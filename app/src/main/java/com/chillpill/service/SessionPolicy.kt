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

    /** Grace extensions granted per package in the current grace window (see [extendGrace]). */
    private val extensionsUsed = HashMap<String, Int>()

    /** User passed the block for [pkg]; grace runs for [graceMs] from now. */
    @Synchronized
    fun startSession(pkg: String, graceMs: Long) {
        graceValidUntil[pkg] = wallClock() + graceMs
        activeSessions.add(pkg)
        leftAtElapsed.remove(pkg)
        extensionsUsed.remove(pkg)
    }

    /** A block / re-intervention screen is being shown for [pkg]; nothing carries over. */
    @Synchronized
    fun endSession(pkg: String) {
        graceValidUntil.remove(pkg)
        activeSessions.remove(pkg)
        leftAtElapsed.remove(pkg)
        extensionsUsed.remove(pkg)
    }

    /** Grace timer ran out for [pkg]. The session itself stays (matters only for no-re-block apps). */
    @Synchronized
    fun clearGrace(pkg: String) {
        graceValidUntil.remove(pkg)
        extensionsUsed.remove(pkg)
    }

    @Synchronized
    fun isInGracePeriod(pkg: String): Boolean = (graceValidUntil[pkg] ?: 0L) > wallClock()

    /** Milliseconds left of [pkg]'s grace window; 0 when it has no grace window or it already ran out. */
    @Synchronized
    fun graceRemainingMs(pkg: String): Long {
        val until = graceValidUntil[pkg] ?: return 0L
        return (until - wallClock()).coerceAtLeast(0L)
    }

    /**
     * Epoch millis at which [pkg]'s grace runs out, or 0 when it has none. Read by the warning pill
     * so its countdown is driven by a value that only moves on an extension, not by a fresh
     * "now + remaining" every tick.
     */
    @Synchronized
    fun graceDeadlineMs(pkg: String): Long = graceValidUntil[pkg] ?: 0L

    /** True while [pkg] is in grace and has not spent all of its [MAX_GRACE_EXTENSIONS] extensions. */
    @Synchronized
    fun canExtendGrace(pkg: String): Boolean =
        isInGracePeriod(pkg) && (extensionsUsed[pkg] ?: 0) < MAX_GRACE_EXTENSIONS

    /**
     * Pushes [pkg]'s grace deadline back by [GRACE_EXTENSION_MS] once per grace window (the
     * warning pill's "+10 s"). Added to the *deadline*, not to "now", so tapping with 3 s left
     * leaves 13 s. Returns false and changes nothing when the extension is not available.
     *
     * The deadline is the only thing this touches: no session change, no exit timestamp.
     */
    @Synchronized
    fun extendGrace(pkg: String): Boolean {
        if (!canExtendGrace(pkg)) return false
        graceValidUntil[pkg] = (graceValidUntil[pkg] ?: return false) + GRACE_EXTENSION_MS
        extensionsUsed[pkg] = (extensionsUsed[pkg] ?: 0) + 1
        return true
    }

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

        /** The grace-expiry warning pill appears once this much of the grace window is left. */
        const val WARNING_LEAD_MS = 10_000L

        /**
         * Lead time for the *second* warning, the one shown after the extension has been spent. It
         * comes later than the first and cannot be dismissed: the user has already had a warning
         * and bought themselves extra time, so this one only has to say that time is up.
         */
        const val FINAL_WARNING_LEAD_MS = 5_000L

        /** How much one "+10 s" tap adds to the grace deadline. */
        const val GRACE_EXTENSION_MS = 10_000L

        /** Extensions allowed per grace window. */
        const val MAX_GRACE_EXTENSIONS = 1
    }
}
