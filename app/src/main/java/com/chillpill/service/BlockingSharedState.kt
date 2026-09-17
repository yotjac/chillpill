package com.chillpill.service

import android.os.SystemClock

/**
 * Shared in-memory state between ChillpillAccessibilityService and GracePeriodService.
 * - currentForegroundPackage: written by AccessibilityService on app transitions; read by GracePeriodService on expiry.
 * - sessions: per-package session/grace bookkeeping ([SessionPolicy]). Grace is fixed when the user passes the block
 *   (Continue / suggestion "Restrict"); leaving an app never extends it. Apps with re-intervention disabled may
 *   additionally return within [SessionPolicy.RETURN_WINDOW_MS] of leaving without a new block.
 */
object BlockingSharedState {

    @Volatile
    var currentForegroundPackage: String? = null
        private set

    /** Session + grace decisions; thread-safe. */
    val sessions = SessionPolicy(
        wallClock = System::currentTimeMillis,
        elapsedClock = SystemClock::elapsedRealtime
    )

    fun setCurrentForegroundPackage(packageName: String?) {
        currentForegroundPackage = packageName
    }

    /** True if the package is currently within the grace window that started at its last Continue. */
    fun isInGracePeriod(packageName: String): Boolean = sessions.isInGracePeriod(packageName)

    /** Clear grace for this package when its grace timer expires. The session itself stays (see [SessionPolicy]). */
    fun clearGraceForPackage(packageName: String) {
        sessions.clearGrace(packageName)
    }
}
