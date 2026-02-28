package com.chillpill.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Listens for app window changes. Only blocks apps that appear in the whitelist picker
 * (user-installed, launchable, not launcher, not system). If the foreground app is not in that set,
 * we ignore it. If it is in the set but in the user's whitelist, we ignore. Otherwise we show BlockActivity.
 */
class AppDetectionAccessibilityService : AccessibilityService() {

    /** Package names that can be blocked (same set as shown in the app picker). */
    private var blockablePackages: Set<String> = emptySet()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        val className = event.className?.toString()
        Log.i(TAG, "onAccessibilityEvent pkg=$pkg className=$className")

        if (pkg == null || pkg.isEmpty()) {
            Log.i(TAG, "Ignore: no package")
            return
        }
        if (pkg == packageName) {
            Log.i(TAG, "Ignore: our own app")
            return
        }
        if (pkg !in blockablePackages) {
            Log.i(TAG, "Ignore: $pkg not in blockable set (launcher/system/not launchable)")
            return
        }

        val prefs = Prefs(this)
        if (pkg in prefs.whitelist) {
            Log.i(TAG, "Ignore: $pkg is whitelisted")
            return
        }

        Log.i(TAG, "Launching BlockActivity for pkg=$pkg className=$className")
        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
            if (!className.isNullOrEmpty()) putExtra(BlockActivity.EXTRA_CLASS_NAME, className)
        }
        try {
            startActivity(intent)
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
        Log.i(TAG, "Accessibility service CONNECTED")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChillPill/A11y"
    }
}
