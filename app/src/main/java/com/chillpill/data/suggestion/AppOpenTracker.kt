package com.chillpill.data.suggestion

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tracker for how often each package has been brought to the foreground recently.
 *
 * The AccessibilityService records each foreground transition; this tracker maintains a
 * sliding 5-hour window of timestamps per package and provides a cheap count for that window.
 *
 * This state is intentionally not persisted. If the process is killed and restarted, the
 * suggestion counters reset and the user will simply need to cross the threshold again.
 */
class AppOpenTracker {

    private val timestampsByPackage: ConcurrentHashMap<String, MutableList<Long>> =
        ConcurrentHashMap()

    private val suggestionShownPackages: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    /**
     * Record that [packageName] has just been opened now.
     */
    fun recordOpen(packageName: String) {
        val now = System.currentTimeMillis()
        val list = timestampsByPackage.getOrPut(packageName) {
            Collections.synchronizedList(mutableListOf())
        }
        synchronized(list) {
            list.add(now)
            pruneOldEntriesLocked(list, now)
        }
    }

    /**
     * Returns the number of times [packageName] has been opened in the last [WINDOW_MS].
     */
    fun getRecentOpenCount(packageName: String): Int {
        val now = System.currentTimeMillis()
        val list = timestampsByPackage[packageName] ?: return 0
        synchronized(list) {
            pruneOldEntriesLocked(list, now)
            return list.size
        }
    }

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

    private fun pruneOldEntriesLocked(list: MutableList<Long>, now: Long) {
        val cutoff = now - WINDOW_MS
        // Remove from the front while entries are older than cutoff.
        var index = 0
        while (index < list.size && list[index] < cutoff) {
            index++
        }
        if (index > 0) {
            for (i in 0 until index) {
                list.removeAt(0)
            }
        }
    }

    companion object {
        private const val WINDOW_MS: Long = 5L * 60L * 60L * 1000L // 5 hours
    }
}

