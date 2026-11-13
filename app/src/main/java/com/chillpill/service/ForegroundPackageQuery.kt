package com.chillpill.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.chillpill.service.engine.ForegroundResolver

/**
 * Best-effort lookup of the package currently in the foreground, using UsageStatsManager
 * (needs usage-access permission). Returns null when no activity is known to be resumed: a
 * package the user has locked the screen on, gone home from, or switched away from is *not*
 * reported.
 *
 * The decision is made by the pure [ForegroundResolver], which tracks resumed state per activity
 * and across queries; this object only reads the events. One resolver is shared by all probes, so
 * queries are serialised (probes run on Dispatchers.IO and can overlap).
 *
 * The result carries the RESUME's timestamp and whether it is the only resumed package: the
 * session engine believes a probe that is newer than its last window event, or one that positively
 * contradicts it (EngineCore P6 / P6b).
 */
object ForegroundPackageQuery {

    data class Foreground(val packageName: String, val resumedAtWallMs: Long, val onlyResumed: Boolean)

    private val resolver = ForegroundResolver()

    @Synchronized
    fun query(context: Context): Foreground? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        // The end lies in the future on purpose: UsageStats' clock can run ahead of ours, and an
        // event it stamped "later than now" must not be missed (it would make the answer stale).
        val usageEvents = usm.queryEvents(resolver.beginQuery(now), now + ForegroundResolver.CLOCK_SLACK_MS)
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val kind = kindOf(event.eventType) ?: continue
            val pkg = event.packageName ?: continue
            resolver.onEvent(kind, pkg, event.className, event.timeStamp)
        }
        return resolver.endQuery(now)?.let { Foreground(it.pkg, it.resumedAtWallMs, it.onlyResumed) }
    }

    private fun kindOf(eventType: Int): ForegroundResolver.Kind? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundResolver.Kind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundResolver.Kind.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundResolver.Kind.STOPPED
                else -> null
            }
        } else {
            @Suppress("DEPRECATION")
            when (eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> ForegroundResolver.Kind.RESUMED
                UsageEvents.Event.MOVE_TO_BACKGROUND -> ForegroundResolver.Kind.PAUSED
                else -> null
            }
        }
}
