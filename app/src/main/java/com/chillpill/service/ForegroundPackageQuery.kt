package com.chillpill.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * Best-effort lookup of the package currently in the foreground, using UsageStatsManager
 * (needs usage-access permission). Returns null unless the most recent relevant event in the
 * window is an unmatched RESUME, i.e. a package is only reported as foreground when nothing has
 * paused it since. A package the user has locked the screen on, gone home from, or switched
 * away from is therefore *not* reported.
 */
object ForegroundPackageQuery {

    private const val WINDOW_MS = 600_000L

    fun query(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val usageEvents = usm.queryEvents(now - WINDOW_MS, now)
        val event = UsageEvents.Event()
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
        var lastResumedPkg: String? = null
        var lastEventWasUnpausedResume = false
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            when (event.eventType) {
                resumeType -> {
                    lastResumedPkg = event.packageName
                    lastEventWasUnpausedResume = true
                }
                pauseType -> {
                    if (event.packageName == lastResumedPkg) {
                        lastEventWasUnpausedResume = false
                    }
                }
            }
        }
        return if (lastEventWasUnpausedResume) lastResumedPkg else null
    }
}
