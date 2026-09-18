package com.chillpill.service

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared in-memory state between ChillpillAccessibilityService and GracePeriodService.
 * - currentForegroundPackage: written by AccessibilityService on app transitions; read by GracePeriodService on expiry.
 * - sessions: per-package session/grace bookkeeping ([SessionPolicy]). Grace is fixed when the user passes the block
 *   (Continue / suggestion "Restrict"); leaving an app never extends it. Apps with re-intervention disabled may
 *   additionally return within [SessionPolicy.RETURN_WINDOW_MS] of leaving without a new block.
 * - graceWarning: the only channel for the grace-expiry warning pill. GracePeriodService decides *when*
 *   a warning is due; ChillpillAccessibilityService owns the window and decides whether it is visible
 *   (see specs/grace-expiry-warning.md).
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

    /**
     * A restricted app whose grace period is about to run out with re-intervention enabled, i.e. the
     * user is about to be kicked out. Written only by [GracePeriodService].
     *
     * @param deadlineWallMs epoch millis at which grace runs out; moves when the user taps "+10 s".
     * @param canExtend whether the pill should still offer the extension.
     * @param dismissible false for the second warning, the one shown after the extension has been
     *   spent: it has no dismiss button and overrides a pill the user tapped away earlier.
     */
    data class GraceWarning(
        val packageName: String,
        val deadlineWallMs: Long,
        val canExtend: Boolean,
        val dismissible: Boolean
    )

    private val _graceWarning = MutableStateFlow<GraceWarning?>(null)

    /** Null while no warning is due. Collected by the accessibility service, which owns the pill window. */
    val graceWarning: StateFlow<GraceWarning?> = _graceWarning.asStateFlow()

    /** Only [GracePeriodService] calls this. */
    fun setGraceWarning(warning: GraceWarning?) {
        _graceWarning.value = warning
    }

    /**
     * The pill's "+10 s": pushes the grace deadline back once per grace window. Returns false when
     * the extension was already used or grace is over. Touches nothing but the deadline.
     */
    fun extendGrace(packageName: String): Boolean = sessions.extendGrace(packageName)

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
