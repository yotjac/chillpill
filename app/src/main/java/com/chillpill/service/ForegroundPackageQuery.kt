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
 *
 * The result carries the RESUME's timestamp: the session engine only believes a probe that is
 * newer than its last window event, and dates the switch to when it really happened.
 */
object ForegroundPackageQuery {

    private const val WINDOW_MS = 600_000L

    data class Foreground(val packageName: String, val resumedAtWallMs: Long)

    fun query(context: Context): Foreground? {
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
        var lastResumedAt = 0L
        var lastEventWasUnpausedResume = false
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            when (event.eventType) {
                resumeType -> {
                    lastResumedPkg = event.packageName
                    lastResumedAt = event.timeStamp
                    lastEventWasUnpausedResume = true
                }
                pauseType -> {
                    if (event.packageName == lastResumedPkg) {
                        lastEventWasUnpausedResume = false
                    }
                }
            }
        }
        val pkg = lastResumedPkg
        return if (lastEventWasUnpausedResume && pkg != null) Foreground(pkg, lastResumedAt) else null
    }
}
