package com.chillpill.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.chillpill.AppBlockActivity
import com.chillpill.ChillpillApp
import com.chillpill.ChillpillSettingsActivity
import com.chillpill.MainActivity
import com.chillpill.ReInterventionActivity
import com.chillpill.data.suggestion.ExcludedApps
import com.chillpill.data.usage.UsageEventType
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChillpillAccessibilityService : AccessibilityService() {

    private val app: ChillpillApp
        get() = applicationContext as ChillpillApp

    /** Single-thread scope so only one processEvent runs at a time; all access to previousForegroundPackage is on this thread. */
    private val eventProcessorScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default.limitedParallelism(1) +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "Uncaught exception in eventProcessorScope (accessibility pipeline)", t)
            }
    )

    /** Previous foreground package; used to detect the user leaving a restricted app. Updated at end of each event. Only accessed from eventProcessorScope. */
    private var previousForegroundPackage: String? = null

    /**
     * Bumped by the screen-off receiver. limitedParallelism(1) serialises *execution*, not
     * coroutines: while [processEvent] is suspended on a DataStore read the receiver's coroutine
     * can run and reset [previousForegroundPackage]; a stale [processEvent] must then not write
     * it back. Only accessed from eventProcessorScope.
     */
    private var screenOffGeneration = 0L

    /** Cached packages of enabled keyboards (see [isOverlayWindow]). Only accessed from eventProcessorScope. */
    private var imePackages: Set<String> = emptySet()
    private var imePackagesLoadedAtElapsed = 0L

    /**
     * The app that was in front when the screen went off. After the screen comes back the user is
     * almost always still in it, but the service cannot rely on hearing about that: many devices
     * emit no window event for the app that survives a screen-off/on, and [ForegroundPackageQuery]
     * lags the app's RESUME. Used as the last resort of [reevaluateAfterScreenOn]. Only accessed
     * from eventProcessorScope.
     */
    private var screenOffPackage: String? = null

    /**
     * Screen off counts as leaving the current app. Lock-screen windows belong to SystemUI, which
     * [isOverlayWindow] ignores, so without this a locked phone would look like "still in the app".
     * Resetting previousForegroundPackage makes the first event after the screen comes back
     * re-evaluate entry.
     *
     * Because some devices emit no window event for the app that was open when the screen went
     * off, the return is additionally re-evaluated by [reevaluateAfterScreenOn] — on
     * ACTION_USER_PRESENT when there was a keyguard to dismiss, and on ACTION_SCREEN_ON when there
     * was none (lock-after-timeout delay, quick power-button tap, no lock screen), a case in which
     * USER_PRESENT never fires. Without that, previousForegroundPackage stays null while the user
     * is inside the app and its next window event (a dialog, the shade collapsing) is judged
     * against the screen-off timestamp — a spurious block after what looked like a short pause.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> eventProcessorScope.launch {
                    Log.d(TAG, "screen off: treating as leaving pkg=$previousForegroundPackage")
                    screenOffGeneration++
                    previousForegroundPackage?.let {
                        BlockingSharedState.sessions.onLeft(it)
                        screenOffPackage = it
                    }
                    previousForegroundPackage = null
                    BlockingSharedState.setCurrentForegroundPackage(null)
                    graceWarningOverlay.dismiss()
                }
                Intent.ACTION_SCREEN_ON -> {
                    val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    val locked = keyguard?.isKeyguardLocked ?: true
                    if (locked) {
                        // USER_PRESENT will follow once the user gets past the keyguard.
                        Log.d(TAG, "screen on: keyguard locked, waiting for user present")
                    } else {
                        reevaluateAfterScreenOn("screen on without keyguard")
                    }
                }
                Intent.ACTION_USER_PRESENT -> reevaluateAfterScreenOn("user present")
            }
        }
    }

    /**
     * Re-evaluates entry into whatever is in front after the screen came back, unless a window
     * event already did (previousForegroundPackage != null). The UsageStats answer is retried a few
     * times because the app's RESUME is written slightly after the broadcast; a still-null answer
     * falls back to [screenOffPackage]. Any window event or a new screen-off during the wait aborts.
     */
    private fun reevaluateAfterScreenOn(reason: String) {
        eventProcessorScope.launch {
            val generation = screenOffGeneration
            var foreground: String? = null
            var attempt = 0
            while (true) {
                if (previousForegroundPackage != null || generation != screenOffGeneration) return@launch
                foreground = withContext(Dispatchers.IO) {
                    try {
                        ForegroundPackageQuery.query(applicationContext)
                    } catch (t: Throwable) {
                        Log.e(TAG, "$reason: foreground query failed", t)
                        null
                    }
                }
                if (foreground != null || ++attempt >= FOREGROUND_QUERY_ATTEMPTS) break
                delay(FOREGROUND_QUERY_RETRY_MS)
            }
            if (previousForegroundPackage != null || generation != screenOffGeneration) return@launch
            val queried = foreground
            val pkg = queried ?: screenOffPackage ?: return@launch
            if (pkg == applicationContext.packageName || isOverlayWindow(pkg, null)) return@launch
            Log.d(
                TAG,
                "$reason: no window event since screen on, re-evaluating pkg=$pkg " +
                    "(source=${if (queried != null) "usagestats" else "screen-off package"})"
            )
            processEvent(pkg)
        }
    }
    private var screenReceiverRegistered = false

    private val overlayManager: SuggestionOverlayManager by lazy { SuggestionOverlayManager(this) }

    /** Owns the grace-expiry warning pill. Deliberately separate from [overlayManager] (see I2 in the spec). */
    private val graceWarningOverlay: GraceWarningOverlayManager by lazy { GraceWarningOverlayManager(this) }

    /**
     * Window add/remove has to happen on the main thread, and the pill must not be serialised behind
     * the (possibly suspended) event pipeline, so it gets its own main-dispatcher scope.
     */
    private val graceWarningScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "Uncaught exception in graceWarningScope (warning pill)", t)
            }
    )
    private var graceWarningJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = null // receive events for all packages; we filter by restricted set
        }
        serviceInfo = info
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        }
        startCollectingGraceWarnings()
    }

    /**
     * [GracePeriodService] decides *when* a grace-expiry warning is due; this service decides
     * whether it is visible (only over the app it belongs to) and owns the window, because only an
     * accessibility service may add a `TYPE_ACCESSIBILITY_OVERLAY`.
     */
    private fun startCollectingGraceWarnings() {
        if (graceWarningJob?.isActive == true) return
        graceWarningJob = graceWarningScope.launch {
            BlockingSharedState.graceWarning.collect { warning ->
                Log.d(TAG, "graceWarning: $warning foreground=${BlockingSharedState.currentForegroundPackage}")
                if (warning != null && warning.packageName == BlockingSharedState.currentForegroundPackage) {
                    graceWarningOverlay.applyWarning(warning, ::onExtendGrace)
                } else {
                    graceWarningOverlay.applyWarning(null, ::onExtendGrace)
                }
            }
        }
    }

    /**
     * Brings the pill in line with a foreground change: visible only over the app whose grace is
     * running out, gone everywhere else (including when the user returns to that app mid-warning).
     */
    private suspend fun syncGraceWarning(pkg: String?) {
        val warning = BlockingSharedState.graceWarning.value
        if (warning != null && warning.packageName == pkg) {
            graceWarningOverlay.applyWarning(warning, ::onExtendGrace)
        } else {
            graceWarningOverlay.dismissIfNot(pkg)
        }
    }

    /** "+10 s": moves the grace deadline and nothing else (I1). */
    private fun onExtendGrace(packageName: String) {
        graceWarningScope.launch {
            val extended = BlockingSharedState.extendGrace(packageName)
            Log.d(TAG, "onExtendGrace: pkg=$packageName extended=$extended")
            if (!extended) return@launch
            try {
                withContext(Dispatchers.IO) {
                    app.usageEventsRepository.recordEvent(packageName, UsageEventType.GRACE_EXTENDED)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "onExtendGrace: recordEvent failed pkg=$packageName", t)
            }
        }
    }

    override fun onDestroy() {
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (t: Throwable) {
                Log.e(TAG, "unregister screenReceiver failed", t)
            }
            screenReceiverRegistered = false
        }
        graceWarningJob = null
        graceWarningOverlay.dismissFromMainThread()
        graceWarningScope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString()
        Log.d(TAG, "onAccessibilityEvent: foreground pkg=$pkg class=$className")
        if (pkg == applicationContext.packageName) {
            // Only our *activities* are a foreground change. Our overlay windows (the grace warning
            // pill, the suggestion popup) sit on top of the app the user is in and leave it in
            // front, so they must not set currentForegroundPackage: doing so makes the warning
            // pill's own gate ("show only over the app the warning belongs to") fail for the rest
            // of the grace window, and would let the grace-expiry fallback treat the user as
            // "away". Matching the pill's own class name is not enough — an overlay's window event
            // does not necessarily carry the root view's class — so the rule is inverted: anything
            // that is not a known activity of ours is an overlay (I3 in specs/grace-expiry-warning.md).
            if (className !in OWN_ACTIVITY_CLASSES) {
                Log.d(TAG, "onAccessibilityEvent: own overlay window class=$className, not a foreground change")
                return
            }
            BlockingSharedState.setCurrentForegroundPackage(pkg)
            // Block / re-intervention screens must not touch previousForegroundPackage (see same-app
            // guard below). Chillpill's own main UI (notification tap, launcher) is a real
            // destination though: going there *is* leaving the restricted app.
            if (className in OWN_MAIN_UI_CLASSES) {
                eventProcessorScope.launch {
                    val prev = previousForegroundPackage
                    if (prev != null && prev != pkg) BlockingSharedState.sessions.onLeft(prev)
                    previousForegroundPackage = pkg
                }
            }
            return
        }
        eventProcessorScope.launch {
            // Same-app check and processEvent run on single thread so previousForegroundPackage has no race.
            // This also absorbs the second window event a restricted app fires (splash -> main)
            // while our block screen is already up.
            if (pkg == previousForegroundPackage) {
                Log.d(TAG, "onAccessibilityEvent: same app pkg=$pkg, skipping")
                BlockingSharedState.setCurrentForegroundPackage(pkg)
                return@launch
            }
            // Notification shade, volume/power dialogs and keyboards sit on top of the current app;
            // while the user is legitimately inside it they are not "leaving" it and must not
            // disturb previousForegroundPackage. Without a live session (block screen up, or a
            // non-restricted app) the overlay behaves like any other app, so that e.g. block -> shade
            // -> tap X notification re-evaluates X instead of hitting the same-app guard.
            if (isOverlayWindow(pkg, className)) {
                val prev = previousForegroundPackage
                if (prev == null || BlockingSharedState.sessions.hasActiveSession(prev)) {
                    Log.d(TAG, "onAccessibilityEvent: overlay pkg=$pkg over prev=$prev, ignoring")
                    return@launch
                }
                Log.d(TAG, "onAccessibilityEvent: overlay pkg=$pkg, prev=$prev has no session; counting as foreground")
                previousForegroundPackage = pkg
                BlockingSharedState.setCurrentForegroundPackage(pkg)
                syncGraceWarning(pkg)
                return@launch
            }
            processEvent(pkg)
        }
    }

    private suspend fun processEvent(pkg: String) {
        val generation = screenOffGeneration
        try {
            val restrictedApps = withContext(Dispatchers.IO) {
                app.restrictedAppsRepository.snapshot.first()
            }
            if (generation != screenOffGeneration) {
                Log.d(TAG, "processEvent: screen went off while reading settings, dropping pkg=$pkg")
                return
            }
            val restrictedPackages = restrictedApps.restricted

            Log.d(TAG, "processEvent: pkg=$pkg restrictedCount=${restrictedPackages.size} restricted=$restrictedPackages")

            recordLeavingRestrictedApp(restrictedPackages, pkg)
            // GracePeriodService reads this when the timer expires to decide re-intervene vs end the session
            BlockingSharedState.setCurrentForegroundPackage(pkg)
            // A real foreground change: the pill belongs to the app the user just left. Done here
            // rather than at the end of this function so every early return is covered.
            syncGraceWarning(pkg)

            // While our suggestion overlay is visible, ignore accessibility events to avoid
            // displacing the overlay or launching competing UI.
            if (overlayManager.isShowing) {
                Log.d(TAG, "processEvent: suggestion overlay showing; skipping pkg=$pkg")
                return
            }

            if (pkg !in restrictedPackages) {
                handleNonRestrictedApp(pkg, generation)
                return
            }

            val decision = BlockingSharedState.sessions.onEnterFromElsewhere(
                pkg,
                reInterventionDisabled = pkg in restrictedApps.reInterventionDisabled
            )
            if (decision == SessionPolicy.Decision.ALLOW) {
                Log.d(TAG, "processEvent: pkg=$pkg in grace period or short-return window, allowing")
                setPreviousForeground(pkg, generation)
                return
            }
            Log.d(TAG, "processEvent: showing block for pkg=$pkg (entering from elsewhere, no valid session)")
            handleEnteringRestrictedAppFromElsewhere(pkg)

            setPreviousForeground(pkg, generation)
        } catch (t: Throwable) {
            Log.e(TAG, "processEvent failed for pkg=$pkg", t)
        }
    }

    /**
     * Notes the moment the user left a restricted app. This never grants or extends grace; it only
     * feeds the short-return window of apps with re-intervention disabled, and only while that app
     * has a live session. Showing a block ends the session, so leaving from a block screen (Home,
     * back, recents) is a no-op regardless of the order in which window events arrived.
     */
    private fun recordLeavingRestrictedApp(restrictedPackages: Set<String>, newPackage: String) {
        previousForegroundPackage?.let { prev ->
            if (prev in restrictedPackages && prev != newPackage) {
                BlockingSharedState.sessions.onLeft(prev)
            }
        }
    }

    /**
     * SystemUI (shade, volume, power menu, keyguard) and keyboard windows. Apps that ship a keyboard
     * (SwiftKey, Grammarly, Samsung Keyboard, ...) also have real activities, so for IME packages only
     * a window whose class is *not* one of the package's activities counts as an overlay.
     * [className] null means "unknown window" (UsageStats lookup). Only call from eventProcessorScope.
     */
    private fun isOverlayWindow(pkg: String, className: String?): Boolean {
        if (pkg == SYSTEM_UI_PACKAGE) return true
        if (pkg !in enabledImePackages()) return false
        return !isActivityOf(pkg, className)
    }

    private fun enabledImePackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (imePackagesLoadedAtElapsed == 0L || now - imePackagesLoadedAtElapsed > IME_CACHE_MS) {
            imePackages = try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.enabledInputMethodList?.mapNotNull { it.packageName }?.toSet() ?: emptySet()
            } catch (t: Throwable) {
                Log.e(TAG, "enabledImePackages: reading enabled input methods failed", t)
                emptySet()
            }
            imePackagesLoadedAtElapsed = now
        }
        return imePackages
    }

    private fun isActivityOf(pkg: String, className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        return try {
            packageManager.getActivityInfo(ComponentName(pkg, className), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun handleEnteringRestrictedAppFromElsewhere(pkg: String) {
        try {
            withContext(Dispatchers.IO) {
                app.usageEventsRepository.recordEvent(pkg, UsageEventType.OPEN_ATTEMPT)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleEnteringRestrictedAppFromElsewhere: recordEvent failed pkg=$pkg", t)
        }
        startBlockActivity(pkg, isReIntervention = false)
    }

    /**
     * Updates local state for next event, unless the screen went off while this event was being
     * processed (the receiver's reset must win). Shared current-foreground is already set at start of processEvent.
     */
    private fun setPreviousForeground(pkg: String, generation: Long) {
        if (generation != screenOffGeneration) {
            Log.d(TAG, "setPreviousForeground: screen went off meanwhile, not recording pkg=$pkg")
            return
        }
        previousForegroundPackage = pkg
    }

    private suspend fun handleNonRestrictedApp(pkg: String, generation: Long) {
        Log.d(TAG, "processEvent: pkg not restricted, considering suggestion flow")

        // Never suggest for hardcoded excluded packages.
        if (pkg in ExcludedApps.EXCLUDED_PACKAGES) {
            Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg is in hardcoded exclusion list, allowing without suggestion")
            setPreviousForeground(pkg, generation)
            return
        }

        // Only suggest for apps that have a launcher activity (same filter as settings app selection).
        val hasLauncherActivity = applicationContext.packageManager.getLaunchIntentForPackage(pkg) != null
        if (!hasLauncherActivity) {
            Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg has no launcher activity, skipping suggestion")
            setPreviousForeground(pkg, generation)
            return
        }

        if (app.appOpenTracker.wasSuggestionShown(pkg)) {
            setPreviousForeground(pkg, generation)
            return
        }
        val foregroundMs = try {
            withContext(Dispatchers.IO) {
                queryForegroundTimeMs(pkg, SUGGESTION_WINDOW_MS)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleNonRestrictedApp: queryForegroundTimeMs failed for pkg=$pkg", t)
            setPreviousForeground(pkg, generation)
            return
        }
        val foregroundMinutes = foregroundMs / 60_000L
        Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg foregroundMinutes=$foregroundMinutes in last 12h")
        if (foregroundMinutes < SUGGESTION_THRESHOLD_MINUTES) {
            setPreviousForeground(pkg, generation)
            return
        }

        val isIgnored: Boolean
        val isPermanentlyExcluded: Boolean
        try {
            isIgnored = withContext(Dispatchers.IO) {
                app.suggestionRepository.isIgnored(pkg)
            }
            isPermanentlyExcluded = withContext(Dispatchers.IO) {
                app.suggestionRepository.isPermanentlyExcluded(pkg)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleNonRestrictedApp: suggestionRepository read failed pkg=$pkg", t)
            setPreviousForeground(pkg, generation)
            return
        }
        Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg isIgnored=$isIgnored isPermanentlyExcluded=$isPermanentlyExcluded")
        if (isIgnored || isPermanentlyExcluded) {
            setPreviousForeground(pkg, generation)
            return
        }

        app.appOpenTracker.markSuggestionShown(pkg)

        val appName = resolveAppName(pkg)
        Log.d(TAG, "handleNonRestrictedApp: calling overlayManager.show pkg=$pkg appName=$appName")
        try {
            overlayManager.show(
                packageName = pkg,
                appName = appName,
                onRestrict = {
                    eventProcessorScope.launch {
                        try {
                            app.restrictedAppsRepository.addRestricted(pkg)
                            app.suggestionRepository.removeIgnored(pkg)
                            app.appOpenTracker.clearSuggestionShown(pkg)

                            // Read grace at tap time so a settings change made while the popup was up is honoured.
                            val graceMs = app.settingsRepository.settings.first().gracePeriodMinutes * 60L * 1000L
                            BlockingSharedState.sessions.startSession(pkg, graceMs)
                            startGracePeriodService(pkg)
                        } catch (t: Throwable) {
                            Log.e(TAG, "suggestion onRestrict failed pkg=$pkg", t)
                        } finally {
                            try {
                                overlayManager.dismiss()
                            } catch (t: Throwable) {
                                Log.e(TAG, "suggestion onRestrict: overlay dismiss failed pkg=$pkg", t)
                            }
                        }
                    }
                },
                onIgnore = {
                    eventProcessorScope.launch {
                        try {
                            app.suggestionRepository.markIgnored(pkg)
                            app.appOpenTracker.clearSuggestionShown(pkg)
                        } catch (t: Throwable) {
                            Log.e(TAG, "suggestion onIgnore failed pkg=$pkg", t)
                        } finally {
                            try {
                                overlayManager.dismiss()
                            } catch (t: Throwable) {
                                Log.e(TAG, "suggestion onIgnore: overlay dismiss failed pkg=$pkg", t)
                            }
                        }
                    }
                },
                onNeverAskAgain = {
                    eventProcessorScope.launch {
                        try {
                            app.suggestionRepository.addPermanentlyExcluded(pkg)
                            app.appOpenTracker.clearSuggestionShown(pkg)
                        } catch (t: Throwable) {
                            Log.e(TAG, "suggestion onNeverAskAgain failed pkg=$pkg", t)
                        } finally {
                            try {
                                overlayManager.dismiss()
                            } catch (t: Throwable) {
                                Log.e(TAG, "suggestion onNeverAskAgain: overlay dismiss failed pkg=$pkg", t)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "handleNonRestrictedApp: overlayManager.show failed or threw before attach pkg=$pkg appName=$appName",
                t
            )
            app.appOpenTracker.clearSuggestionShown(pkg)
        }
        setPreviousForeground(pkg, generation)
    }

    private fun queryForegroundTimeMs(pkg: String, windowMs: Long): Long {
        val usm = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return 0L
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - windowMs, now)
        val event = UsageEvents.Event()
        var totalMs = 0L
        var lastResumed = -1L
        val resumeType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_RESUMED
        else UsageEvents.Event.MOVE_TO_FOREGROUND
        val pauseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_PAUSED
        else UsageEvents.Event.MOVE_TO_BACKGROUND
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != pkg) continue
            when (event.eventType) {
                resumeType -> lastResumed = event.timeStamp
                pauseType -> {
                    if (lastResumed > 0) {
                        totalMs += event.timeStamp - lastResumed
                        lastResumed = -1L
                    }
                }
            }
        }
        if (lastResumed > 0) totalMs += now - lastResumed
        return totalMs
    }

    private fun startBlockActivity(packageName: String, isReIntervention: Boolean) {
        try {
            val activityClass = if (isReIntervention) ReInterventionActivity::class.java else AppBlockActivity::class.java
            val intent = Intent(applicationContext, activityClass).apply {
                setPackage(applicationContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra(AppBlockActivity.EXTRA_PACKAGE_NAME, packageName)
                if (!isReIntervention) putExtra(AppBlockActivity.EXTRA_IS_RE_INTERVENTION, false)
            }
            Log.d(TAG, "startBlockActivity: launching packageName=$packageName isReIntervention=$isReIntervention")
            applicationContext.startActivity(intent)
            Log.d(TAG, "startBlockActivity: startActivity returned for packageName=$packageName (if block does not appear, check Android 10+ background start restrictions)")
        } catch (t: Throwable) {
            Log.e(TAG, "startBlockActivity failed for packageName=$packageName", t)
        }
    }

    private fun startGracePeriodService(packageName: String) {
        try {
            val serviceIntent = Intent(applicationContext, GracePeriodService::class.java).apply {
                action = GracePeriodService.ACTION_START
                putExtra(GracePeriodService.EXTRA_PACKAGE_NAME, packageName)
            }
            Log.d(TAG, "startGracePeriodService: starting grace for pkg=$packageName")
            applicationContext.startForegroundService(serviceIntent)
        } catch (t: Throwable) {
            // See AppBlockActivity: an unmonitored grace window must not outlive the failure.
            Log.e(TAG, "startGracePeriodService failed for pkg=$packageName; ending session", t)
            BlockingSharedState.sessions.endSession(packageName)
        }
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            packageName
        }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "ChillpillA11y"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        /** Our activities that are destinations in their own right, as opposed to block/overlay UI. */
        private val OWN_MAIN_UI_CLASSES = setOf(
            MainActivity::class.java.name,
            ChillpillSettingsActivity::class.java.name
        )

        /**
         * Every activity Chillpill has. An own-package window event from anything else is one of our
         * overlays (warning pill, suggestion popup) and is not a foreground change at all.
         */
        private val OWN_ACTIVITY_CLASSES = OWN_MAIN_UI_CLASSES + setOf(
            AppBlockActivity::class.java.name,
            ReInterventionActivity::class.java.name
        )
        private const val IME_CACHE_MS = 5L * 60L * 1000L
        /** UsageStats retries after the screen comes on (see [reevaluateAfterScreenOn]). */
        private const val FOREGROUND_QUERY_ATTEMPTS = 4
        private const val FOREGROUND_QUERY_RETRY_MS = 400L
        private const val SUGGESTION_WINDOW_MS = 12L * 60L * 60L * 1000L // 12 hours
        // Cumulative foreground time across the whole SUGGESTION_WINDOW_MS window, not "this sitting" —
        // an app already used for 30+ min earlier today will trigger the suggestion almost immediately
        // on a later, brief reopen (as soon as AppOpenTracker's per-process dedupe allows it again).
        private const val SUGGESTION_THRESHOLD_MINUTES = 30L
    }
}
