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
    /** Any other app: a real foreground change. */
    APP,
    /** Chillpill's own main UI (MainActivity, settings trampoline): a real destination. */
    OWN_MAIN_UI,
    /** Chillpill's block / re-intervention screen. */
    OWN_BLOCK_UI,
    /** Chillpill's overlay windows (warning pill, suggestion popup): never a foreground change. */
    OWN_OVERLAY,
    /** SystemUI (shade, volume, keyguard) and keyboard windows: never a foreground change. */
    SYSTEM_OVERLAY
}

enum class BlockKind { INITIAL, RE_INTERVENTION }

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
    GRACE_EXTENDED
}

data class Config(
    val restricted: Set<String>,
    /** Restricted apps with "Block again after grace" OFF. */
    val noReblock: Set<String>,
    val graceMs: Long
)

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
    val screenOn: Boolean
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
        val instance: Long?
    ) : AppState

    /** The user passed the block and has not been blocked since. */
    data class Session(
        val graceDeadline: Long,
        val extensionsUsed: Int = 0,
        val firstWarningDismissed: Boolean = false,
        /** No-re-block apps only: grace ran out, the user may stay (and return within 10 s). */
        val graceOver: Boolean = false,
        /** When the user left the app; null exactly while the user is in it (invariant I2). */
        val leftAt: Long? = null,
        /** When the last "+10 s" was accepted (drives the pill's short confirmation). */
        val extendedAt: Long? = null
    ) : AppState
}

/** Result of a UsageStats foreground lookup, already classified and converted to elapsed time. */
data class ProbeResult(val pkg: String, val kind: WindowKind, val resumedAt: Long)

sealed interface Input {
    val at: Long

    data class Window(val pkg: String, val className: String?, val kind: WindowKind, override val at: Long) : Input
    data class ScreenOff(override val at: Long) : Input
    data class ScreenOn(val keyguardLocked: Boolean, override val at: Long) : Input
    data class UserPresent(override val at: Long) : Input
    data class Probe(val result: ProbeResult?, val token: Long, override val at: Long) : Input

    data class BlockStarted(val pkg: String, val instance: Long, override val at: Long) : Input
    data class BlockStopped(val pkg: String, val instance: Long, override val at: Long) : Input
    data class BlockClosed(val pkg: String, val reason: CloseReason, val instance: Long, override val at: Long) : Input
    data class ContinueTapped(val pkg: String, override val at: Long) : Input

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
    data class ShowBlock(val pkg: String, val kind: BlockKind) : Effect
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
