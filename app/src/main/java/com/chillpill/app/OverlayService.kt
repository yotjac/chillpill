package com.chillpill.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

const val OVERLAY_NOTIFICATION_ID = 9001
const val CHANNEL_ID = "chillpill_overlay"

class OverlayService : Service() {

    private var overlayView: View? = null
    private var countDownTimer: CountDownTimer? = null
    private var blockedPackage: String? = null
    private val prefs by lazy { Prefs(this) }
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        blockedPackage = intent?.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        showOverlay()
        startForeground(OVERLAY_NOTIFICATION_ID, createNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_wait, null).apply {
            val timerText = findViewById<android.widget.TextView>(R.id.overlay_timer)
            val messageText = findViewById<android.widget.TextView>(R.id.overlay_message)
            val buttonsPanel = findViewById<android.view.ViewGroup>(R.id.overlay_buttons)
            val btnContinue = findViewById<android.widget.Button>(R.id.btn_continue)
            val btnCloseHome = findViewById<android.widget.Button>(R.id.btn_close_home)

            val seconds = prefs.timerSeconds
            var remaining = seconds

            timerText.text = remaining.toString()
            messageText.text = getString(R.string.wait_message)

            countDownTimer = object : CountDownTimer((seconds * 1000).toLong(), 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    remaining = (millisUntilFinished / 1000).toInt()
                    timerText.text = remaining.toString()
                }

                override fun onFinish() {
                    timerText.visibility = android.view.View.GONE
                    messageText.text = getString(R.string.wait_message)
                    buttonsPanel.visibility = android.view.View.VISIBLE
                }
            }.start()

            btnContinue.setOnClickListener {
                stopSelf()
            }

            btnCloseHome.setOnClickListener {
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(home)
                stopSelf()
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
        countDownTimer?.cancel()
        countDownTimer = null
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "blocked_package"
    }
}
