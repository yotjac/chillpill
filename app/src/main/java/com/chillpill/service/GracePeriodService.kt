package com.chillpill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.app.usage.UsageStatsManager
import androidx.core.app.NotificationCompat
import com.chillpill.ChillpillApp
import com.chillpill.ReInterventionActivity
import com.chillpill.R
import com.chillpill.data.usage.UsageEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that monitors the grace period for one or more restricted apps at once.
 *
 * Each restricted package gets its own monitoring coroutine (keyed in [graceJobs]) so that
 * starting grace for one app never cancels or interferes with another app's in-flight grace
 * window. A SupervisorJob is used for [serviceScope] so an uncaught failure in one package's
 * job cannot tear down the others, and a [CoroutineExceptionHandler] ensures such a failure is
 * logged instead of crashing the process.
 *
 * Rather than counting down a fixed local duration, each job polls the authoritative expiry
 * held in [BlockingSharedState] (`isInGracePeriod`). This means if the user's grace window gets
 * extended after the timer started (e.g. by leaving and returning to the app before expiry —
 * see [ChillpillAccessibilityService.recordLeavingRestrictedApp]), the job simply keeps waiting
 * instead of firing re-intervention against a now-stale expiry time.
 */
class GracePeriodService : Service() {

    private val app: ChillpillApp
        get() = application as ChillpillApp

    private val serviceScope = CoroutineScope(
        Dispatchers.Main.immediate +
            SupervisorJob() +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "Uncaught exception in GracePeriodService serviceScope", t)
            }
    )

    /** One monitoring job per package currently in grace. Guarded by identity-checked removal to avoid a
     * restart racing with the previous job's own cleanup (see startGraceTimer). */
    private val graceJobs = ConcurrentHashMap<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_NOT_STICKY
                startGraceTimer(packageName)
            }
            else -> { /* ignore */ }
        }
        return START_NOT_STICKY
    }

    private fun startGraceTimer(packageName: String) {
        // Only cancel this package's own prior job (if any); other packages' grace monitoring
        // must keep running independently.
        graceJobs[packageName]?.cancel()
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            if (graceJobs.isEmpty()) stopSelf()
            return
        }

        // Dispatched on Default (not Main.immediate) so this launch() call always returns before
        // the coroutine body runs; that guarantees `job` below is assigned before the coroutine's
        // `finally` block can read it back out of the closure.
        var job: Job? = null
        job = serviceScope.launch(Dispatchers.Default) {
            try {
                if (!BlockingSharedState.isInGracePeriod(packageName)) {
                    Log.w(TAG, "startGraceTimer: no active grace window for pkg=$packageName at job start; not monitoring")
                    return@launch
                }
                Log.d(TAG, "startGraceTimer: monitoring pkg=$packageName")
                while (BlockingSharedState.isInGracePeriod(packageName)) {
                    delay(1000L)
                }
                onGraceExpired(packageName)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "grace monitoring failed for pkg=$packageName", t)
            } finally {
                // Identity-checked removal: if a newer job has already replaced this one in the
                // map (e.g. grace was restarted for the same package while this job was winding
                // down), don't clobber that newer entry. `job` is guaranteed non-null by the time
                // this runs (Dispatchers.Default always dispatches, so the assignment below always
                // completes before this coroutine body starts), but the compiler can't smart-cast a
                // captured `var`, hence the explicit null-check.
                job?.let { graceJobs.remove(packageName, it) }
                if (graceJobs.isEmpty()) {
                    stopSelf()
                }
            }
        }
        graceJobs[packageName] = job
    }

    /**
     * Best-effort lookup of the package currently in the foreground, using UsageStatsManager.
     * Returns null (letting the caller fall back to [BlockingSharedState.currentForegroundPackage])
     * unless the most recent relevant event in the window is an unmatched RESUME — i.e. we only
     * report a package as "foreground" when nothing has paused it since. This avoids treating a
     * package as still active when the user has since locked the screen, gone home, or switched
     * away, which would previously have been missed because pause events were never consulted.
     */
    private fun queryActualForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val usageEvents = usm.queryEvents(now - 600_000, now)
        val event = UsageEvents.Event()
        val resumeType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val pauseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }
        var lastResumedPkg: String? = null
        var lastEventWasUnpausedResume = false
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            when (event.eventType) {
                resumeType -> {
                    lastResumedPkg = event.packageName
                    lastEventWasUnpausedResume = true
                }
                pauseType -> {
                    if (event.packageName == lastResumedPkg) {
                        lastEventWasUnpausedResume = false
                    }
                }
            }
        }
        return if (lastEventWasUnpausedResume) lastResumedPkg else null
    }

    private suspend fun onGraceExpired(packageName: String) {
        BlockingSharedState.clearGraceForPackage(packageName)
        val currentForeground = queryActualForegroundPackage()
            ?: BlockingSharedState.currentForegroundPackage
        val reInterventionDisabled = withContext(Dispatchers.IO) {
            app.restrictedAppsRepository.reInterventionDisabledPackages.first()
        }
        Log.d(TAG, "onGraceExpired: pkg=$packageName currentForeground=$currentForeground")
        if (packageName in reInterventionDisabled) {
            Log.d(TAG, "onGraceExpired: re-intervention disabled for pkg=$packageName, recording event only")
            withContext(Dispatchers.IO) {
                if (currentForeground == packageName) {
                    app.usageEventsRepository.recordEvent(
                        packageName,
                        UsageEventType.GRACE_EXPIRED_WHILE_ACTIVE
                    )
                } else {
                    app.usageEventsRepository.recordEvent(
                        packageName,
                        UsageEventType.GRACE_EXPIRED_WHILE_AWAY
                    )
                }
            }
            return
        }
        if (currentForeground == packageName) {
            Log.d(TAG, "onGraceExpired: user still in app, showing block")
            withContext(Dispatchers.IO) {
                app.usageEventsRepository.recordEvent(packageName, UsageEventType.OPEN_ATTEMPT)
                app.usageEventsRepository.recordEvent(packageName, UsageEventType.GRACE_EXPIRED_WHILE_ACTIVE)
            }
            startBlockActivity(packageName)
        } else {
            Log.d(TAG, "onGraceExpired: user left app, setting graceExpiredForPackage")
            withContext(Dispatchers.IO) {
                app.usageEventsRepository.recordEvent(packageName, UsageEventType.GRACE_EXPIRED_WHILE_AWAY)
            }
            BlockingSharedState.setGraceExpiredForPackage(packageName)
        }
    }

    private fun startBlockActivity(packageName: String) {
        try {
            val intent = Intent(applicationContext, ReInterventionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra(ReInterventionActivity.EXTRA_PACKAGE_NAME, packageName)
            }
            applicationContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startBlockActivity failed for packageName=$packageName", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.grace_notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.chillpill.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.grace_notification_title))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        graceJobs.clear()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "GracePeriodService"
        const val ACTION_START = "com.chillpill.grace.START"
        const val ACTION_RESUME = "com.chillpill.grace.RESUME"
        const val EXTRA_PACKAGE_NAME = "packageName"
        private const val CHANNEL_ID = "chillpill_grace"
        private const val NOTIFICATION_ID = 1
    }
}
