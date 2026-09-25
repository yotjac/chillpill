package com.chillpill.service.engine

import com.chillpill.service.engine.AppState.Blocking
import com.chillpill.service.engine.AppState.Session
import java.util.TreeSet

/**
 * The whole blocking pipeline as one deterministic state machine (specs/session-engine.md).
 *
 * - Pure: no Android, no coroutines, no I/O, no clock of its own. Every input carries its time.
 * - Not thread-safe by design: [SessionEngine] calls it from a single thread.
 * - [handle] mutates state and returns the effects the host must execute. Derived UI state
 *   ([warning], [suggestion], [graceActive]) and the next wake-up are read after each call.
 *
 * Rule ids (P*, E*, A*, G*) refer to the spec.
 */
class EngineCore {

    var config: Config? = null
        private set

    /** Apps restricted from the suggestion popup whose DataStore write has not come back yet (A8). */
    private val pendingRestricted = HashSet<String>()

    var presence = Presence(pkg = null, kind = null, since = Long.MIN_VALUE, screenOn = true)
        private set

    private val apps = HashMap<String, AppState>()

    /** Package in front when the screen last went off (P4); fallback of the probe burst (P6). */
    private var screenOffPkg: String? = null

    var suggestion: SuggestionUi? = null
        private set

    /** Latest time seen on any input; the engine's notion of "now". */
    private var clock = Long.MIN_VALUE
    private var lastPumped = Long.MIN_VALUE
    private var nextReconcileAt: Long? = null
    private val followUpProbes = TreeSet<Long>()
    private var burst: Burst? = null
    private var nextToken = 1L

    private class Burst(val startedAt: Long, val slots: ArrayDeque<Long>, val outstanding: MutableSet<Long>)

    // ---------------------------------------------------------------------------------------
    // Read access (host, tests)
    // ---------------------------------------------------------------------------------------

    val appStates: Map<String, AppState> get() = HashMap(apps)

    fun appState(pkg: String): AppState? = apps[pkg]

    private val restricted: Set<String>
        get() = (config?.restricted ?: emptySet()) + pendingRestricted

    private fun isNoReblock(pkg: String) = config?.noReblock?.contains(pkg) == true

    // ---------------------------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------------------------

    fun handle(input: Input): List<Effect> {
        val out = ArrayList<Effect>()
        if (input.at > clock) clock = input.at
        if (input is Input.ConfigChanged) {
            applyConfig(input.config)
            pump(out)
            return out
        }
        // The host holds inputs back until the first config; this is only a safety net.
        if (config == null) return out

        advanceTo(clock, out)
        when (input) {
            is Input.Window -> onWindow(input, out)
            is Input.ScreenOff -> onScreenOff(input.at)
            is Input.ScreenOn -> onScreenOn(input.at, startBurst = !input.keyguardLocked)
            is Input.UserPresent -> onScreenOn(input.at, startBurst = true)
            is Input.Probe -> onProbe(input, out)
            is Input.BlockStarted -> onBlockStarted(input)
            is Input.BlockStopped -> onBlockStopped(input, out)
            is Input.BlockClosed -> onBlockClosed(input, out)
            is Input.ContinueTapped -> onContinue(input, out)
            is Input.ExtendTapped -> onExtend(input, out)
            is Input.WarningDismissed -> onWarningDismissed(input)
            is Input.SuggestionEligible -> onSuggestionEligible(input)
            is Input.SuggestionAnswered -> onSuggestionAnswered(input, out)
            is Input.Tick, is Input.ProcessStarted, is Input.ConfigChanged -> Unit
        }
        pump(out)
        return out
    }

    private fun applyConfig(newConfig: Config) {
        config = newConfig
        pendingRestricted.removeAll(newConfig.restricted)
        // A7: an app that is no longer restricted has no session and no block.
        val allowed = restricted
        apps.keys.retainAll(allowed)
        if (suggestion?.pkg in allowed) suggestion = null
    }

    // ---- presence (P1–P6) -----------------------------------------------------------------

    private fun onWindow(input: Input.Window, out: MutableList<Effect>) {
        when (input.kind) {
            WindowKind.OWN_OVERLAY, WindowKind.APP_OVERLAY -> Unit // P3
            WindowKind.SYSTEM_OVERLAY -> { // P3
                // Over an app, SystemUI / keyboard windows never change presence. Over our block
                // screen they do: whatever the user reaches from the shade (the blocked app's own
                // notification) must not be mistaken for that app's late splash event.
                if (presence.kind == WindowKind.OWN_BLOCK_UI && input.at >= presence.since) {
                    presence = Presence(input.pkg, WindowKind.SYSTEM_OVERLAY, input.at, screenOn = true)
                }
            }
            WindowKind.OWN_BLOCK_UI -> { // P2
                if (input.at < presence.since) return
                leave(presence.pkg, input.at)
                presence = Presence(input.pkg, WindowKind.OWN_BLOCK_UI, input.at, screenOn = true)
                burst = null
            }
            WindowKind.APP, WindowKind.OWN_MAIN_UI -> onForeground(input.pkg, input.kind, input.at, out)
        }
    }

    /** P1: [pkg] is in front as of [at]. */
    private fun onForeground(pkg: String, kind: WindowKind, at: Long, out: MutableList<Effect>) {
        if (at < presence.since) return // older than what we already know
        val state = apps[pkg]
        if (kind == WindowKind.APP && state is Blocking && presence.kind == WindowKind.OWN_BLOCK_UI &&
            at - state.shownAt < ABSORB_MS
        ) {
            // Our block screen for this very app came up moments ago and is still in front: this
            // is the app's own late window event (splash -> main), not the app coming back in
            // front of the block. Later events do move presence, so getting past the block is
            // caught by BlockStopped / BlockClosed (E4) even without usage access.
            return
        }
        burst = null
        if (pkg != presence.pkg) {
            leave(presence.pkg, at)
            presence = Presence(pkg, kind, at, screenOn = true)
            if (kind == WindowKind.APP) evaluate(pkg, at, entry = true, out)
        } else {
            if (!presence.screenOn) presence = presence.copy(screenOn = true)
            if (kind == WindowKind.APP) evaluate(pkg, at, entry = false, out)
        }
    }

    /** User is no longer in [pkg] as of [at]. Never moves a deadline (I5). */
    private fun leave(pkg: String?, at: Long) {
        if (pkg == null) return
        val state = apps[pkg]
        if (state is Session && state.leftAt == null) apps[pkg] = state.copy(leftAt = at)
        if (suggestion?.pkg == pkg) suggestion = null // B2
    }

    private fun onScreenOff(at: Long) { // P4
        leave(presence.pkg, at)
        screenOffPkg = when (presence.kind) {
            WindowKind.APP -> presence.pkg
            // Block screen in front: the user is "at" the blocked app. If the screen is gone after
            // unlock (NO_HISTORY) and nothing reports, the fallback re-evaluates that app.
            WindowKind.OWN_BLOCK_UI -> apps.entries.firstOrNull { (it.value as? Blocking)?.uiVisible == true }?.key
            else -> null
        }
        presence = Presence(null, null, maxOf(at, presence.since), screenOn = false)
        burst = null
        suggestion = null
    }

    private fun onScreenOn(at: Long, startBurst: Boolean) { // P5
        if (!presence.screenOn) presence = presence.copy(screenOn = true)
        if (startBurst) {
            val slots = ArrayDeque<Long>()
            for (i in 0 until BURST_PROBES) slots.addLast(at + i * BURST_SPACING_MS)
            burst = Burst(at, slots, HashSet())
        }
    }

    private fun onProbe(input: Input.Probe, out: MutableList<Effect>) { // P6
        val b = burst
        val inBurst = b != null && b.outstanding.remove(input.token)
        val r = input.result
        if (presence.screenOn && r != null && r.kind == WindowKind.APP &&
            r.pkg != presence.pkg && r.resumedAt > presence.since
        ) {
            // UsageStats saw a switch newer than our last window event: believe it, as of the
            // moment it happened.
            onForeground(r.pkg, WindowKind.APP, r.resumedAt, out)
            return
        }
        if (inBurst && burst === b && b!!.slots.isEmpty() && b.outstanding.isEmpty()) {
            burst = null
            val fallback = screenOffPkg
            if (presence.pkg == null && presence.screenOn && fallback != null) {
                onForeground(fallback, WindowKind.APP, maxOf(b.startedAt, presence.since), out)
            }
        }
    }

    // ---- session rules (E1–E8) ------------------------------------------------------------

    private fun evaluate(pkg: String, at: Long, entry: Boolean, out: MutableList<Effect>) {
        if (pkg !in restricted) {
            if (entry) out += Effect.EvaluateSuggestion(pkg)
            return
        }
        when (val state = apps[pkg]) {
            null -> block(pkg, BlockKind.INITIAL, out) // E2
            is Blocking -> // E3 / E4
                if (!state.uiVisible && at - state.shownAt >= RELAUNCH_GUARD_MS) reshow(pkg, state, out)
            is Session -> {
                // E5: still inside. A leftAt here means we had lost track; treat it as an entry.
                if (!entry && state.leftAt == null) return
                val leftAt = state.leftAt
                val allowed = at < state.graceDeadline ||                                   // E6
                    (isNoReblock(pkg) && (leftAt == null || at - leftAt < RETURN_WINDOW_MS)) // E7
                if (allowed) {
                    apps[pkg] = state.copy(leftAt = null)
                } else {
                    apps.remove(pkg) // E8
                    block(pkg, BlockKind.INITIAL, out)
                }
            }
        }
    }

    private fun block(pkg: String, kind: BlockKind, out: MutableList<Effect>) {
        apps[pkg] = Blocking(kind, shownAt = clock, uiVisible = false, instance = null)
        out += Effect.ShowBlock(pkg, kind)
        out += Effect.RecordUsage(pkg, UsageKind.OPEN_ATTEMPT)
    }

    /** E4: the user got to [pkg] past its block screen; bring the block back. */
    private fun reshow(pkg: String, state: Blocking, out: MutableList<Effect>) {
        // instance = null: the old activity is going away; only the new one's reports count.
        apps[pkg] = state.copy(shownAt = clock, uiVisible = false, instance = null)
        out += Effect.ShowBlock(pkg, state.kind)
        out += Effect.RecordUsage(pkg, UsageKind.OPEN_ATTEMPT)
    }

    private fun presenceIs(pkg: String) =
        presence.pkg == pkg && presence.kind == WindowKind.APP && presence.screenOn

    // ---- block screen and user actions (A1–A10) -------------------------------------------

    private fun onBlockStarted(input: Input.BlockStarted) { // A1
        val state = apps[input.pkg] as? Blocking ?: return
        apps[input.pkg] = state.copy(uiVisible = true, instance = input.instance)
    }

    private fun onBlockStopped(input: Input.BlockStopped, out: MutableList<Effect>) { // A1
        val state = apps[input.pkg] as? Blocking ?: return
        if (state.instance != input.instance) return
        apps[input.pkg] = state.copy(uiVisible = false)
        scheduleFollowUpProbes(input.at)
        if (presenceIs(input.pkg)) reshow(input.pkg, state, out)
    }

    private fun onBlockClosed(input: Input.BlockClosed, out: MutableList<Effect>) { // A4
        val state = apps[input.pkg] as? Blocking ?: return
        if (state.instance != input.instance) return
        apps.remove(input.pkg)
        out += Effect.RecordUsage(input.pkg, UsageKind.LEFT_APP)
        scheduleFollowUpProbes(input.at)
        // Closed by the system while the app itself is in front: the user got past the block.
        // Go Home / Back are explicit exits and never re-block, whatever event came late.
        if (input.reason == CloseReason.SYSTEM_GESTURE && presenceIs(input.pkg)) {
            block(input.pkg, BlockKind.INITIAL, out)
        }
    }

    private fun onContinue(input: Input.ContinueTapped, out: MutableList<Effect>) { // A2 / A3
        if (apps[input.pkg] !is Blocking) return
        val graceMs = config?.graceMs ?: return
        apps[input.pkg] = Session(
            graceDeadline = input.at + graceMs,
            leftAt = if (presenceIs(input.pkg)) null else input.at
        )
        out += Effect.RecordUsage(input.pkg, UsageKind.WAIT_COMPLETED)
    }

    private fun onExtend(input: Input.ExtendTapped, out: MutableList<Effect>) { // A5
        val state = apps[input.pkg] as? Session ?: return
        if (state.graceOver || input.at >= state.graceDeadline) return
        if (state.extensionsUsed >= MAX_GRACE_EXTENSIONS) return
        apps[input.pkg] = state.copy(
            graceDeadline = state.graceDeadline + GRACE_EXTENSION_MS,
            extensionsUsed = state.extensionsUsed + 1,
            extendedAt = input.at
        )
        out += Effect.RecordUsage(input.pkg, UsageKind.GRACE_EXTENDED)
    }

    private fun onWarningDismissed(input: Input.WarningDismissed) { // A6
        val state = apps[input.pkg] as? Session ?: return
        apps[input.pkg] = state.copy(firstWarningDismissed = true)
    }

    private fun onSuggestionEligible(input: Input.SuggestionEligible) { // A10
        if (presenceIs(input.pkg) && input.pkg !in restricted) {
            suggestion = SuggestionUi(input.pkg, input.appName)
        }
    }

    private fun onSuggestionAnswered(input: Input.SuggestionAnswered, out: MutableList<Effect>) { // A8 / A9
        if (suggestion?.pkg == input.pkg) suggestion = null
        out += Effect.PersistSuggestionAnswer(input.pkg, input.answer)
        if (input.answer != SuggestionAnswer.RESTRICT || input.pkg in restricted) return
        val graceMs = config?.graceMs ?: return
        pendingRestricted += input.pkg
        apps[input.pkg] = Session(
            graceDeadline = input.at + graceMs,
            leftAt = if (presenceIs(input.pkg)) null else input.at
        )
    }

    // ---- time: grace expiry (G1–G3) and probe scheduling ----------------------------------

    /** Processes every grace deadline at or before [t], earliest first. */
    private fun advanceTo(t: Long, out: MutableList<Effect>) {
        // A no-re-block session past its grace, left 10 s ago or more, can only end in a block on
        // the next entry: end it now so nothing (reconciliation probes) keeps running for it.
        apps.entries.removeAll { (_, s) ->
            s is Session && s.graceOver && s.leftAt != null && t - s.leftAt >= RETURN_WINDOW_MS
        }
        while (true) {
            var dueKey: String? = null
            var dueDeadline = Long.MAX_VALUE
            for ((pkg, state) in apps) {
                if (state is Session && !state.graceOver && state.graceDeadline <= t && state.graceDeadline < dueDeadline) {
                    dueKey = pkg
                    dueDeadline = state.graceDeadline
                }
            }
            val pkg = dueKey ?: return
            expire(pkg, apps[pkg] as Session, out)
        }
    }

    private fun expire(pkg: String, state: Session, out: MutableList<Effect>) {
        if (state.leftAt != null && isNoReblock(pkg) && state.graceDeadline - state.leftAt >= RETURN_WINDOW_MS) {
            // Away for 10 s or more when grace ran out: this session is already over.
            apps.remove(pkg)
            out += Effect.RecordUsage(pkg, UsageKind.GRACE_EXPIRED_WHILE_AWAY)
            return
        }
        val present = presenceIs(pkg)
        when {
            isNoReblock(pkg) -> { // G1
                apps[pkg] = state.copy(graceOver = true)
                out += Effect.RecordUsage(
                    pkg,
                    if (present) UsageKind.GRACE_EXPIRED_WHILE_ACTIVE else UsageKind.GRACE_EXPIRED_WHILE_AWAY
                )
            }
            present -> { // G2
                apps[pkg] = Blocking(BlockKind.RE_INTERVENTION, shownAt = clock, uiVisible = false, instance = null)
                out += Effect.ShowBlock(pkg, BlockKind.RE_INTERVENTION)
                out += Effect.RecordUsage(pkg, UsageKind.OPEN_ATTEMPT)
                out += Effect.RecordUsage(pkg, UsageKind.GRACE_EXPIRED_WHILE_ACTIVE)
            }
            else -> { // G3
                apps.remove(pkg)
                out += Effect.RecordUsage(pkg, UsageKind.GRACE_EXPIRED_WHILE_AWAY)
            }
        }
    }

    private fun scheduleFollowUpProbes(at: Long) {
        for (delay in FOLLOW_UP_PROBE_DELAYS_MS) followUpProbes += at + delay
    }

    /** Emits the probes that are due by [clock] (P5, P7, follow-ups). */
    private fun pump(out: MutableList<Effect>) {
        val now = clock
        val anySession = apps.values.any { it is Session }
        if (anySession && presence.screenOn) {
            if (nextReconcileAt == null) nextReconcileAt = now + RECONCILE_INTERVAL_MS
        } else {
            nextReconcileAt = null
        }
        var probe = false
        nextReconcileAt?.let {
            if (it <= now) {
                probe = true
                nextReconcileAt = now + RECONCILE_INTERVAL_MS
            }
        }
        for (state in apps.values) {
            if (state is Session && !state.graceOver) {
                val t = state.graceDeadline - PRE_DEADLINE_PROBE_MS
                if (t > lastPumped && t <= now) probe = true
            }
        }
        while (followUpProbes.isNotEmpty() && followUpProbes.first() <= now) {
            followUpProbes.pollFirst()
            probe = true
        }
        if (probe && presence.screenOn) out += Effect.RequestProbe(nextToken++)
        burst?.let { b ->
            while (b.slots.isNotEmpty() && b.slots.first() <= now) {
                b.slots.removeFirst()
                val token = nextToken++
                b.outstanding += token
                out += Effect.RequestProbe(token)
            }
        }
        lastPumped = now
    }

    // ---------------------------------------------------------------------------------------
    // Derived state
    // ---------------------------------------------------------------------------------------

    /** The grace-expiry pill for the app in front, if any (B1). */
    fun warning(now: Long): WarningUi? {
        val pkg = presence.pkg ?: return null
        if (!presenceIs(pkg) || isNoReblock(pkg)) return null
        val state = apps[pkg] as? Session ?: return null
        if (state.graceOver) return null
        val remaining = state.graceDeadline - now
        if (remaining <= 0L) return null
        val extendedAt = state.extendedAt
        if (extendedAt != null && now - extendedAt in 0 until EXTEND_CONFIRMATION_MS) {
            // "10 s added": the pill confirms an accepted extension for a moment.
            return WarningUi(pkg, state.graceDeadline, canExtend = false, dismissible = false, extensionConfirmed = true)
        }
        val firstAvailable = state.extensionsUsed < MAX_GRACE_EXTENSIONS && !state.firstWarningDismissed
        return when {
            firstAvailable && remaining <= WARNING_LEAD_MS ->
                WarningUi(pkg, state.graceDeadline, canExtend = true, dismissible = true)
            remaining <= FINAL_WARNING_LEAD_MS ->
                WarningUi(pkg, state.graceDeadline, canExtend = false, dismissible = false)
            else -> null
        }
    }

    /** True while any grace window is running (drives the ongoing notification). */
    fun graceActive(): Boolean = apps.values.any { it is Session && !it.graceOver }

    /** Earliest moment after [now] at which the engine needs a [Input.Tick]; null if none. */
    fun nextWakeUp(now: Long): Long? {
        var best: Long? = null
        fun consider(t: Long?) {
            if (t != null && t > now && (best == null || t < best!!)) best = t
        }
        for (state in apps.values) {
            if (state is Session && state.graceOver && state.leftAt != null) consider(state.leftAt + RETURN_WINDOW_MS)
            if (state is Session) state.extendedAt?.let { consider(it + EXTEND_CONFIRMATION_MS) }
            if (state is Session && !state.graceOver) {
                consider(state.graceDeadline - WARNING_LEAD_MS)
                consider(state.graceDeadline - FINAL_WARNING_LEAD_MS)
                consider(state.graceDeadline - PRE_DEADLINE_PROBE_MS)
                consider(state.graceDeadline)
            }
        }
        consider(nextReconcileAt)
        consider(followUpProbes.firstOrNull())
        consider(burst?.slots?.firstOrNull())
        // Anything already due but not yet pumped (e.g. a slot equal to now) needs a tick now.
        val overdue = listOfNotNull(nextReconcileAt, followUpProbes.firstOrNull(), burst?.slots?.firstOrNull())
            .any { it <= now }
        return if (overdue) now + 1 else best
    }

    /** Invariants I1–I4 of the spec; empty when all hold. Checked after every step in tests. */
    fun invariantViolations(now: Long): List<String> {
        val v = ArrayList<String>()
        val allowed = restricted
        for ((pkg, state) in apps) {
            if (pkg !in allowed) v += "I1: state for unrestricted $pkg: $state"
            if (state is Session) {
                val inside = presenceIs(pkg)
                if ((state.leftAt == null) != inside) v += "I2: $pkg leftAt=${state.leftAt} but presence=$presence"
                if (!state.graceOver && state.graceDeadline <= clock) v += "I4: $pkg deadline ${state.graceDeadline} not processed at $clock"
            }
        }
        warning(now)?.let { w ->
            if (w.pkg != presence.pkg) v += "I3: warning for ${w.pkg} while ${presence.pkg} is in front"
        }
        if (presence.kind != null && presence.pkg == null) v += "presence kind without package: $presence"
        return v
    }

    companion object {
        /** No-re-block apps: leaving for less than this never re-triggers the block screen. */
        const val RETURN_WINDOW_MS = 10_000L
        /** The first warning (with "+10 s") appears this long before the deadline. */
        const val WARNING_LEAD_MS = 10_000L
        /** The non-dismissible final countdown appears this long before the deadline. */
        const val FINAL_WARNING_LEAD_MS = 5_000L
        const val GRACE_EXTENSION_MS = 10_000L
        const val MAX_GRACE_EXTENSIONS = 1
        /** A block screen gets this long to come up before an event for its app re-shows it. */
        const val RELAUNCH_GUARD_MS = 2_000L
        /** The app's own late window events (splash -> main) this soon after its block are absorbed. */
        const val ABSORB_MS = 2_000L
        /** How long the pill shows "10 s added" after an accepted extension. */
        const val EXTEND_CONFIRMATION_MS = 1_200L
        const val RECONCILE_INTERVAL_MS = 5_000L
        const val PRE_DEADLINE_PROBE_MS = 1_500L
        const val BURST_PROBES = 4
        const val BURST_SPACING_MS = 400L
        val FOLLOW_UP_PROBE_DELAYS_MS = longArrayOf(300L, 1_000L)
    }
}
