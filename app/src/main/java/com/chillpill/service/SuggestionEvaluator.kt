package com.chillpill.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.chillpill.ChillpillApp
import com.chillpill.data.suggestion.ExcludedApps
import com.chillpill.service.engine.SuggestionAnswer

/**
 * The I/O half of the "restrict this app?" suggestion. The engine decides *when* to ask
 * (a non-restricted app came to the front) and whether the popup may still show; this decides
 * *whether* the app qualifies and persists the answer.
 *
 * Qualifies: not hard-excluded, has a launcher activity, not asked yet in this process, used for
 * at least 30 min in total over the last 12 h (cumulative, not one sitting), and neither ignored
 * (7 days) nor permanently excluded.
 */
class SuggestionEvaluator(private val app: ChillpillApp) {

    /** App label if the popup should be offered for [pkg], otherwise null. Call off the main thread. */
    suspend fun evaluate(pkg: String): String? {
        try {
            if (pkg in ExcludedApps.EXCLUDED_PACKAGES) return null
            if (app.packageManager.getLaunchIntentForPackage(pkg) == null) return null
            if (app.appOpenTracker.wasSuggestionShown(pkg)) return null
            val minutes = queryForegroundTimeMs(pkg, WINDOW_MS) / 60_000L
            if (minutes < THRESHOLD_MINUTES) return null
            if (app.suggestionRepository.isIgnored(pkg)) return null
            if (app.suggestionRepository.isPermanentlyExcluded(pkg)) return null
            Log.d(TAG, "evaluate: pkg=$pkg qualifies (${minutes} min in the last 12 h)")
            app.appOpenTracker.markSuggestionShown(pkg)
            return resolveAppName(pkg)
        } catch (t: Throwable) {
            Log.e(TAG, "evaluate failed pkg=$pkg", t)
            return null
        }
    }

    suspend fun persist(pkg: String, answer: SuggestionAnswer) {
        try {
            when (answer) {
                SuggestionAnswer.RESTRICT -> {
                    app.restrictedAppsRepository.addRestricted(pkg)
                    app.suggestionRepository.removeIgnored(pkg)
                }
                SuggestionAnswer.IGNORE -> app.suggestionRepository.markIgnored(pkg)
                SuggestionAnswer.NEVER -> app.suggestionRepository.addPermanentlyExcluded(pkg)
            }
            app.appOpenTracker.clearSuggestionShown(pkg)
        } catch (t: Throwable) {
            Log.e(TAG, "persist failed pkg=$pkg answer=$answer", t)
        }
    }

    private fun queryForegroundTimeMs(pkg: String, windowMs: Long): Long {
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - windowMs, now)
        val event = UsageEvents.Event()
        var totalMs = 0L
        var lastResumed = -1L
        val resumeType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val pauseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != pkg) continue
            when (event.eventType) {
                resumeType -> lastResumed = event.timeStamp
                pauseType -> if (lastResumed > 0) {
                    totalMs += event.timeStamp - lastResumed
                    lastResumed = -1L
                }
            }
        }
        if (lastResumed > 0) totalMs += now - lastResumed
        return totalMs
    }

    private fun resolveAppName(pkg: String): String = try {
        val info = app.packageManager.getApplicationInfo(pkg, 0)
        app.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        pkg
    }

    private companion object {
        const val TAG = "SuggestionEvaluator"
        const val WINDOW_MS = 12L * 60L * 60L * 1000L
        const val THRESHOLD_MINUTES = 30L
    }
}
