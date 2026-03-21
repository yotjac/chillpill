package com.chillpill.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.chillpill.AppBlockActivity
import com.chillpill.ChillpillApp
import com.chillpill.ReInterventionActivity
import com.chillpill.data.suggestion.ExcludedApps
import com.chillpill.data.usage.UsageEventType
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Previous foreground package; used to detect "leaving" for grace extension. Updated at end of each event. Only accessed from eventProcessorScope. */
    private var previousForegroundPackage: String? = null

    private val overlayManager: SuggestionOverlayManager by lazy { SuggestionOverlayManager(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = null // receive events for all packages; we filter by restricted set
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        Log.d(TAG, "onAccessibilityEvent: foreground pkg=$pkg")
        if (pkg == applicationContext.packageName) {
            BlockingSharedState.setCurrentForegroundPackage(pkg)
            return
        }
        eventProcessorScope.launch {
            // Same-app check and processEvent run on single thread so previousForegroundPackage has no race
            if (pkg == previousForegroundPackage) {
                Log.d(TAG, "onAccessibilityEvent: same app pkg=$pkg, skipping")
                previousForegroundPackage = pkg
                BlockingSharedState.setCurrentForegroundPackage(pkg)
                return@launch
            }
            processEvent(pkg)
        }
    }

    private suspend fun processEvent(pkg: String) {
        try {
            val restrictedPackages = withContext(Dispatchers.IO) {
                app.restrictedAppsRepository.restrictedPackages.first()
            }
            val settings = withContext(Dispatchers.IO) {
                app.settingsRepository.settings.first()
            }
            val graceMs = settings.gracePeriodMinutes * 60L * 1000L

            Log.d(TAG, "processEvent: pkg=$pkg restrictedCount=${restrictedPackages.size} restricted=$restrictedPackages")

            recordLeavingRestrictedApp(restrictedPackages, pkg, graceMs)
            // GracePeriodService reads this when the timer expires to decide re-intervene vs set grace-expired flag
            BlockingSharedState.setCurrentForegroundPackage(pkg)

            // While our suggestion overlay is visible, ignore accessibility events to avoid
            // displacing the overlay or launching competing UI.
            if (overlayManager.isShowing) {
                Log.d(TAG, "processEvent: suggestion overlay showing; skipping pkg=$pkg")
                return
            }

            if (pkg !in restrictedPackages) {
                handleNonRestrictedApp(pkg, graceMs)
                return
            }

            tryHandleGraceExpiredReIntervention(pkg)
            if (BlockingSharedState.isInGracePeriod(pkg)) {
                Log.d(TAG, "processEvent: pkg=$pkg in grace period, allowing")
                setPreviousForeground(pkg)
                return
            }
            Log.d(TAG, "processEvent: showing block for pkg=$pkg (entering from elsewhere, grace expired)")
            handleEnteringRestrictedAppFromElsewhere(pkg)

            setPreviousForeground(pkg)
        } catch (t: Throwable) {
            Log.e(TAG, "processEvent failed for pkg=$pkg", t)
        }
    }

    private fun recordLeavingRestrictedApp(restrictedPackages: Set<String>, newPackage: String, graceMs: Long) {
        // Do not grant grace when switching to our block activity: user did not leave the app voluntarily
        if (newPackage == applicationContext.packageName) return
        if (BlockingSharedState.currentForegroundPackage == applicationContext.packageName) return
        previousForegroundPackage?.let { prev ->
            if (prev in restrictedPackages && prev != newPackage) {
                BlockingSharedState.setGraceValidUntil(prev, System.currentTimeMillis() + graceMs)
            }
        }
    }

    /** If grace expired while user was away, clear the flag so we fall through to the regular block screen (not re-intervention). */
    private fun tryHandleGraceExpiredReIntervention(pkg: String): Boolean {
        val expiredPkg = BlockingSharedState.graceExpiredForPackage ?: return false
        if (expiredPkg != pkg) return false
        BlockingSharedState.setGraceExpiredForPackage(null)
        return false
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

    /** Updates local state for next event. Shared current-foreground is already set at start of processEvent. */
    private fun setPreviousForeground(pkg: String) {
        previousForegroundPackage = pkg
    }

    private suspend fun handleNonRestrictedApp(pkg: String, graceMs: Long) {
        Log.d(TAG, "processEvent: pkg not restricted, considering suggestion flow")

        // Never suggest for hardcoded excluded packages.
        if (pkg in ExcludedApps.EXCLUDED_PACKAGES) {
            Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg is in hardcoded exclusion list, allowing without suggestion")
            setPreviousForeground(pkg)
            return
        }

        // Only suggest for apps that have a launcher activity (same filter as settings app selection).
        val hasLauncherActivity = applicationContext.packageManager.getLaunchIntentForPackage(pkg) != null
        if (!hasLauncherActivity) {
            Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg has no launcher activity, skipping suggestion")
            setPreviousForeground(pkg)
            return
        }

        if (app.appOpenTracker.wasSuggestionShown(pkg)) {
            setPreviousForeground(pkg)
            return
        }
        val foregroundMs = try {
            withContext(Dispatchers.IO) {
                queryForegroundTimeMs(pkg, SUGGESTION_WINDOW_MS)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleNonRestrictedApp: queryForegroundTimeMs failed for pkg=$pkg", t)
            setPreviousForeground(pkg)
            return
        }
        val foregroundMinutes = foregroundMs / 60_000L
        Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg foregroundMinutes=$foregroundMinutes in last 12h")
        if (foregroundMinutes < SUGGESTION_THRESHOLD_MINUTES) {
            setPreviousForeground(pkg)
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
            setPreviousForeground(pkg)
            return
        }
        Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg isIgnored=$isIgnored isPermanentlyExcluded=$isPermanentlyExcluded")
        if (isIgnored || isPermanentlyExcluded) {
            setPreviousForeground(pkg)
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

                            BlockingSharedState.setGraceValidUntil(
                                pkg,
                                System.currentTimeMillis() + graceMs
                            )
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
        setPreviousForeground(pkg)
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
            Log.e(TAG, "startGracePeriodService failed for pkg=$packageName", t)
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
        private const val SUGGESTION_WINDOW_MS = 12L * 60L * 60L * 1000L // 12 hours
        private const val SUGGESTION_THRESHOLD_MINUTES = 1L
    }
}
