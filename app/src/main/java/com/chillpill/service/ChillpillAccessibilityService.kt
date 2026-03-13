package com.chillpill.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.chillpill.AppBlockActivity
import com.chillpill.ChillpillApp
import com.chillpill.SuggestRestrictionActivity
import com.chillpill.data.suggestion.ExcludedApps
import com.chillpill.data.usage.UsageEventType
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
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )

    /** Previous foreground package; used to detect "leaving" for grace extension. Updated at end of each event. Only accessed from eventProcessorScope. */
    private var previousForegroundPackage: String? = null

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

            if (pkg !in restrictedPackages) {
                handleNonRestrictedApp(pkg)
                return
            }

            if (tryHandleGraceExpiredReIntervention(pkg)) {
                Log.d(TAG, "processEvent: handled grace-expired re-intervention for pkg=$pkg")
                setPreviousForeground(pkg)
                return
            }
            if (BlockingSharedState.isInGracePeriod(pkg)) {
                Log.d(TAG, "processEvent: pkg=$pkg in grace period, allowing")
                setPreviousForeground(pkg)
                return
            }
            Log.d(TAG, "processEvent: showing block for pkg=$pkg (entering from elsewhere, grace expired)")
            handleEnteringRestrictedAppFromElsewhere(pkg)

            setPreviousForeground(pkg)
        } catch (e: Exception) {
            Log.e(TAG, "processEvent failed for pkg=$pkg", e)
        }
    }

    private fun recordLeavingRestrictedApp(restrictedPackages: Set<String>, newPackage: String, graceMs: Long) {
        // Do not grant grace when switching to our block activity: user did not leave the app voluntarily
        if (newPackage == applicationContext.packageName) return
        previousForegroundPackage?.let { prev ->
            if (prev in restrictedPackages && prev != newPackage) {
                BlockingSharedState.setGraceValidUntil(prev, System.currentTimeMillis() + graceMs)
            }
        }
    }

    private suspend fun tryHandleGraceExpiredReIntervention(pkg: String): Boolean {
        val expiredPkg = BlockingSharedState.graceExpiredForPackage ?: return false
        if (expiredPkg != pkg) return false
        val disabledPackages = withContext(Dispatchers.IO) {
            app.restrictedAppsRepository.reInterventionDisabledPackages.first()
        }
        if (pkg in disabledPackages) {
            Log.d(TAG, "tryHandleGraceExpiredReIntervention: re-intervention disabled for pkg=$pkg, clearing flag without blocking")
            BlockingSharedState.setGraceExpiredForPackage(null)
            return true
        }
        BlockingSharedState.setGraceExpiredForPackage(null)
        withContext(Dispatchers.IO) {
            app.usageEventsRepository.recordEvent(pkg, UsageEventType.OPEN_ATTEMPT)
        }
        startBlockActivity(pkg, isReIntervention = true)
        return true
    }

    private suspend fun handleEnteringRestrictedAppFromElsewhere(pkg: String) {
        withContext(Dispatchers.IO) {
            app.usageEventsRepository.recordEvent(pkg, UsageEventType.OPEN_ATTEMPT)
        }
        startBlockActivity(pkg, isReIntervention = false)
    }

    /** Updates local state for next event. Shared current-foreground is already set at start of processEvent. */
    private fun setPreviousForeground(pkg: String) {
        previousForegroundPackage = pkg
    }

    private suspend fun handleNonRestrictedApp(pkg: String) {
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

        // Track opens in the in-memory tracker.
        app.appOpenTracker.recordOpen(pkg)
        val count = app.appOpenTracker.getRecentOpenCount(pkg)
        Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg recentOpenCount=$count")

        if (count <= 5 || app.appOpenTracker.wasSuggestionShown(pkg)) {
            setPreviousForeground(pkg)
            return
        }

        val isIgnored = withContext(Dispatchers.IO) {
            app.suggestionRepository.isIgnored(pkg)
        }
        val isPermanentlyExcluded = withContext(Dispatchers.IO) {
            app.suggestionRepository.isPermanentlyExcluded(pkg)
        }
        if (isIgnored || isPermanentlyExcluded) {
            Log.d(TAG, "handleNonRestrictedApp: pkg=$pkg is ignored=$isIgnored permanentlyExcluded=$isPermanentlyExcluded")
            setPreviousForeground(pkg)
            return
        }

        app.appOpenTracker.markSuggestionShown(pkg)
        startSuggestRestrictionActivity(pkg)
        setPreviousForeground(pkg)
    }

    private fun startBlockActivity(packageName: String, isReIntervention: Boolean) {
        try {
            val intent = Intent(applicationContext, AppBlockActivity::class.java).apply {
                setPackage(applicationContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra(AppBlockActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AppBlockActivity.EXTRA_IS_RE_INTERVENTION, isReIntervention)
            }
            Log.d(TAG, "startBlockActivity: launching packageName=$packageName isReIntervention=$isReIntervention")
            applicationContext.startActivity(intent)
            Log.d(TAG, "startBlockActivity: startActivity returned for packageName=$packageName (if block does not appear, check Android 10+ background start restrictions)")
        } catch (e: Exception) {
            Log.e(TAG, "startBlockActivity failed for packageName=$packageName", e)
        }
    }

    private fun startSuggestRestrictionActivity(packageName: String) {
        try {
            val intent = Intent(applicationContext, SuggestRestrictionActivity::class.java).apply {
                setPackage(applicationContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra(SuggestRestrictionActivity.EXTRA_PACKAGE_NAME, packageName)
            }
            Log.d(TAG, "startSuggestRestrictionActivity: launching suggestion for packageName=$packageName")
            applicationContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startSuggestRestrictionActivity failed for packageName=$packageName", e)
        }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "ChillpillA11y"
    }
}
