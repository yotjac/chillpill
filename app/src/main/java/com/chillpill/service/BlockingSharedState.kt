package com.chillpill.service

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared in-memory state between ChillpillAccessibilityService and GracePeriodService.
 * - currentForegroundPackage: written by AccessibilityService on app transitions; read by GracePeriodService on expiry.
 * - graceValidUntilMillis: "in grace" window per package; written when user leaves app or when grace timer starts (Continue).
 * - graceExpiredForPackages: set of packages whose grace expired while not in the foreground (GracePeriodService adds;
 *   AccessibilityService removes on next open of that package). A set rather than a single field so that grace expiring
 *   while-away for one restricted app doesn't clobber the same bookkeeping for another restricted app.
 */
object BlockingSharedState {

    @Volatile
    var currentForegroundPackage: String? = null
        private set

    private val graceValidUntilMillis = ConcurrentHashMap<String, Long>()

    private val graceExpiredForPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    /** Mark [packageName]'s grace as having expired while the user was away from it. */
    fun setGraceExpiredForPackage(packageName: String) {
        graceExpiredForPackages.add(packageName)
    }

    /** True if [packageName]'s grace previously expired while away and hasn't been acknowledged yet. */
    fun isGraceExpiredForPackage(packageName: String): Boolean = packageName in graceExpiredForPackages

    /** Acknowledge/clear the "grace expired while away" flag for this package only. */
    fun clearGraceExpiredForPackage(packageName: String) {
        graceExpiredForPackages.remove(packageName)
    }

    private const val DEBUG_TAG = "BlockingSharedState"
}
