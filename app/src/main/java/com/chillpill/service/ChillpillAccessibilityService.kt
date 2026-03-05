package com.chillpill.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lastPackage: String? = null
    private var lastClassName: String? = null
    private val blockShownAt = mutableMapOf<String, Long>()
    private val latestClosingTime = mutableMapOf<String, Long>()

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
        val className = event.className?.toString()

        // Same app: no block logic
        if (pkg == lastPackage) {
            lastPackage = pkg
            lastClassName = className
            BlockingSharedState.setLastForegroundPackage(pkg)
            return
        }

        serviceScope.launch {
            processEvent(pkg, className)
        }
    }

    private suspend fun processEvent(pkg: String, className: String?) {
        try {
            val monitored = withContext(Dispatchers.IO) {
                app.monitoredAppsRepository.monitoredPackages.first()
            }

            // Leaving a monitored app (previous was monitored, switching to any other app)
            lastPackage?.let { prev ->
                if (prev in monitored && prev != pkg) {
                    latestClosingTime[prev] = System.currentTimeMillis()
                }
            }
            BlockingSharedState.setLastForegroundPackage(pkg)

            if (pkg !in monitored) {
                lastPackage = pkg
                lastClassName = className
                return
            }

            // Check grace-expired flag (re-intervention from GracePeriodService when app was not in foreground)
            BlockingSharedState.graceExpiredForPackage?.let { expiredPkg ->
                if (expiredPkg == pkg) {
                    BlockingSharedState.setGraceExpiredForPackage(null)
                    startBlockActivity(pkg, className, isReIntervention = true)
                    updateLastAndForeground(pkg, className)
                    return
                }
            }

            // User returned from our block (last was Chillpill, now opening monitored app)
            if (lastPackage == applicationContext.packageName) {
                blockShownAt.remove(pkg)
                startGracePeriodService(pkg, className)
                updateLastAndForeground(pkg, className)
                return
            }

            // Entering monitored app from elsewhere: check grace and maybe show block
            val settings = withContext(Dispatchers.IO) {
                app.settingsRepository.settings.first()
            }
            val graceMs = settings.gracePeriodMinutes * 60L * 1000L
            val closedAt = latestClosingTime[pkg] ?: 0L
            val graceExpired = closedAt + graceMs < System.currentTimeMillis()

            if (graceExpired) {
                withContext(Dispatchers.IO) {
                    app.usageEventsRepository.recordEvent(pkg, UsageEventType.OPEN_ATTEMPT)
                }
                blockShownAt[pkg] = System.currentTimeMillis()
                startBlockActivity(pkg, className, isReIntervention = false)
            } else {
                startGracePeriodService(pkg, className)
            }

            updateLastAndForeground(pkg, className)
        } catch (e: Exception) {
            Log.e(TAG, "processEvent failed for pkg=$pkg", e)
        }
    }

    private fun updateLastAndForeground(pkg: String, className: String?) {
        lastPackage = pkg
        lastClassName = className
        BlockingSharedState.setLastForegroundPackage(pkg)
    }

    private fun startBlockActivity(packageName: String, className: String?, isReIntervention: Boolean) {
        try {
            val intent = Intent(applicationContext, AppBlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra(AppBlockActivity.EXTRA_PACKAGE_NAME, packageName)
                className?.let { putExtra(AppBlockActivity.EXTRA_CLASS_NAME, it) }
                putExtra(AppBlockActivity.EXTRA_IS_RE_INTERVENTION, isReIntervention)
            }
            applicationContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startBlockActivity failed for packageName=$packageName", e)
        }
    }

    private fun startGracePeriodService(packageName: String, className: String?) {
        try {
            val intent = Intent(applicationContext, GracePeriodService::class.java).apply {
                action = GracePeriodService.ACTION_START
                putExtra(GracePeriodService.EXTRA_PACKAGE_NAME, packageName)
                className?.let { putExtra(GracePeriodService.EXTRA_CLASS_NAME, it) }
            }
            applicationContext.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startGracePeriodService failed for packageName=$packageName", e)
        }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "ChillpillA11y"
    }
}
