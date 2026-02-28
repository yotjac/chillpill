package com.chillpill.app

import android.content.pm.ApplicationInfo
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Listens for app window changes. When the user opens an app that is not in the whitelist,
 * starts BlockActivity (full-screen pause screen). Starting an Activity from the accessibility
 * service is allowed; drawing an overlay or starting a foreground service from background is
 * restricted on Android 12+.
 */
class AppDetectionAccessibilityService : AccessibilityService() {

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
        if (pkg == "com.android.systemui") {
            Log.i(TAG, "Ignore: systemui")
            return
        }
        if (pkg == "com.android.launcher" || pkg == "com.android.launcher3") {
            Log.i(TAG, "Ignore: launcher")
            return
        }
        if (isSystemApp(pkg)) {
            Log.i(TAG, "Ignore: $pkg is system app")
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
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.i(TAG, "Accessibility service CONNECTED - you should see 'onAccessibilityEvent' when opening other apps")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun isSystemApp(pkg: String): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "ChillPill/A11y"
    }
}
