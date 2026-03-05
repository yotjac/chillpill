package com.chillpill.service

/**
 * Shared in-memory state between ChillpillAccessibilityService and GracePeriodService.
 * - lastForegroundPackage: written by AccessibilityService on app transitions; read by GracePeriodService on expiry.
 * - graceExpiredForPackage: set by GracePeriodService when grace expires and app is not in foreground; read by AccessibilityService on next open.
 */
object BlockingSharedState {

    @Volatile
    var lastForegroundPackage: String? = null
        private set

    @Volatile
    var graceExpiredForPackage: String? = null
        private set

    fun setLastForegroundPackage(packageName: String?) {
        lastForegroundPackage = packageName
    }

    fun setGraceExpiredForPackage(packageName: String?) {
        graceExpiredForPackage = packageName
    }

    fun clearGraceExpiredForPackage(packageName: String) {
        if (graceExpiredForPackage == packageName) {
            graceExpiredForPackage = null
        }
    }
}
