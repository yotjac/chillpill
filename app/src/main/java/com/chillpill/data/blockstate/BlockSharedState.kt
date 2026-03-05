package com.chillpill.data.blockstate

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared state between ChillpillAccessibilityService and GracePeriodService:
 * - lastForegroundPackage: written by a11y on each window event; read by GracePeriodService on expiry.
 * - graceExpiredForPackage: written by GracePeriodService when grace expires and app is not in foreground; read and cleared by a11y when user enters that package.
 */
class BlockSharedState(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastForegroundPackage: String?
        get() = prefs.getString(KEY_LAST_FOREGROUND_PACKAGE, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_FOREGROUND_PACKAGE, value).apply()
        }

    var graceExpiredForPackage: String?
        get() = prefs.getString(KEY_GRACE_EXPIRED_FOR_PACKAGE, null)
        set(value) {
            prefs.edit().putString(KEY_GRACE_EXPIRED_FOR_PACKAGE, value).apply()
        }

    fun clearGraceExpiredForPackage() {
        prefs.edit().remove(KEY_GRACE_EXPIRED_FOR_PACKAGE).apply()
    }

    companion object {
        private const val PREFS_NAME = "chillpill_block_shared"
        private const val KEY_LAST_FOREGROUND_PACKAGE = "last_foreground_package"
        private const val KEY_GRACE_EXPIRED_FOR_PACKAGE = "grace_expired_for_package"
    }
}
