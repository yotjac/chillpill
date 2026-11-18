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
 * Rule ids (P*, E*, A*, G*) refer to the spec; L* (link pass) to specs/link-pass.md; R* (reel
 * finish) to specs/reel-finish.md.
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
    private var screenOffHome = false

    /**
     * Last SystemUI window (shade, heads-up, Pixel's back-gesture panel) seen while an app was in
     * front. Disqualifies L1 only when no activity of the app in front came after it: the user went
     * on inside that app, so it was not a notification tap.
     */
    private var lastSystemUiAt = Long.MIN_VALUE

    /** Latest window event of an activity of [presence]'s app (presence.since is only its first). */
    private var presenceWindowAt = Long.MIN_VALUE

    /** When each app's last link visit started (A2b); a link within 15 min of it is capped (L3). */
    private val lastLinkPassAt = HashMap<String, Long>()

    /** Where an entry came from (L1): the presence before it and its app's latest activity event. */
    private class Source(val presence: Presence, val lastWindowAt: Long)


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

    /** Rule a new session of [pkg] starts with; apps still pending from the popup use the defaults. */
    private fun ruleFor(pkg: String): AppRule? = config?.let { it.rules[pkg] ?: it.defaultRule }

    private val Session.pauseOnly: Boolean get() = rule.mode == Mode.PAUSE_ONLY && !linkPass

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
            is Input.ReelProgress -> onReelProgress(input)
            is Input.ReelSwiped -> onReelSwiped(input, out)
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
                if (input.pkg == WindowClassifier.SYSTEM_UI_PACKAGE && input.at >= presence.since) {
                    // The shade or a heads-up: whatever opens next may be a notification tap (L1).
                    lastSystemUiAt = maxOf(lastSystemUiAt, input.at)
                }
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
            WindowKind.APP, WindowKind.OWN_MAIN_UI ->
                onForeground(input.pkg, input.kind, input.at, out, home = input.home, viaWindow = true)
        }
    }

    /**
     * P1: [pkg] is in front as of [at]. [viaWindow]: seen through its own window event, so what
     * was in front before is where it was opened from (L1); probes and fallbacks are not.
     */
    private fun onForeground(
        pkg: String,
        kind: WindowKind,
        at: Long,
        out: MutableList<Effect>,
        home: Boolean = false,
        viaWindow: Boolean = false
    ) {
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
            val from = Source(presence, maxOf(presenceWindowAt, presence.since))
            leave(presence.pkg, at)
            presence = Presence(pkg, kind, at, screenOn = true, home = home)
            presenceWindowAt = at
            if (kind == WindowKind.APP) evaluate(pkg, at, entry = true, out, openedFrom = if (viaWindow) from else null)
        } else {
            if (viaWindow) presenceWindowAt = maxOf(presenceWindowAt, at)
            if (!presence.screenOn) presence = presence.copy(screenOn = true)
            if (kind == WindowKind.APP) evaluate(pkg, at, entry = false, out)
        }
    }

    /** User is no longer in [pkg] as of [at]. Never moves a deadline (I5). */
    private fun leave(pkg: String?, at: Long) {
        if (pkg == null) return
        val state = apps[pkg]
        if (state is Session && state.leftAt == null) {
            apps[pkg] = state.copy(leftAt = at)
            // Have UsageStats confirm the departure as soon as its answer can be trusted (P6b),
            // rather than at the next reconciliation up to 5 s later.
            followUpProbes += at + LEAVE_CHECK_DELAY_MS
        }
        if (suggestion?.pkg == pkg) suggestion = null // B2
    }

    private fun onScreenOff(at: Long) { // P4
        leave(presence.pkg, at)
        screenOffHome = presence.kind == WindowKind.APP && presence.home
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
        if (presence.screenOn && r != null && r.kind == WindowKind.APP && r.pkg != presence.pkg) {
            if (r.resumedAt > presence.since) {
                // UsageStats saw a switch newer than our last window event: believe it, as of the
                // moment it happened.
                onForeground(r.pkg, WindowKind.APP, r.resumedAt, out, home = r.home)
                return
            }
            if (r.onlyResumed && r.queriedAt - presence.since >= PROBE_SETTLE_MS) { // P6b
                // UsageStats positively contradicts us: well after our last window event, r.pkg
                // is the only package with a resumed activity, so whatever that window was, it
                // did not take the foreground (the launcher resumes for a moment under the app
                // in front without pausing it). The user never left r.pkg: put presence back as
                // of the false departure, so its session sees an absence of zero.
                onForeground(r.pkg, WindowKind.APP, presence.since, out, home = r.home)
                return
            }
        }
        if (inBurst && burst === b && b!!.slots.isEmpty() && b.outstanding.isEmpty()) {
            burst = null
            val fallback = screenOffPkg
            if (presence.pkg == null && presence.screenOn && fallback != null) {
                onForeground(fallback, WindowKind.APP, maxOf(b.startedAt, presence.since), out, home = screenOffHome)
            }
        }
    }

    // ---- session rules (E1–E8) ------------------------------------------------------------

    /** [openedFrom]: presence just before an entry seen through a window event (L1). */
    private fun evaluate(pkg: String, at: Long, entry: Boolean, out: MutableList<Effect>, openedFrom: Source? = null) {
        if (pkg !in restricted) {
            if (entry) out += Effect.EvaluateSuggestion(pkg)
            return
        }
        when (val state = apps[pkg]) {
            null -> if (!linkPass(pkg, at, openedFrom, out)) block(pkg, BlockKind.INITIAL, out) // E2 / L1
            is Blocking -> // E3 / E4
                if (!state.uiVisible && at - state.shownAt >= RELAUNCH_GUARD_MS) reshow(pkg, state, out)
            is Session -> {
                // E5: still inside. A leftAt here means we had lost track; treat it as an entry.
                if (!entry && state.leftAt == null) return
                val leftAt = state.leftAt
                val allowed = at < state.graceDeadline ||                                   // E6
                    (state.pauseOnly && (leftAt == null || at - leftAt < RETURN_WINDOW_MS)) // E7
                if (allowed) {
                    apps[pkg] = state.copy(leftAt = null)
                } else {
                    apps.remove(pkg) // E8
                    if (!linkPass(pkg, at, openedFrom, out)) block(pkg, BlockKind.INITIAL, out) // L1
                }
            }
        }
    }

    /**
     * L1 (specs/link-pass.md): [pkg] was opened by another app (a link, a share). If its rule
     * allows a link pass, show the link picker (no wait, "how long?") instead of the normal block.
     * L3: if a link visit of [pkg] started less than [LINK_REPEAT_WINDOW_MS] ago, the picker only
     * goes up to [LINK_REPEAT_MAX_MS] and says why. Fails strict: anything that is
     * not clearly "came straight from another app's activity" gets the normal block.
     */
    private fun linkPass(pkg: String, at: Long, source: Source?, out: MutableList<Effect>): Boolean {
        val from = source?.presence ?: return false
        val rule = ruleFor(pkg) ?: return false
        val limitMs = rule.linkPassMs ?: return false
        if (from.pkg == null || from.pkg == pkg || from.kind != WindowKind.APP || from.home || !from.screenOn) return false
        // Shade / heads-up after the source app's last activity event: a notification tap.
        if (lastSystemUiAt >= source.lastWindowAt) return false
        // No OPEN_ATTEMPT: this is not a block. LINK_PASS is recorded when the user picks (A2b).
        val repeat = lastLinkPassAt[pkg]?.let { at - it < LINK_REPEAT_WINDOW_MS } == true
        val maxMs = if (repeat) minOf(limitMs, LINK_REPEAT_MAX_MS) else limitMs
        val limited = repeat && maxMs < limitMs
        apps[pkg] = Blocking(
            BlockKind.LINK, shownAt = clock, uiVisible = false, instance = null,
            linkMaxMs = maxMs, linkLimited = limited
        )
        out += Effect.ShowBlock(pkg, BlockKind.LINK, maxMs, limited)
        return true
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
        out += Effect.ShowBlock(pkg, state.kind, state.linkMaxMs, state.linkLimited)
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
        val blocking = apps[input.pkg] as? Blocking ?: return
        val rule = ruleFor(input.pkg) ?: return
        if (blocking.kind == BlockKind.LINK) { // A2b
            lastLinkPassAt[input.pkg] = input.at
            apps[input.pkg] = Session(
                graceDeadline = input.at + linkPickedMs(input.chosenMs, rule, blocking.linkMaxMs),
                rule = rule,
                linkPass = true,
                leftAt = if (presenceIs(input.pkg)) null else input.at
            )
            out += Effect.RecordUsage(input.pkg, UsageKind.LINK_PASS)
            return
        }
        val graceMs = if (rule.mode == Mode.PICK) pickedMs(input.chosenMs) else rule.graceMs
        apps[input.pkg] = Session(
            graceDeadline = input.at + graceMs,
            rule = rule,
            leftAt = if (presenceIs(input.pkg)) null else input.at
        )
        out += Effect.RecordUsage(input.pkg, UsageKind.WAIT_COMPLETED)
    }

    private fun onExtend(input: Input.ExtendTapped, out: MutableList<Effect>) { // A5
        val state = apps[input.pkg] as? Session ?: return
        if (state.graceOver || input.at >= state.graceDeadline) return
        if (state.reelHeld) return // R2: the hold is the extra time
        if (!state.rule.extensionAllowed || state.extensionsUsed >= MAX_GRACE_EXTENSIONS) return
        apps[input.pkg] = state.copy(
            graceDeadline = state.graceDeadline + GRACE_EXTENSION_MS,
            extensionsUsed = state.extensionsUsed + 1,
            extendedAt = input.at
        )
        out += Effect.RecordUsage(input.pkg, UsageKind.GRACE_EXTENDED)
    }

    /**
     * A2b: a link visit: whole minutes, 1 min up to what the picker offered ([shownMaxMs], L3) and
     * the rule's current limit (the full limit if nothing was picked; 1 min if link passes were
     * turned off since the picker appeared).
     */
    private fun linkPickedMs(chosenMs: Long?, rule: AppRule, shownMaxMs: Long?): Long {
        val ruleMax = rule.linkPassMs ?: PICK_MIN_MS
        val max = minOf(ruleMax, shownMaxMs ?: ruleMax).coerceIn(PICK_MIN_MS, PICK_MAX_MS)
        if (chosenMs == null) return max - max % MINUTE_MS
        val minutes = (chosenMs + MINUTE_MS / 2) / MINUTE_MS
        return (minutes * MINUTE_MS).coerceIn(PICK_MIN_MS, max - max % MINUTE_MS)
    }

    /** A2: the visit length a PICK app gets for [chosenMs]: whole minutes, 1–90; 5 min if none. */
    private fun pickedMs(chosenMs: Long?): Long {
        if (chosenMs == null) return PICK_FALLBACK_MS
        val minutes = (chosenMs + MINUTE_MS / 2) / MINUTE_MS
        return (minutes * MINUTE_MS).coerceIn(PICK_MIN_MS, PICK_MAX_MS)
    }

    private fun onWarningDismissed(input: Input.WarningDismissed) { // A6
        val state = apps[input.pkg] as? Session ?: return
        apps[input.pkg] = state.copy(firstWarningDismissed = true)
    }

    // ---- reel finish (R1–R4, specs/reel-finish.md) ----------------------------------------

    /** R1: remember that a reel is playing in the app in front, and when it ends. */
    private fun onReelProgress(input: Input.ReelProgress) {
        val state = apps[input.pkg] as? Session ?: return
        if (!state.rule.finishReel || state.pauseOnly || state.graceOver || state.reelHeld) return
        if (!presenceIs(input.pkg) || input.remainingMs < 0L) return
        apps[input.pkg] = state.copy(reelSeenAt = input.at, reelEndsAt = input.at + input.remainingMs)
    }

    /**
     * R3: a swipe to another reel. While held, that is the moment the hold was waiting for: the
     * session expires now. Otherwise what was known about the previous reel no longer holds.
     */
    private fun onReelSwiped(input: Input.ReelSwiped, out: MutableList<Effect>) {
        val state = apps[input.pkg] as? Session ?: return
        if (!presenceIs(input.pkg) || state.graceOver) return
        if (state.reelHeld) {
            expire(input.pkg, state, out)
        } else if (state.reelSeenAt != null) {
            apps[input.pkg] = state.copy(reelSeenAt = null, reelEndsAt = null)
        }
    }

    /**
     * R2: [state] ran out of time with the user in the app. If a reel was seen playing just before
     * and ends soon, the session with its deadline moved to the reel's end; else null. Once per
     * session: the hold's own expiry, and a swipe, go through.
     */
    private fun reelHold(state: Session): Session? {
        if (!state.rule.finishReel || state.pauseOnly || state.reelHeld) return null
        val seenAt = state.reelSeenAt ?: return null
        val endsAt = state.reelEndsAt ?: return null
        if (state.graceDeadline - seenAt > REEL_FRESH_MS) return null
        val left = endsAt - state.graceDeadline
        if (left <= 0L || left > REEL_HOLD_MAX_MS) return null
        return state.copy(graceDeadline = endsAt + REEL_END_SLACK_MS, reelHeld = true)
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
        val rule = config?.defaultRule ?: return
        // A PICK default: the user never picked, so they get the fallback length.
        val graceMs = if (rule.mode == Mode.PICK) PICK_FALLBACK_MS else rule.graceMs
        pendingRestricted += input.pkg
        apps[input.pkg] = Session(
            graceDeadline = input.at + graceMs,
            rule = rule,
            leftAt = if (presenceIs(input.pkg)) null else input.at
        )
    }

    // ---- time: grace expiry (G1–G3) and probe scheduling ----------------------------------

    /** Processes every grace deadline at or before [t], earliest first. */
    private fun advanceTo(t: Long, out: MutableList<Effect>) {
        // A PAUSE_ONLY session past its grace, left 10 s ago or more, can only end in a block on
        // the next entry: end it now so nothing (reconciliation probes) keeps running for it.
        apps.entries.removeAll { (_, s) ->
            s is Session && s.graceOver && s.leftAt != null && t - s.leftAt >= RETURN_WINDOW_MS
        }
        // Earliest first, whether it is a deadline or an idle reset, so a long gap between inputs
        // ends each session the way it would have ended in real time.
        while (true) {
            var dueKey: String? = null
            var dueAt = Long.MAX_VALUE
            var dueIsIdle = false
            for ((pkg, state) in apps) {
                if (state !is Session) continue
                if (!state.graceOver && state.graceDeadline <= t && state.graceDeadline < dueAt) {
                    dueKey = pkg
                    dueAt = state.graceDeadline
                    dueIsIdle = false
                }
                val idleAt = state.idleResetAt
                if (idleAt != null && idleAt <= t && idleAt < dueAt) {
                    dueKey = pkg
                    dueAt = idleAt
                    dueIsIdle = true
                }
            }
            val pkg = dueKey ?: return
            val state = apps[pkg] as Session
            if (dueIsIdle) idleReset(pkg, state, out) else expire(pkg, state, out)
        }
    }

    /** When [IDLE_RESET_MS] away from the app ends this session (G4); null while the user is in it. */
    private val Session.idleResetAt: Long? get() = leftAt?.let { it + IDLE_RESET_MS }

    /**
     * G4: the user has been away from [pkg] (another app, home, our own screens, screen off) for
     * [IDLE_RESET_MS] in a row. Whatever was left of its grace, return window or link pass is
     * dropped: the next entry goes through the block again.
     */
    private fun idleReset(pkg: String, state: Session, out: MutableList<Effect>) {
        apps.remove(pkg)
        // A PAUSE_ONLY session past its grace already recorded its expiry (G1).
        if (!state.graceOver) out += Effect.RecordUsage(pkg, UsageKind.GRACE_EXPIRED_WHILE_AWAY)
    }

    private fun expire(pkg: String, state: Session, out: MutableList<Effect>) {
        if (state.leftAt != null && state.pauseOnly && state.graceDeadline - state.leftAt >= RETURN_WINDOW_MS) {
            // Away for 10 s or more when grace ran out: this session is already over.
            apps.remove(pkg)
            out += Effect.RecordUsage(pkg, UsageKind.GRACE_EXPIRED_WHILE_AWAY)
            return
        }
        val present = presenceIs(pkg)
        if (present) {
            reelHold(state)?.let { // R2: let the reel that is playing finish
                apps[pkg] = it
                return
            }
        }
        when {
            state.pauseOnly -> { // G1
                apps[pkg] = state.copy(graceOver = true)
                out += Effect.RecordUsage(
                    pkg,
                    if (present) UsageKind.GRACE_EXPIRED_WHILE_ACTIVE else UsageKind.GRACE_EXPIRED_WHILE_AWAY
                )
            }
            present && state.linkPass -> { // L2: the pass is over; now the app's normal block
                apps.remove(pkg)
                block(pkg, BlockKind.INITIAL, out)
                out += Effect.RecordUsage(pkg, UsageKind.GRACE_EXPIRED_WHILE_ACTIVE)
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
                // With the session gone reconciliation stops. Look once more: if "away" was a
                // false departure just before the deadline (too fresh for P6b), the probe puts
                // presence back and the app is blocked (E2) instead of running free until its
                // next window event.
                scheduleFollowUpProbes(state.graceDeadline)
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
        if (!presenceIs(pkg)) return null
        val state = apps[pkg] as? Session ?: return null
        if (state.pauseOnly || state.graceOver) return null
        val remaining = state.graceDeadline - now
        if (remaining <= 0L) return null
        if (state.reelHeld) {
            // R2: time is up, the reel is finishing. Shown for the whole hold, so the missing block is explained.
            return WarningUi(pkg, state.graceDeadline, canExtend = false, dismissible = false, reelHold = true)
        }
        val extendedAt = state.extendedAt
        if (extendedAt != null && now - extendedAt in 0 until EXTEND_CONFIRMATION_MS) {
            // "10 s added": the pill confirms an accepted extension for a moment.
            return WarningUi(pkg, state.graceDeadline, canExtend = false, dismissible = false, extensionConfirmed = true)
        }
        val firstAvailable = state.extensionsUsed < MAX_GRACE_EXTENSIONS && !state.firstWarningDismissed
        return when {
            firstAvailable && remaining <= WARNING_LEAD_MS ->
                WarningUi(pkg, state.graceDeadline, canExtend = state.rule.extensionAllowed, dismissible = true)
            remaining <= FINAL_WARNING_LEAD_MS ->
                WarningUi(pkg, state.graceDeadline, canExtend = false, dismissible = false)
            else -> null
        }
    }

    /**
     * R4: the app whose reel viewer the host should follow right now, or null: the app in front,
     * with a session that may be held for a reel, in the last [REEL_WATCH_LEAD_MS] of its time or
     * during the hold. Outside that window nothing is sampled.
     */
    fun reelWatch(now: Long): String? {
        val pkg = presence.pkg ?: return null
        if (!presenceIs(pkg)) return null
        val state = apps[pkg] as? Session ?: return null
        if (!state.rule.finishReel || state.pauseOnly || state.graceOver) return null
        return if (state.reelHeld || state.graceDeadline - now <= REEL_WATCH_LEAD_MS) pkg else null
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
            if (state is Session) consider(state.idleResetAt)
            if (state is Session && !state.graceOver) {
                consider(state.graceDeadline - WARNING_LEAD_MS)
                consider(state.graceDeadline - FINAL_WARNING_LEAD_MS)
                consider(state.graceDeadline - PRE_DEADLINE_PROBE_MS)
                consider(state.graceDeadline)
                if (state.rule.finishReel) consider(state.graceDeadline - REEL_WATCH_LEAD_MS)
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
        /** PAUSE_ONLY apps: leaving for less than this never re-triggers the block screen. */
        const val RETURN_WINDOW_MS = 10_000L
        /**
         * G4: this long away from an app in a row ends its session: grace, return window and link
         * pass no longer let the user back in without the block.
         */
        const val IDLE_RESET_MS = 2L * 60_000L
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
        /**
         * P6b: a probe must have been taken this long after the last window event before
         * "another package is the only resumed one" is believed; a real switch is in UsageStats
         * well within that time.
         */
        const val PROBE_SETTLE_MS = 1_000L
        /** A session app was left: probe once the answer is old enough for P6b. */
        const val LEAVE_CHECK_DELAY_MS = PROBE_SETTLE_MS + 200L
        const val PRE_DEADLINE_PROBE_MS = 1_500L
        const val BURST_PROBES = 4
        const val BURST_SPACING_MS = 400L
        val FOLLOW_UP_PROBE_DELAYS_MS = longArrayOf(300L, 1_000L)

        /** R2: a reel that ends later than this after time ran out is not waited for. */
        const val REEL_HOLD_MAX_MS = 90_000L
        /** R2: "a reel is playing" must have been reported this recently when time runs out. */
        const val REEL_FRESH_MS = 2_500L
        /** R2: the block comes this long after the reel's reported end. */
        const val REEL_END_SLACK_MS = 500L
        /** R4: the host follows the reel viewer from this long before the deadline. */
        const val REEL_WATCH_LEAD_MS = 15_000L

        private const val MINUTE_MS = 60_000L
        /** PICK: range of a visit the user can choose (whole minutes). */
        const val PICK_MIN_MS = 1L * MINUTE_MS
        const val PICK_MAX_MS = 90L * MINUTE_MS
        /** PICK: length when nothing was picked (Continue without a choice, suggestion "Restrict"). */
        const val PICK_FALLBACK_MS = 5L * MINUTE_MS
        /** L3: a link this soon after the start of the app's previous link visit is capped... */
        const val LINK_REPEAT_WINDOW_MS = 15L * MINUTE_MS
        /** ...to this. */
        const val LINK_REPEAT_MAX_MS = 2L * MINUTE_MS
    }
}
