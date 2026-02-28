package com.chillpill.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Listens for app window changes. When the user opens an app that is not in the whitelist,
 * shows an overlay directly from this service (no foreground service from background).
 * On Android 12+ starting a foreground service from background is blocked, so we draw the
 * overlay here instead of starting OverlayService.
 */
class AppDetectionAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayRunnable: Runnable? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return
        if (pkg == "com.android.launcher" || pkg == "com.android.launcher3") return

        val prefs = Prefs(this)
        if (pkg in prefs.whitelist) return

        showOverlay()
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        val prefs = Prefs(this)
        val seconds = prefs.timerSeconds

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_wait, null).apply {
            val timerText = findViewById<android.widget.TextView>(R.id.overlay_timer)
            val messageText = findViewById<android.widget.TextView>(R.id.overlay_message)
            val buttonsPanel = findViewById<View>(R.id.overlay_buttons)
            val btnContinue = findViewById<android.widget.Button>(R.id.btn_continue)
            val btnCloseHome = findViewById<android.widget.Button>(R.id.btn_close_home)

            timerText.text = seconds.toString()
            messageText.text = getString(R.string.wait_message)
            buttonsPanel.visibility = View.GONE

            var remaining = seconds
            overlayRunnable = object : Runnable {
                override fun run() {
                    if (overlayView == null) return
                    remaining--
                    if (remaining > 0) {
                        timerText.text = remaining.toString()
                        handler.postDelayed(this, 1000)
                    } else {
                        timerText.visibility = View.GONE
                        messageText.text = getString(R.string.wait_message)
                        buttonsPanel.visibility = View.VISIBLE
                    }
                }
            }
            handler.postDelayed(overlayRunnable!!, 1000)

            btnContinue.setOnClickListener { removeOverlay() }
            btnCloseHome.setOnClickListener {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                removeOverlay()
            }
        }

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            overlayView = null
            overlayRunnable = null
        }
    }

    private fun removeOverlay() {
        overlayRunnable?.let { handler.removeCallbacks(it) }
        overlayRunnable = null
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
