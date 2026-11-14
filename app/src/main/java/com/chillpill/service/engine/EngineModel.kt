package com.chillpill.service.engine

/*
 * Data model of the session engine (specs/session-engine.md). Pure Kotlin: nothing in this
 * package except SessionEngine and ProcessExitLogger may import Android classes, so the whole
 * decision logic runs in JVM unit tests.
 *
 * All times are SystemClock.elapsedRealtime() milliseconds of when the thing *happened*.
 */

/** What a TYPE_WINDOW_STATE_CHANGED event (or a UsageStats probe) is about. See [WindowClassifier]. */
enum class WindowKind {
    /** An activity of any other app: a real foreground change. */
    APP,
    /**
     * A window of another app that is not one of its activities (dialog, bottom sheet, popup,
     * toast). It floats over the app in front without pausing it, so it is never a foreground
     * change.
     */
    APP_OVERLAY,
    /** Chillpill's own main UI (MainActivity, settings trampoline): a real destination. */
    OWN_MAIN_UI,
    /** Chillpill's block / re-intervention screen. */
    OWN_BLOCK_UI,
    /** Chillpill's overlay windows (warning pill, suggestion popup): never a foreground change. */
    OWN_OVERLAY,
    /** SystemUI (shade, volume, keyguard) and keyboard windows: never a foreground change. */
    SYSTEM_OVERLAY
}

enum class BlockKind {
    INITIAL,
    RE_INTERVENTION,
    /** Opened from a link (L1): no wait, only "how long?" up to the rule's linkPassMs. */
    LINK
}

/** Why a block screen closed without the user tapping Continue. */
enum class CloseReason { GO_HOME, BACK, SYSTEM_GESTURE }

enum class SuggestionAnswer { RESTRICT, IGNORE, NEVER }

/** Usage-statistics events the engine asks the host to record (mapped to `UsageEventType`). */
enum class UsageKind {
    OPEN_ATTEMPT,
    WAIT_COMPLETED,
    LEFT_APP,
    GRACE_EXPIRED_WHILE_ACTIVE,
    GRACE_EXPIRED_WHILE_AWAY,
    GRACE_EXTENDED,
    /** Continue on a link picker: let in without a wait (specs/link-pass.md, A2b). */
    LINK_PASS
}

/** How Chillpill steps in for a restricted app (specs/per-app-modes.md). */
enum class Mode {
    /** Wait, then use freely: grace is a free re-entry window and never ends in a block (G1, E7). */
    PAUSE_ONLY,
    /** Wait, then a fixed amount of time; re-intervention when it runs out in the app (G2). */
    TIME_LIMIT,
    /** Wait, then the user picks the visit length ([Input.ContinueTapped.chosenMs]); expires like TIME_LIMIT. */
    PICK
}

/** What the engine needs to know about one restricted app. Wait times are UI-only and not in here. */
data class AppRule(
    val mode: Mode,
    /** PAUSE_ONLY: free re-entry window. TIME_LIMIT: time allowed. PICK: unused. */
    val graceMs: Long,
    /** Whether the first warning pill offers "+10 s" (TIME_LIMIT / PICK). */
    val extensionAllowed: Boolean = true,
    /**
     * Opened from another app (a link, a share): skip the wait and let the user pick a visit of
     * up to this long, then the normal block (specs/link-pass.md). Null = off.
     */
    val linkPassMs: Long? = null
)

data class Config(
    /** Effective rule per restricted package (overrides already resolved against the defaults). */
    val rules: Map<String, AppRule>,
    /** Rule of an app restricted from the suggestion popup (A8): new apps follow the defaults. */
    val defaultRule: AppRule
) {
    val restricted: Set<String> get() = rules.keys

    companion object {
        /**
         * The pre-per-app-modes shape: one grace for all apps, [noReblock] apps are PAUSE_ONLY and
         * the rest TIME_LIMIT. Used for old traces and by tests.
         */
        fun legacy(restricted: Set<String>, noReblock: Set<String>, graceMs: Long): Config = Config(
            rules = restricted.associateWith {
                AppRule(if (it in noReblock) Mode.PAUSE_ONLY else Mode.TIME_LIMIT, graceMs)
            },
            defaultRule = AppRule(Mode.TIME_LIMIT, graceMs)
        )
    }
}

/** The engine's single belief about where the user is. */
data class Presence(
    /** Package in front; null while the screen is off / locked or before anything is known. */
    val pkg: String?,
    /**
     * [WindowKind.APP], [WindowKind.OWN_MAIN_UI], [WindowKind.OWN_BLOCK_UI], or
     * [WindowKind.SYSTEM_OVERLAY] when the shade / a keyboard was opened over our block screen;
     * null with [pkg].
     */
    val kind: WindowKind?,
    /** When the current value was established (event time). Older signals never override it. */
    val since: Long,
    val screenOn: Boolean,
    /** [pkg] is a home app (launcher). An entry from a home app is never a link (L1). */
    val home: Boolean = false
)

/** Per restricted package. No entry in the map means Idle. */
sealed interface AppState {
    /** A block or re-intervention screen for the package is (being) shown. */
    data class Blocking(
        val kind: BlockKind,
        val shownAt: Long,
        /** Between the activity's onStart and onStop. */
        val uiVisible: Boolean,
        /** Identity of the activity instance currently showing it; null until it reports in. */
        val instance: Long?,
        /** [BlockKind.LINK] only: longest visit the picker allows (L1, already capped for a repeat). */
        val linkMaxMs: Long? = null,
        /** [BlockKind.LINK] only: capped because a link visit started < 15 min ago (L3). */
        val linkLimited: Boolean = false
    ) : AppState

    /** The user passed the block and has not been blocked since. */
    data class Session(
        val graceDeadline: Long,
        /**
         * The app's rule when the session started. Settings changes apply from the next session,
         * so a running session never changes mode under the user.
         */
        val rule: AppRule,
        val extensionsUsed: Int = 0,
        val firstWarningDismissed: Boolean = false,
        /** PAUSE_ONLY only: grace ran out, the user may stay (and return within 10 s). */
        val graceOver: Boolean = false,
        /** When the user left the app; null exactly while the user is in it (invariant I2). */
        val leftAt: Long? = null,
        /** When the last "+10 s" was accepted (drives the pill's short confirmation). */
        val extendedAt: Long? = null,
        /**
         * A link pass (L1): let in without a wait; when it runs out in the app the normal block
         * follows (L2), whatever the mode. Never treated as PAUSE_ONLY.
         */
        val linkPass: Boolean = false
    ) : AppState
}

/**
 * Result of a UsageStats foreground lookup, already classified and converted to elapsed time:
 * [pkg] has the most recently resumed activity that is still resumed, since [resumedAt].
 *
 * [queriedAt] and [onlyResumed] must stay primitives with these defaults: traces recorded before
 * they existed decode to 0 / false, which switches rule P6b off and replays them unchanged.
 */
data class ProbeResult(
    val pkg: String,
    val kind: WindowKind,
    val resumedAt: Long,
    /** Just before the lookup ran; what it says is true as of this moment. */
    val queriedAt: Long = 0L,
    /** No other package has a resumed activity (never true in split screen). */
    val onlyResumed: Boolean = false,
    /** [pkg] is a home app (same default rule as [queriedAt] for old traces). */
    val home: Boolean = false
)

sealed interface Input {
    val at: Long

    /** [home]: [pkg] is a home app (launcher); false in traces recorded before link passes. */
    data class Window(
        val pkg: String,
        val className: String?,
        val kind: WindowKind,
        override val at: Long,
        val home: Boolean = false
    ) : Input
    data class ScreenOff(override val at: Long) : Input
    data class ScreenOn(val keyguardLocked: Boolean, override val at: Long) : Input
    data class UserPresent(override val at: Long) : Input
    data class Probe(val result: ProbeResult?, val token: Long, override val at: Long) : Input

    data class BlockStarted(val pkg: String, val instance: Long, override val at: Long) : Input
    data class BlockStopped(val pkg: String, val instance: Long, override val at: Long) : Input
    data class BlockClosed(val pkg: String, val reason: CloseReason, val instance: Long, override val at: Long) : Input
    /**
     * Continue on a block screen. [chosenMs] is the visit length picked on a PICK app's picker;
     * ignored for other modes. Null in traces recorded before per-app modes.
     */
    data class ContinueTapped(val pkg: String, override val at: Long, val chosenMs: Long? = null) : Input

    data class ExtendTapped(val pkg: String, override val at: Long) : Input
    data class WarningDismissed(val pkg: String, override val at: Long) : Input

    data class SuggestionEligible(val pkg: String, val appName: String, override val at: Long) : Input
    data class SuggestionAnswered(val pkg: String, val answer: SuggestionAnswer, override val at: Long) : Input

    data class ConfigChanged(val config: Config, override val at: Long) : Input
    data class Tick(override val at: Long) : Input
    /** Trace marker: why the previous process ended (Android 11+). No effect on state. */
    data class ProcessStarted(val previousExit: String, override val at: Long) : Input
}

sealed interface Effect {
    /** [linkMaxMs] / [linkLimited]: what the link picker offers ([BlockKind.LINK] only). */
    data class ShowBlock(
        val pkg: String,
        val kind: BlockKind,
        val linkMaxMs: Long? = null,
        val linkLimited: Boolean = false
    ) : Effect
    data class RecordUsage(val pkg: String, val kind: UsageKind) : Effect
    /** Run a UsageStats foreground lookup and send back [Input.Probe] with [token]. */
    data class RequestProbe(val token: Long) : Effect
    /** Decide (with I/O) whether to offer "restrict this app?"; send back [Input.SuggestionEligible]. */
    data class EvaluateSuggestion(val pkg: String) : Effect
    /** Persist the answer; for RESTRICT this also adds the app to the restricted set. */
    data class PersistSuggestionAnswer(val pkg: String, val answer: SuggestionAnswer) : Effect
}

data class WarningUi(
    val pkg: String,
    /** Elapsed-realtime millis at which grace runs out. */
    val deadline: Long,
    val canExtend: Boolean,
    val dismissible: Boolean,
    /** Show "10 s added" instead of the countdown: an extension was just accepted. */
    val extensionConfirmed: Boolean = false
)

data class SuggestionUi(val pkg: String, val appName: String)
