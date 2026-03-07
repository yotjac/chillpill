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
import com.chillpill.AppBlockActivity
import com.chillpill.ChillpillApp
import com.chillpill.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GracePeriodService : Service() {

    private val app: ChillpillApp
        get() = application as ChillpillApp

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var graceJob: Job? = null

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
        graceJob?.cancel()
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
            stopSelf()
            return
        }

        graceJob = serviceScope.launch {
            val graceMinutes = withContext(Dispatchers.IO) {
                app.settingsRepository.settings.first().gracePeriodMinutes
            }
            val graceSeconds = graceMinutes * 60L
            Log.d(TAG, "startGraceTimer: pkg=$packageName graceMinutes=$graceMinutes")
            var elapsed = 0L
            while (elapsed < graceSeconds) {
                delay(1000L)
                elapsed++
            }
            onGraceExpired(packageName)
        }
    }

    private suspend fun onGraceExpired(packageName: String) {
        BlockingSharedState.clearGraceForPackage(packageName)
        val currentForeground = BlockingSharedState.currentForegroundPackage
        Log.d(TAG, "onGraceExpired: pkg=$packageName currentForeground=$currentForeground")
        if (currentForeground == packageName) {
            Log.d(TAG, "onGraceExpired: user still in app, showing block")
            startBlockActivity(packageName)
        } else {
            Log.d(TAG, "onGraceExpired: user left app, setting graceExpiredForPackage")
            BlockingSharedState.setGraceExpiredForPackage(packageName)
        }
        stopSelf()
    }

    private fun startBlockActivity(packageName: String) {
        try {
            val intent = Intent(applicationContext, AppBlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra(AppBlockActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AppBlockActivity.EXTRA_IS_RE_INTERVENTION, true)
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
        graceJob?.cancel()
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
