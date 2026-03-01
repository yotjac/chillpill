package com.chillpill.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Listens for app window changes. Only blocks apps that are in the user's blacklist.
 * Blockable set = launchable apps (including system apps like YouTube), excluding us and launchers.
 * Periodically checks if grace period expired while user is still in a blacklisted app and shows block.
 */
class AppDetectionAccessibilityService : AccessibilityService() {

    /** Package names that can be blacklisted (same set as shown in the app picker). */
    private var blockablePackages: Set<String> = emptySet()

    /** Last known foreground app (from TYPE_WINDOW_STATE_CHANGED). */
    private var lastForegroundPackage: String? = null
    private var lastForegroundClassName: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val graceCheckIntervalMs = 10_000L

    private val graceCheckRunnable = object : Runnable {
        override fun run() {
            checkGraceExpiredThenReschedule()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        val className = event.className?.toString()
        Log.i(TAG, "onAccessibilityEvent pkg=$pkg className=$className")

        if (pkg != null && pkg.isNotEmpty()) {
            lastForegroundPackage = pkg
            lastForegroundClassName = className
        }

        if (pkg == null || pkg.isEmpty()) {
            Log.i(TAG, "Ignore: no package")
            return
        }
        if (pkg == packageName) {
            Log.i(TAG, "Ignore: our own app")
            return
        }
        if (pkg !in blockablePackages) {
            Log.i(TAG, "Ignore: $pkg not in blockable set (launcher/not launchable)")
            return
        }

        val prefs = Prefs(this)
        if (pkg !in prefs.blacklist) {
            Log.i(TAG, "Ignore: $pkg not blacklisted")
            return
        }
        val graceUntil = prefs.getGraceUntil(pkg)
        if (graceUntil != null && System.currentTimeMillis() < graceUntil) {
            Log.i(TAG, "Ignore: $pkg in grace period until $graceUntil")
            return
        }

        launchBlockActivity(pkg, className)
    }

    /** If current foreground app is blacklisted and grace just expired, show block. Then reschedule. */
    private fun checkGraceExpiredThenReschedule() {
        val pkg = lastForegroundPackage ?: run {
            handler.postDelayed(graceCheckRunnable, graceCheckIntervalMs)
            return
        }
        if (pkg == packageName || pkg !in blockablePackages) {
            handler.postDelayed(graceCheckRunnable, graceCheckIntervalMs)
            return
        }
        val prefs = Prefs(this)
        if (pkg !in prefs.blacklist) {
            handler.postDelayed(graceCheckRunnable, graceCheckIntervalMs)
            return
        }
        val graceUntil = prefs.getGraceUntil(pkg)
        if (graceUntil != null && System.currentTimeMillis() < graceUntil) {
            handler.postDelayed(graceCheckRunnable, graceCheckIntervalMs)
            return
        }
        Log.i(TAG, "Grace expired for $pkg while in foreground, launching BlockActivity")
        launchBlockActivity(pkg, lastForegroundClassName)
        handler.postDelayed(graceCheckRunnable, graceCheckIntervalMs)
    }

    private fun launchBlockActivity(pkg: String, className: String?) {
        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
            if (!className.isNullOrEmpty()) putExtra(BlockActivity.EXTRA_CLASS_NAME, className)
        }
        try {
            startActivity(intent)
            // Prevent the 10s grace check from firing again before we get TYPE_WINDOW_STATE_CHANGED
            // for our app — otherwise it would relaunch BlockActivity → onNewIntent → timer reset.
            lastForegroundPackage = packageName
            lastForegroundClassName = null
            Log.i(TAG, "BlockActivity started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BlockActivity", e)
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        blockablePackages = BlockableApps.getBlockablePackageNames(this)
        Log.i(TAG, "Blockable packages count: ${blockablePackages.size}")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
        handler.postDelayed(graceCheckRunnable, graceCheckIntervalMs)
        Log.i(TAG, "Accessibility service CONNECTED, grace check scheduled")
    }

    override fun onDestroy() {
        handler.removeCallbacks(graceCheckRunnable)
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChillPill/A11y"
    }
}
