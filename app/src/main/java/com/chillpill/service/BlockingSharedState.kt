package com.chillpill.service

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared in-memory state between ChillpillAccessibilityService and GracePeriodService.
 * - currentForegroundPackage: written by AccessibilityService on app transitions; read by GracePeriodService on expiry.
 * - graceValidUntilMillis: "in grace" window per package; written when user leaves app or when grace timer starts (Continue); read to decide show block vs allow.
 * - graceExpiredForPackage: set by GracePeriodService when grace expires and app is not in foreground; read by AccessibilityService on next open.
 */
object BlockingSharedState {

    @Volatile
    var currentForegroundPackage: String? = null
        private set

    private val graceValidUntilMillis = ConcurrentHashMap<String, Long>()

    @Volatile
    var graceExpiredForPackage: String? = null
        private set

    fun setCurrentForegroundPackage(packageName: String?) {
        currentForegroundPackage = packageName
    }

    /** True if the package is currently within its grace window (recently left or post-Continue timer). */
    fun isInGracePeriod(packageName: String): Boolean {
        val validUntil = graceValidUntilMillis[packageName] ?: 0L
        val now = System.currentTimeMillis()
        val inGrace = validUntil > now
        if (validUntil != 0L) {
            Log.d(DEBUG_TAG, "isInGracePeriod: pkg=$packageName validUntil=$validUntil now=$now inGrace=$inGrace")
        }
        return inGrace
    }

    /** Set grace valid until this timestamp (e.g. when user leaves app or when Continue timer starts). */
    fun setGraceValidUntil(packageName: String, validUntilMillis: Long) {
        graceValidUntilMillis[packageName] = validUntilMillis
    }

    /** Clear grace for this package (e.g. when grace timer expires). */
    fun clearGraceForPackage(packageName: String) {
        graceValidUntilMillis.remove(packageName)
    }

    fun setGraceExpiredForPackage(packageName: String?) {
        graceExpiredForPackage = packageName
    }

    fun clearGraceExpiredForPackage(packageName: String) {
        if (graceExpiredForPackage == packageName) {
            graceExpiredForPackage = null
        }
    }

    private const val DEBUG_TAG = "BlockingSharedState"
}
