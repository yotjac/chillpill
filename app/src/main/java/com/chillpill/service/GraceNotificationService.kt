package com.chillpill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chillpill.ChillpillApp
import com.chillpill.MainActivity
import com.chillpill.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The ongoing "grace period" notification, shown while any grace window runs. Cosmetic only (B3
 * in specs/session-engine.md): grace is enforced by the session engine, so if this service fails
 * to start or the user stops it, nothing about blocking changes. Started by [com.chillpill.service.engine.SessionEngine]
 * whenever `graceActive` turns true; stops itself when it turns false.
 */
class GraceNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    /**
     * Latest start id delivered to this instance. Stopping with it is ignored by Android when a
     * newer start is already on its way, which would otherwise crash the process
     * (startForegroundService without startForeground).
     */
    @Volatile
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed; no grace notification this time", t)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (watchJob == null) {
            watchJob = scope.launch {
                (application as ChillpillApp).sessionEngine.graceActive.collect { active ->
                    // The notification goes with the service; a newer start keeps both alive.
                    if (!active) stopSelfResult(lastStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

    private companion object {
        const val TAG = "GraceNotification"
        const val CHANNEL_ID = "chillpill_grace"
        const val NOTIFICATION_ID = 1
    }
}
