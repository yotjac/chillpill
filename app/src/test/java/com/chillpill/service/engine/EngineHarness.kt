package com.chillpill.service.engine

import org.junit.Assert.fail

const val X = "app.x"          // restricted, "Block again after grace" ON
const val Y = "app.y"          // restricted, ON
const val N = "app.noreblock"  // restricted, OFF
const val S = "app.suggest"    // not restricted
const val L = "launcher"       // home screen
const val OWN = "com.chillpill"
const val GRACE = 60_000L

/**
 * Drives [EngineCore] the way SessionEngine does: sends inputs, fires Tick at every requested
 * wake-up while time advances, answers probes from [truth], and checks the invariants after
 * every step.
 */
class EngineHarness(
    restricted: Set<String> = setOf(X, Y, N),
    noReblock: Set<String> = setOf(N),
    graceMs: Long = GRACE
) {
    val core = EngineCore()
    var now = 1_000_000L
    val effects = ArrayList<Effect>()
    private val history = ArrayList<String>()

    /** What UsageStats would report; null = inconclusive. */
    var truth: ProbeResult? = null

    /** Answer RequestProbe immediately (true) or keep them for [answerProbes]. */
    var autoProbe = true
    val pendingProbes = ArrayList<Long>()

    private var instanceSeq = 100L
    val instances = HashMap<String, Long>()

    init {
        send(Input.ConfigChanged(Config(restricted, noReblock, graceMs), now))
    }

    fun send(input: Input): List<Effect> {
        val fx = core.handle(input)
        history += "$input -> $fx"
        effects += fx
        check(input)
        val probes = fx.filterIsInstance<Effect.RequestProbe>()
        if (probes.isNotEmpty()) {
            if (autoProbe) probes.forEach { send(Input.Probe(truth, it.token, now)) }
            else probes.forEach { pendingProbes += it.token }
        }
        return fx
    }

    fun answerProbes(result: ProbeResult? = truth) {
        val tokens = pendingProbes.toList()
        pendingProbes.clear()
        tokens.forEach { send(Input.Probe(result, it, now)) }
    }

    private fun check(input: Input) {
        val v = core.invariantViolations(now)
        if (v.isNotEmpty()) {
            fail("Invariant violated after $input:\n  ${v.joinToString("\n  ")}\nHistory:\n  " +
                history.takeLast(15).joinToString("\n  "))
        }
    }

    /** Moves time forward, firing every wake-up the engine asked for on the way. */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val w = core.nextWakeUp(now) ?: break
            if (w > target) break
            now = w
            send(Input.Tick(now))
        }
        now = target
    }

    /** Moves time forward without any wake-up (CPU asleep). */
    fun sleep(ms: Long) {
        now += ms
    }

    fun window(pkg: String, kind: WindowKind = WindowKind.APP, at: Long = now, className: String? = null) =
        send(Input.Window(pkg, className, kind, at))

    fun app(pkg: String) = window(pkg)
    fun home() = window(L)
    fun shade() = window("com.android.systemui", WindowKind.SYSTEM_OVERLAY)
    /** A dialog / bottom sheet / popup / toast window of [pkg] (not one of its activities). */
    fun popup(pkg: String) = window(pkg, WindowKind.APP_OVERLAY, className = "android.app.Dialog")
    fun screenOff() = send(Input.ScreenOff(now))
    fun screenOn(locked: Boolean) = send(Input.ScreenOn(locked, now))
    fun userPresent() = send(Input.UserPresent(now))

    /** The block activity for [pkg] comes up (window event + onStart). */
    fun blockAppears(pkg: String) {
        window(OWN, WindowKind.OWN_BLOCK_UI, className = "com.chillpill.AppBlockActivity")
        val id = ++instanceSeq
        instances[pkg] = id
        send(Input.BlockStarted(pkg, id, now))
    }

    fun blockStops(pkg: String) = send(Input.BlockStopped(pkg, instances.getValue(pkg), now))
    fun blockClosed(pkg: String, reason: CloseReason, instance: Long = instances.getValue(pkg)) =
        send(Input.BlockClosed(pkg, reason, instance, now))

    fun tapContinue(pkg: String) = send(Input.ContinueTapped(pkg, now))
    fun extend(pkg: String) = send(Input.ExtendTapped(pkg, now))
    fun dismissWarning(pkg: String) = send(Input.WarningDismissed(pkg, now))

    /** Open [pkg], get blocked, wait, Continue, land back in the app. */
    fun enterWithContinue(pkg: String) {
        app(pkg)
        blockAppears(pkg)
        advance(1_000)
        tapContinue(pkg)
        blockStops(pkg)
        app(pkg)
        blockClosed(pkg, CloseReason.SYSTEM_GESTURE) // onUserLeaveHint after Continue: ignored
    }

    fun state(pkg: String) = core.appState(pkg)
    fun session(pkg: String) = core.appState(pkg) as? AppState.Session
    fun warning() = core.warning(now)

    fun clearEffects() = effects.clear()
    fun blocksShown(pkg: String? = null) =
        effects.filterIsInstance<Effect.ShowBlock>().filter { pkg == null || it.pkg == pkg }
    fun usage(kind: UsageKind, pkg: String? = null) =
        effects.filterIsInstance<Effect.RecordUsage>().count { it.kind == kind && (pkg == null || it.pkg == pkg) }
}
