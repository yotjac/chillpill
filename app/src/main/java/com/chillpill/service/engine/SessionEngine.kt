package com.chillpill.service.engine

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.chillpill.AppBlockActivity
import com.chillpill.ChillpillApp
import com.chillpill.ChillpillSettingsActivity
import com.chillpill.MainActivity
import com.chillpill.ReInterventionActivity
import com.chillpill.data.restricted.RestrictedAppsSnapshot
import com.chillpill.data.settings.Settings
import com.chillpill.data.usage.UsageEventType
import com.chillpill.service.ForegroundPackageQuery
import com.chillpill.service.GraceNotificationService
import com.chillpill.service.SuggestionEvaluator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android host of [EngineCore] (specs/session-engine.md). Owns the only instance of the core and
 * feeds it from one channel on one thread, so every decision sees a consistent state and nothing
 * can interleave inside it.
 *
 * - Inputs: [send] (thread-safe, never blocks) from the accessibility service, the block
 *   activities and the overlays. Queued until the first config has been read, so no decision is
 *   ever made without config and no DataStore read happens inside one.
 * - Effects are executed here (activities, Room, UsageStats, repositories), off the engine thread.
 * - Outputs: [warning], [suggestion] and [graceActive] StateFlows, rendered by the accessibility
 *   service (overlays) and [GraceNotificationService].
 * - One wake-up timer, always set to [EngineCore.nextWakeUp].
 * - Every input and effect goes to the trace in `filesDir/trace/` (see [EngineTrace]).
 *
 * Singleton in [ChillpillApp]; started by the accessibility service.
 */
class SessionEngine(private val app: ChillpillApp) {

    private val core = EngineCore()
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)

    private val handler = CoroutineExceptionHandler { _, t -> Log.e(TAG, "uncaught exception in session engine", t) }

    /** The engine thread: [core] is only ever touched from here. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + handler)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    private val trace = EngineTrace(File(app.filesDir, "trace"))
    private val suggestionEvaluator = SuggestionEvaluator(app)

    private val _warning = MutableStateFlow<WarningUi?>(null)
    /** The grace-expiry pill to show, or null. */
    val warning: StateFlow<WarningUi?> = _warning.asStateFlow()

    private val _suggestion = MutableStateFlow<SuggestionUi?>(null)
    /** The "restrict this app?" popup to show, or null. */
    val suggestion: StateFlow<SuggestionUi?> = _suggestion.asStateFlow()

    private val _graceActive = MutableStateFlow(false)
    /** True while any grace window runs; drives the ongoing notification. */
    val graceActive: StateFlow<Boolean> = _graceActive.asStateFlow()

    private var timerJob: Job? = null
    private var timerAt: Long? = null

    private var imePackages: Set<String> = emptySet()
    private var imePackagesLoadedAt = 0L

    val classifier = WindowClassifier(
        ownPackage = app.packageName,
        ownMainUiClasses = setOf(MainActivity::class.java.name, ChillpillSettingsActivity::class.java.name),
        ownBlockUiClasses = setOf(AppBlockActivity::class.java.name, ReInterventionActivity::class.java.name),
        imePackages = ::enabledImePackages,
        isActivityOf = ::isActivityOf
    )

    /** Idempotent. Called from the accessibility service's onServiceConnected. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { run() }
        io.launch {
            while (isActive) {
                delay(TRACE_FLUSH_MS)
                trace.flush()
            }
        }
    }

    /** Thread-safe; inputs sent before [start] wait in the queue. */
    fun send(input: Input) {
        inputs.trySend(input)
    }

    private suspend fun run() {
        // Nothing can be decided without config; a DataStore failure must not kill the engine.
        var config: Config? = null
        while (config == null) {
            config = try {
                configFlow().first()
            } catch (t: Throwable) {
                Log.e(TAG, "reading config failed; retrying", t)
                delay(CONFIG_RETRY_MS)
                null
            }
        }
        process(Input.ConfigChanged(config, now()))
        ProcessExitLogger.previousExit(app)?.let {
            Log.i(TAG, "previous process exit: $it")
            process(Input.ProcessStarted(it, now()))
        }
        process(initialScreenState())
        scope.launch {
            // Re-emits the current config first; handling the same config twice is a no-op.
            configFlow()
                .retryWhen { cause, _ ->
                    Log.e(TAG, "config flow failed; resubscribing", cause)
                    delay(CONFIG_RETRY_MS)
                    true
                }
                .collect { send(Input.ConfigChanged(it, now())) }
        }
        for (input in inputs) process(input)
    }

    private fun configFlow(): Flow<Config> =
        combine(app.restrictedAppsRepository.snapshot, app.settingsRepository.settings) { r: RestrictedAppsSnapshot, s: Settings ->
            Config(r.restricted, r.reInterventionDisabled, s.gracePeriodMinutes * 60_000L)
        }.distinctUntilChanged()

    private fun initialScreenState(): Input {
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return if (power?.isInteractive != false) {
            Input.ScreenOn(keyguardLocked = keyguard?.isKeyguardLocked ?: false, at = now())
        } else {
            Input.ScreenOff(now())
        }
    }

    private fun process(input: Input) {
        val effects = try {
            core.handle(input)
        } catch (t: Throwable) {
            Log.e(TAG, "engine failed on $input", t)
            emptyList()
        }
        trace.append(TraceCodec.encodeInput(input, System.currentTimeMillis()))
        if (effects.isNotEmpty()) trace.append(TraceCodec.encodeEffects(input.at, effects))
        if (input !is Input.Tick && input !is Input.Probe || effects.isNotEmpty()) {
            Log.d(TAG, "$input -> $effects")
        }
        effects.forEach(::execute)
        if (effects.any { it is Effect.ShowBlock }) io.launch { trace.flush() }

        val now = now()
        core.invariantViolations(now).forEach { Log.e(TAG, "invariant violated: $it (after $input)") }
        _warning.value = core.warning(now)
        _suggestion.value = core.suggestion
        val graceActive = core.graceActive()
        // Started on every transition the engine itself sees, not from a (conflating) collector,
        // so a grace that ends and a new one that starts right after always get a start.
        if (graceActive && !_graceActive.value) startGraceNotification()
        _graceActive.value = graceActive
        reschedule(core.nextWakeUp(now))
    }

    private fun reschedule(at: Long?) {
        if (at == timerAt && timerJob?.isActive == true) return
        timerJob?.cancel()
        timerAt = at
        if (at == null) return
        timerJob = scope.launch {
            delay((at - now()).coerceAtLeast(0L))
            send(Input.Tick(now()))
        }
    }

    // ---- effects ----------------------------------------------------------------------------

    private fun execute(effect: Effect) {
        when (effect) {
            is Effect.ShowBlock -> showBlock(effect.pkg, effect.kind)
            is Effect.RecordUsage -> io.launch {
                val sessionStart = if (effect.kind == UsageKind.WAIT_COMPLETED) System.currentTimeMillis() else null
                app.usageEventsRepository.recordEvent(effect.pkg, effect.kind.eventType(), sessionStart)
            }
            is Effect.RequestProbe -> io.launch {
                val result = try {
                    ForegroundPackageQuery.query(app)?.let {
                        ProbeResult(it.packageName, classifier.classify(it.packageName, null), wallToElapsed(it.resumedAtWallMs))
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "foreground probe failed", t)
                    null
                }
                send(Input.Probe(result, effect.token, now()))
            }
            is Effect.EvaluateSuggestion -> io.launch {
                val appName = suggestionEvaluator.evaluate(effect.pkg)
                if (appName != null) send(Input.SuggestionEligible(effect.pkg, appName, now()))
            }
            is Effect.PersistSuggestionAnswer -> io.launch {
                suggestionEvaluator.persist(effect.pkg, effect.answer)
            }
        }
    }

    private fun showBlock(pkg: String, kind: BlockKind) {
        try {
            val activity = if (kind == BlockKind.RE_INTERVENTION) ReInterventionActivity::class.java else AppBlockActivity::class.java
            val intent = Intent(app, activity).apply {
                setPackage(app.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra(AppBlockActivity.EXTRA_PACKAGE_NAME, pkg)
                putExtra(AppBlockActivity.EXTRA_IS_RE_INTERVENTION, kind == BlockKind.RE_INTERVENTION)
            }
            app.startActivity(intent)
        } catch (t: Throwable) {
            // The core keeps the app Blocking with no visible screen; its next event re-shows it (E4).
            Log.e(TAG, "showBlock failed pkg=$pkg kind=$kind", t)
        }
    }

    private fun startGraceNotification() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, GraceNotificationService::class.java))
        } catch (t: Throwable) {
            // Cosmetic only (B3): grace is enforced by the engine, not by the service.
            Log.w(TAG, "could not start the grace notification", t)
        }
    }

    // ---- lookups for the classifier ---------------------------------------------------------

    @Synchronized
    private fun enabledImePackages(): Set<String> {
        val now = now()
        if (imePackagesLoadedAt == 0L || now - imePackagesLoadedAt > IME_CACHE_MS) {
            imePackages = try {
                val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.enabledInputMethodList?.mapNotNull { it.packageName }?.toSet() ?: emptySet()
            } catch (t: Throwable) {
                Log.e(TAG, "reading enabled input methods failed", t)
                emptySet()
            }
            imePackagesLoadedAt = now
        }
        return imePackages
    }

    /**
     * Whether [className] is an activity of [pkg]; null when the package is not visible to us
     * (package visibility, API 30+: launchable apps and enabled IMEs are, see `<queries>` in the
     * manifest). Every window event asks, so answers are cached; they only change on (un)install,
     * which the TTL covers.
     */
    private val activityLookups = HashMap<String, Boolean?>()
    private var activityLookupsClearedAt = 0L

    @Synchronized
    private fun isActivityOf(pkg: String, className: String): Boolean? {
        val now = now()
        if (now - activityLookupsClearedAt > LOOKUP_CACHE_MS) {
            activityLookups.clear()
            activityLookupsClearedAt = now
        }
        val key = "$pkg/$className"
        if (activityLookups.containsKey(key)) return activityLookups[key]
        val pm = app.packageManager
        val result: Boolean? = try {
            pm.getActivityInfo(ComponentName(pkg, className), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            try {
                pm.getPackageInfo(pkg, 0)
                false
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "activity lookup failed for $key", t)
            null
        }
        activityLookups[key] = result
        return result
    }

    companion object {
        private const val TAG = "SessionEngine"
        private const val TRACE_FLUSH_MS = 2_000L
        private const val IME_CACHE_MS = 5L * 60L * 1000L
        private const val LOOKUP_CACHE_MS = 5L * 60L * 1000L
        private const val CONFIG_RETRY_MS = 1_000L

        fun now(): Long = SystemClock.elapsedRealtime()

        /** AccessibilityEvent.eventTime is uptime-based; the engine works in elapsed realtime. */
        fun elapsedFromUptime(uptimeMs: Long): Long =
            SystemClock.elapsedRealtime() - (SystemClock.uptimeMillis() - uptimeMs)

        fun wallToElapsed(wallMs: Long): Long =
            SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wallMs)

        private fun UsageKind.eventType(): String = when (this) {
            UsageKind.OPEN_ATTEMPT -> UsageEventType.OPEN_ATTEMPT
            UsageKind.WAIT_COMPLETED -> UsageEventType.WAIT_COMPLETED
            UsageKind.LEFT_APP -> UsageEventType.LEFT_APP
            UsageKind.GRACE_EXPIRED_WHILE_ACTIVE -> UsageEventType.GRACE_EXPIRED_WHILE_ACTIVE
            UsageKind.GRACE_EXPIRED_WHILE_AWAY -> UsageEventType.GRACE_EXPIRED_WHILE_AWAY
            UsageKind.GRACE_EXTENDED -> UsageEventType.GRACE_EXTENDED
        }
    }
}
