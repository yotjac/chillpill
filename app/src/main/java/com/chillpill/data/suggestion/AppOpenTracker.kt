package com.chillpill.data.suggestion

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tracker for which packages have had the suggestion dialog shown in the current
 * process / session. Used to avoid showing the suggestion popup multiple times in rapid
 * succession for the same app.
 *
 * Suggestion eligibility is determined by [UsageStatsManager] (foreground time in last 12 h).
 * This state is intentionally not persisted; if the process is killed, the flag resets.
 */
class AppOpenTracker {

    private val suggestionShownPackages: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    /**
     * Mark that a suggestion dialog has been shown for [packageName] in the current burst.
     * This prevents multiple dialogs in rapid succession for the same app.
     */
    fun markSuggestionShown(packageName: String) {
        suggestionShownPackages.add(packageName)
    }

    /**
     * Returns true if a suggestion has already been shown for [packageName] in the current
     * process lifetime / burst.
     */
    fun wasSuggestionShown(packageName: String): Boolean {
        return packageName in suggestionShownPackages
    }

    /**
     * Clear the in-memory "suggestion shown" flag for [packageName]. Typically called when
     * the user explicitly ignores or adds the app so future bursts can retrigger suggestions.
     */
    fun clearSuggestionShown(packageName: String) {
        suggestionShownPackages.remove(packageName)
    }
}
