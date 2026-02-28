package com.chillpill.app

import android.accessibilityservice.AccessibilityService
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
        Log.d(TAG, "onAccessibilityEvent pkg=$pkg className=$className")

        if (pkg == null || pkg.isEmpty()) {
            Log.v(TAG, "Ignore: no package")
            return
        }
        if (pkg == packageName) {
            Log.v(TAG, "Ignore: our own app")
            return
        }
        if (pkg == "com.android.systemui") {
            Log.v(TAG, "Ignore: systemui")
            return
        }
        if (pkg == "com.android.launcher" || pkg == "com.android.launcher3") {
            Log.v(TAG, "Ignore: launcher")
            return
        }

        val prefs = Prefs(this)
        if (pkg in prefs.whitelist) {
            Log.d(TAG, "Ignore: $pkg is whitelisted")
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
            Log.d(TAG, "BlockActivity started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BlockActivity", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChillPill/A11y"
    }
}
