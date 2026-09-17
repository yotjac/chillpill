package com.chillpill.service

/**
 * Pure (Android-free) per-package session logic deciding whether entering a restricted app shows
 * the block screen. See specs/no-reblock-apps-session-fix.md.
 *
 * - A **session** starts when the user passes the block ([startSession]: "Continue", or "Restrict"
 *   in the suggestion popup) and ends the moment a block / re-intervention screen is shown
 *   ([endSession]).
 * - The **grace window** is fixed at session start (`now + grace`). Leaving the app never extends it.
 * - For apps with re-intervention disabled ("no-re-block" apps) the session outlives the grace
 *   window for as long as the user stays in the app; leaving for less than [RETURN_WINDOW_MS]
 *   keeps it alive, anything longer ends it on the next entry.
 *
 * @param wallClock   epoch millis; used for the grace deadline.
 * @param elapsedClock monotonic millis that keeps counting during device sleep; used for the return window.
 */
class SessionPolicy(
    private val wallClock: () -> Long,
    private val elapsedClock: () -> Long
) {
    enum class Decision { ALLOW, BLOCK }

    private val graceValidUntil = HashMap<String, Long>()
    private val activeSessions = HashSet<String>()
    private val leftAtElapsed = HashMap<String, Long>()

    /** User passed the block for [pkg]; grace runs for [graceMs] from now. */
    @Synchronized
    fun startSession(pkg: String, graceMs: Long) {
        graceValidUntil[pkg] = wallClock() + graceMs
        activeSessions.add(pkg)
        leftAtElapsed.remove(pkg)
    }

    /** A block / re-intervention screen is being shown for [pkg]; nothing carries over. */
    @Synchronized
    fun endSession(pkg: String) {
        graceValidUntil.remove(pkg)
        activeSessions.remove(pkg)
        leftAtElapsed.remove(pkg)
    }

    /** Grace timer ran out for [pkg]. The session itself stays (matters only for no-re-block apps). */
    @Synchronized
    fun clearGrace(pkg: String) {
        graceValidUntil.remove(pkg)
    }

    @Synchronized
    fun isInGracePeriod(pkg: String): Boolean = (graceValidUntil[pkg] ?: 0L) > wallClock()

    /** True while the user is legitimately inside [pkg] (passed the block, not blocked since). */
    @Synchronized
    fun hasActiveSession(pkg: String): Boolean = pkg in activeSessions

    /** User moved from [pkg] to another app (or the screen turned off). Keeps the earliest unanswered exit. */
    @Synchronized
    fun onLeft(pkg: String) {
        if (pkg in activeSessions && pkg !in leftAtElapsed) {
            leftAtElapsed[pkg] = elapsedClock()
        }
    }

    /**
     * User is entering restricted [pkg] from somewhere else. On [Decision.BLOCK] the session is
     * already ended; the caller only has to show the block screen.
     */
    @Synchronized
    fun onEnterFromElsewhere(pkg: String, reInterventionDisabled: Boolean): Decision {
        val leftAt = leftAtElapsed[pkg]
        val allow = isInGracePeriod(pkg) ||
            (reInterventionDisabled &&
                pkg in activeSessions &&
                leftAt != null &&
                elapsedClock() - leftAt < RETURN_WINDOW_MS)
        return if (allow) {
            leftAtElapsed.remove(pkg)
            Decision.ALLOW
        } else {
            endSession(pkg)
            Decision.BLOCK
        }
    }

    companion object {
        /** No-re-block apps: leaving for less than this never re-triggers the block screen. */
        const val RETURN_WINDOW_MS = 10_000L
    }
}
