package com.chillpill.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import android.view.accessibility.AccessibilityEvent
import com.chillpill.AppBlockActivity
import com.chillpill.ChillpillApp
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

    /** Previous foreground package; used to detect "leaving" and "return from block". Updated at end of each event. Only accessed from eventProcessorScope. */
    private var previousForegroundPackage: String? = null
    private val blockShownAt = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = null // receive events for all packages; we filter by monitored set
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
            val monitoredPackages = withContext(Dispatchers.IO) {
                app.monitoredAppsRepository.monitoredPackages.first()
            }
            val settings = withContext(Dispatchers.IO) {
                app.settingsRepository.settings.first()
            }
            val graceMs = settings.gracePeriodMinutes * 60L * 1000L

            Log.d(TAG, "processEvent: pkg=$pkg monitoredCount=${monitoredPackages.size} monitored=$monitoredPackages")

            recordLeavingMonitoredApp(monitoredPackages, pkg, graceMs)
            // GracePeriodService reads this when the timer expires to decide re-intervene vs set grace-expired flag
            BlockingSharedState.setCurrentForegroundPackage(pkg)

            if (pkg !in monitoredPackages) {
                Log.d(TAG, "processEvent: pkg not monitored, allowing")
                setPreviousForeground(pkg)
                return
            }

            if (tryHandleGraceExpiredReIntervention(pkg)) {
                Log.d(TAG, "processEvent: handled grace-expired re-intervention for pkg=$pkg")
                setPreviousForeground(pkg)
                return
            }
            if (tryHandleReturnFromBlock(pkg)) {
                Log.d(TAG, "processEvent: handled return from block, starting grace for pkg=$pkg")
                setPreviousForeground(pkg)
                return
            }
            if (BlockingSharedState.isInGracePeriod(pkg)) {
                Log.d(TAG, "processEvent: pkg=$pkg in grace period, allowing")
                setPreviousForeground(pkg)
                return
            }
            Log.d(TAG, "processEvent: showing block for pkg=$pkg (entering from elsewhere, grace expired)")
            handleEnteringMonitoredAppFromElsewhere(pkg)

            setPreviousForeground(pkg)
        } catch (e: Exception) {
            Log.e(TAG, "processEvent failed for pkg=$pkg", e)
        }
    }

    private fun recordLeavingMonitoredApp(monitoredPackages: Set<String>, newPackage: String, graceMs: Long) {
        // Do not grant grace when switching to our block activity: user did not leave the app voluntarily
        if (newPackage == applicationContext.packageName) return
        previousForegroundPackage?.let { prev ->
            if (prev in monitoredPackages && prev != newPackage) {
                BlockingSharedState.setGraceValidUntil(prev, System.currentTimeMillis() + graceMs)
            }
        }
    }

    private fun tryHandleGraceExpiredReIntervention(pkg: String): Boolean {
        val expiredPkg = BlockingSharedState.graceExpiredForPackage ?: return false
        if (expiredPkg != pkg) return false
        BlockingSharedState.setGraceExpiredForPackage(null)
        startBlockActivity(pkg, isReIntervention = true)
        return true
    }

    private fun tryHandleReturnFromBlock(pkg: String): Boolean {
        if (previousForegroundPackage != applicationContext.packageName) return false
        blockShownAt.remove(pkg)
        startGracePeriodService(pkg)
        return true
    }

    private suspend fun handleEnteringMonitoredAppFromElsewhere(pkg: String) {
        withContext(Dispatchers.IO) {
            app.usageEventsRepository.recordEvent(pkg, UsageEventType.OPEN_ATTEMPT)
        }
        blockShownAt[pkg] = System.currentTimeMillis()
        startBlockActivity(pkg, isReIntervention = false)
    }

    /** Updates local state for next event. Shared current-foreground is already set at start of processEvent. */
    private fun setPreviousForeground(pkg: String) {
        previousForegroundPackage = pkg
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

    private fun startGracePeriodService(packageName: String) {
        try {
            val intent = Intent(applicationContext, GracePeriodService::class.java).apply {
                action = GracePeriodService.ACTION_START
                putExtra(GracePeriodService.EXTRA_PACKAGE_NAME, packageName)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
        } catch (e: Exception) {
            Log.e(TAG, "startGracePeriodService failed for packageName=$packageName", e)
        }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "ChillpillA11y"
    }
}
