package com.chillpill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chillpill.ChillpillApp
import com.chillpill.ReInterventionActivity
import com.chillpill.R
import com.chillpill.data.usage.UsageEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that monitors the grace period for one or more restricted apps at once.
 *
 * Each restricted package gets its own monitoring coroutine (keyed in [graceJobs]) so that
 * starting grace for one app never cancels or interferes with another app's in-flight grace
 * window. A SupervisorJob is used for [serviceScope] so an uncaught failure in one package's
 * job cannot tear down the others, and a [CoroutineExceptionHandler] ensures such a failure is
 * logged instead of crashing the process.
 *
 * Rather than counting down a local duration, each job polls the authoritative deadline held in
 * [BlockingSharedState] (`isInGracePeriod`). That deadline is fixed when the user taps Continue
 * (leaving the app does not extend it); it only moves if the user passes a block again. Because the
 * job reads the deadline on every tick, a second start for a package that is already being
 * monitored does not replace its job: the existing one simply follows the new deadline.
 *
 * Invariant: **a package is never left with a valid grace window and no job watching it.** Such a
 * package would be allowed back in forever (`SessionPolicy.onEnterFromElsewhere` sees grace as
 * valid) and never kicked out. Every path that can lose a job — a stop racing a newer start, the
 * system or the user stopping the service, `startForeground` failing — therefore either keeps the
 * job alive or ends the package's session so the next open shows the regular block.
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

    /**
     * Number of [startGraceTimer] calls that have not yet registered their job. A job finishing on a
     * background thread while `onStartCommand` for another package is running on the main thread
     * must not see an empty map and stop the service out from under the new job.
     */
    private val startsInFlight = AtomicInteger(0)

    /**
     * The most recent `startId` handed to [onStartCommand]. Every stop goes through
     * [stopSelf] with this id: Android then ignores the stop if a newer start has been delivered to
     * (or is queued for) this instance. A plain `stopSelf()` has no such guard — it tears the
     * service down together with the job that newer start just launched, leaving that package
     * in grace with nobody watching it.
     */
    @Volatile
    private var lastStartId = -1

    /** One monitoring job per package currently in grace. */
    private val graceJobs = ConcurrentHashMap<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (packageName == null) {
                    lastStartId = startId
                    stopIfIdle()
                    return START_NOT_STICKY
                }
                startGraceTimer(packageName, startId)
            }
            else -> {
                lastStartId = startId
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun startGraceTimer(packageName: String, startId: Int) {
        startsInFlight.incrementAndGet()
        try {
            startGraceTimerInternal(packageName, startId)
        } finally {
            // Published only once the job is registered: a job finishing concurrently then either
            // sees the new job in the map, or stops with a stale id that Android ignores.
            lastStartId = startId
            startsInFlight.decrementAndGet()
            // The job may already have finished (e.g. no grace window at job start) while this
            // counter was still 1 and held its own stop back; re-check now that it is 0.
            stopIfIdle()
        }
    }

    private fun startGraceTimerInternal(packageName: String, startId: Int) {
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Without the service the grace window would never be enforced; end it instead so the
            // next open shows the block (see the class invariant).
            Log.e(TAG, "startForeground failed; ending session for pkg=$packageName so it is not left in unmonitored grace", e)
            if (graceJobs[packageName]?.isActive != true) {
                BlockingSharedState.sessions.endSession(packageName)
            }
            // This start is the newest one, so stop with its own id (lastStartId is not yet updated).
            if (graceJobs.isEmpty() && startsInFlight.get() == 1) stopSelf(startId)
            return
        }

        val existing = graceJobs[packageName]
        if (existing != null && existing.isActive) {
            // Already monitored (double tap on Continue, two stacked block screens, ...). The job
            // polls the shared deadline each tick, so it picks up the new one by itself; cancelling
            // and replacing it here is what all the previous stop/start races were made of.
            Log.d(TAG, "startGraceTimer: pkg=$packageName already monitored, keeping existing job")
            return
        }

        // Started LAZY and registered before it runs, so the map can never miss a job that has
        // already finished (a finished job removes itself by identity; see the `finally` below).
        val job = serviceScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            try {
                if (!BlockingSharedState.isInGracePeriod(packageName)) {
                    Log.w(TAG, "startGraceTimer: no active grace window for pkg=$packageName at job start; not monitoring")
                    return@launch
                }
                Log.d(TAG, "startGraceTimer: monitoring pkg=$packageName")
                // Read once per job: the toggle can only change through Settings, and the worst case
                // of a stale read is one unnecessary (or one missing) warning pill.
                val reInterventionDisabled = withContext(Dispatchers.IO) {
                    app.restrictedAppsRepository.reInterventionDisabledPackages.first()
                }.contains(packageName)
                var tick = 0L
                while (true) {
                    val remaining = BlockingSharedState.sessions.graceRemainingMs(packageName)
                    if (remaining <= 0L) break
                    // Heartbeat: every second through the last half-minute, every 30 s before that.
                    // Without it a silent loop and a dead loop look identical in logcat.
                    if (remaining <= 30_000L || tick % 30L == 0L) {
                        Log.d(
                            TAG,
                            "tick: pkg=$packageName remaining=${remaining}ms " +
                                "foreground=${BlockingSharedState.currentForegroundPackage}"
                        )
                    }
                    tick++
                    // Two warnings with different lead times: the first, with "+10 s" on offer,
                    // gets the full 10 s and can be tapped away; the second — after the extension
                    // has been spent — comes at 5 s and cannot be dismissed.
                    val canExtend = BlockingSharedState.sessions.canExtendGrace(packageName)
                    val lead = if (canExtend) {
                        SessionPolicy.WARNING_LEAD_MS
                    } else {
                        SessionPolicy.FINAL_WARNING_LEAD_MS
                    }
                    if (remaining <= lead && !reInterventionDisabled) {
                        publishWarning(packageName, remaining, canExtend)
                    } else {
                        // Outside a warning window: before the lead time, or just after "+10 s"
                        // pushed the deadline back beyond it.
                        clearWarningFor(packageName)
                    }
                    delay(1000L)
                }
                // The pill must be gone before ReInterventionActivity launches.
                clearWarningFor(packageName)
                onGraceExpired(packageName)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "grace monitoring failed for pkg=$packageName", t)
            } finally {
                // A stale pill must not survive the job (whatever ended it).
                clearWarningFor(packageName)
                // Identity-checked removal so a newer job for the same package is never clobbered.
                graceJobs.remove(packageName, coroutineContext.job)
                // A start that arrived while this job was already winding down was "kept" onto a
                // job that is now gone. Rather than leave that grace window unmonitored, end it.
                if (graceJobs[packageName] == null && BlockingSharedState.isInGracePeriod(packageName)) {
                    Log.w(TAG, "job ended for pkg=$packageName while its grace is still valid; ending session")
                    BlockingSharedState.sessions.endSession(packageName)
                }
                stopIfIdle()
            }
        }
        graceJobs[packageName] = job
        job.start()
    }

    /**
     * Stops the service once nothing is being monitored. Always with [lastStartId]: if a start
     * newer than the one this instance last saw is already on its way, Android ignores the stop
     * and the service stays up for it. Safe to call from any thread.
     */
    private fun stopIfIdle() {
        // Snapshot first: if a start lands between the checks and the stop, this id is stale and
        // Android rejects the stop. Reading it after the checks could pick up that start's own id
        // and stop the service with its freshly registered job inside.
        val startId = lastStartId
        if (graceJobs.isEmpty() && startsInFlight.get() == 0) {
            Log.d(TAG, "stopIfIdle: no packages monitored, stopping (startId=$startId)")
            stopSelf(startId)
        }
    }

    /**
     * Publishes the warning for [packageName], but only while it is the app the user is actually
     * looking at: with two restricted apps in their last seconds, the foreground one wins and the
     * other leaves the single warning slot alone (R3 in specs/grace-expiry-warning.md).
     */
    private fun publishWarning(packageName: String, remainingMs: Long, canExtend: Boolean) {
        if (BlockingSharedState.currentForegroundPackage != packageName) return
        val deadline = BlockingSharedState.sessions.graceDeadlineMs(packageName)
        if (deadline == 0L) return
        // Inside the warning window the package is always in grace, so canExtend == false means the
        // extension has been spent — and that second warning is the one the user cannot take away.
        val warning = BlockingSharedState.GraceWarning(
            packageName = packageName,
            deadlineWallMs = deadline,
            canExtend = canExtend,
            dismissible = canExtend
        )
        val current = BlockingSharedState.graceWarning.value
        // The deadline only moves on an extension, so comparing whole objects keeps the flow quiet
        // while the countdown ticks (the pill computes the seconds itself).
        if (current != warning) {
            Log.d(
                TAG,
                "publishWarning: pkg=$packageName remaining=${remainingMs}ms " +
                    "canExtend=$canExtend dismissible=${warning.dismissible}"
            )
            BlockingSharedState.setGraceWarning(warning)
        }
    }

    /** Clears the warning only if it is this package's; another package's pill must survive. */
    private fun clearWarningFor(packageName: String) {
        if (BlockingSharedState.graceWarning.value?.packageName == packageName) {
            Log.d(TAG, "clearWarning: pkg=$packageName")
            BlockingSharedState.setGraceWarning(null)
        }
    }

    private suspend fun onGraceExpired(packageName: String) {
        BlockingSharedState.clearGraceForPackage(packageName)
        val currentForeground = ForegroundPackageQuery.query(this)
            ?: BlockingSharedState.currentForegroundPackage
        val reInterventionDisabled = withContext(Dispatchers.IO) {
            app.restrictedAppsRepository.reInterventionDisabledPackages.first()
        }
        Log.d(
            TAG,
            "onGraceExpired: pkg=$packageName currentForeground=$currentForeground " +
                "(shared=${BlockingSharedState.currentForegroundPackage})"
        )
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
            // Re-intervention is a block: the session is over until the user taps Continue again.
            BlockingSharedState.sessions.endSession(packageName)
            startBlockActivity(packageName)
        } else {
            Log.d(TAG, "onGraceExpired: user left app, ending session (next open shows the regular block)")
            withContext(Dispatchers.IO) {
                app.usageEventsRepository.recordEvent(packageName, UsageEventType.GRACE_EXPIRED_WHILE_AWAY)
            }
            // For apps with re-intervention enabled, grace *is* the session: once it runs out the
            // user has to pass the block again, whether they are still inside or not.
            BlockingSharedState.sessions.endSession(packageName)
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
        BlockingSharedState.setGraceWarning(null)
        // Being destroyed with jobs still registered means something other than [stopIfIdle] took
        // the service down (system, user "Stop" on the foreground-service notification, a lost
        // race). Those packages would keep a valid grace window with nothing enforcing it, so end
        // their sessions: the next open shows the regular block. The user is not kicked out of the
        // app they are in — that needs a running service — but they cannot get back in for free.
        val orphaned = graceJobs.keys.toList()
        graceJobs.clear()
        serviceScope.cancel()
        for (pkg in orphaned) {
            if (BlockingSharedState.isInGracePeriod(pkg)) {
                Log.w(TAG, "onDestroy: pkg=$pkg still in grace with no monitor; ending its session")
                BlockingSharedState.sessions.endSession(pkg)
            }
        }
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
