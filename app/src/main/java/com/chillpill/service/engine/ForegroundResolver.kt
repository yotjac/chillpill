package com.chillpill.service.engine

/**
 * Which package is in the foreground according to UsageStats activity events. Pure; fed by
 * [com.chillpill.service.ForegroundPackageQuery], which owns the Android query.
 *
 * Resumed state is tracked **per activity** (package + class): an activity is resumed from its
 * ACTIVITY_RESUMED until its own ACTIVITY_PAUSED / ACTIVITY_STOPPED. Tracking only "the last
 * resumed package" is wrong on real devices: Android briefly resumes the launcher under the app in
 * front (RESUMED -> PAUSED -> STOPPED within a second) without ever pausing that app. The last
 * resume is then the launcher's, it is paused, and the app that never left looked like "nothing in
 * the foreground" (the launcher-blip bug, specs/session-engine.md "Amendment 2026-10-04").
 *
 * The resolver is incremental so that an activity resumed hours ago stays known: each query only
 * adds the events since the previous one ([beginQuery] / [endQuery]). When continuity is lost (no
 * query for longer than [windowMs], or the wall clock went backwards) it starts over from the last
 * [windowMs], which also bounds any drift.
 *
 * Not thread-safe; the caller serialises queries. All times are wall-clock milliseconds, as in
 * `UsageEvents.Event.getTimeStamp()`. UsageStats keeps its own wall clock, which may differ from
 * `System.currentTimeMillis()` by a couple of seconds (it only re-syncs beyond 2 s; seen on the
 * device), hence [CLOCK_SLACK_MS]: queries overlap by that much and read that far "ahead".
 */
class ForegroundResolver(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val overlapMs: Long = CLOCK_SLACK_MS
) {
    enum class Kind { RESUMED, PAUSED, STOPPED }

    data class Snapshot(
        val pkg: String,
        val resumedAtWallMs: Long,
        /** No other package has a resumed activity (false in split screen / freeform). */
        val onlyResumed: Boolean
    )

    private class Entry(val pkg: String, val resumedAt: Long)

    /** Resumed activities by "package/class", in the order they were (last) resumed. */
    private val resumed = LinkedHashMap<String, Entry>()

    /** End of the range covered so far; null before the first query. */
    private var cursor: Long? = null

    /**
     * Start of the range the caller must now feed through [onEvent], in order. Events in the
     * overlap are applied a second time; that is harmless, because the state of an activity only
     * depends on its last event and the overlap is the tail of the log.
     */
    fun beginQuery(nowWallMs: Long): Long {
        val c = cursor
        if (c == null || nowWallMs < c || nowWallMs - c > windowMs) {
            resumed.clear()
            cursor = null
            return nowWallMs - windowMs
        }
        return c - overlapMs
    }

    fun onEvent(kind: Kind, pkg: String, className: String?, timeWallMs: Long) {
        val key = "$pkg/${className.orEmpty()}"
        resumed.remove(key) // an unmatched PAUSED / STOPPED removes nothing
        if (kind == Kind.RESUMED) resumed[key] = Entry(pkg, timeWallMs)
    }

    /** Closes the query that [beginQuery] opened and returns the result as of [nowWallMs]. */
    fun endQuery(nowWallMs: Long): Snapshot? {
        cursor = nowWallMs
        return snapshot()
    }

    /** The most recently resumed activity that is still resumed; null when there is none. */
    fun snapshot(): Snapshot? {
        var top: Entry? = null
        for (e in resumed.values) {
            // ">=": on equal timestamps the later event wins (map order is event order).
            if (top == null || e.resumedAt >= top.resumedAt) top = e
        }
        val t = top ?: return null
        return Snapshot(t.pkg, t.resumedAt, onlyResumed = resumed.values.all { it.pkg == t.pkg })
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 600_000L
        /** Generous bound on how far UsageStats' timestamps may be off the system wall clock. */
        const val CLOCK_SLACK_MS = 10_000L
    }
}
