package com.chillpill.data.usage

import kotlinx.coroutines.flow.Flow

data class AppStats(
    val openAttempts: Int,
    val continueCount: Int
)

data class DayStats(
    val dayBucket: Long,
    val attempts: Int,
    val entered: Int
)

object UsageEventType {
    const val OPEN_ATTEMPT = "OPEN_ATTEMPT"
    const val WAIT_COMPLETED = "WAIT_COMPLETED"
    const val CONTINUED = "CONTINUED"
    const val LEFT_APP = "LEFT_APP"
    const val GRACE_EXPIRED_WHILE_ACTIVE = "GRACE_EXPIRED_WHILE_ACTIVE"
    const val GRACE_EXPIRED_WHILE_AWAY = "GRACE_EXPIRED_WHILE_AWAY"

    /** User bought ten more seconds from the grace-expiry warning pill. */
    const val GRACE_EXTENDED = "GRACE_EXTENDED"
}

class UsageEventsRepository(private val database: UsageDatabase) {

    private val dao: UsageEventsDao get() = database.usageEventsDao()

    suspend fun recordEvent(packageName: String, eventType: String, sessionStartTime: Long? = null) {
        dao.insert(
            UsageEvent(
                packageName = packageName,
                timestamp = System.currentTimeMillis(),
                eventType = eventType,
                sessionStartTime = sessionStartTime
            )
        )
    }

    fun getEventsInLast24h(packageName: String): Flow<List<UsageEvent>> {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        return dao.getEventsSince(packageName, since)
    }

    suspend fun getOpenCountLast24h(packageName: String): Int {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        return dao.countEventsSince(packageName, UsageEventType.OPEN_ATTEMPT, since)
    }

    /** Count of OPEN_ATTEMPT in last 24h (times app was opened and intercepted). */
    suspend fun getOpenInterceptCountLast24h(packageName: String): Int {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        return dao.countEventsSince(packageName, UsageEventType.OPEN_ATTEMPT, since)
    }

    /** Count of WAIT_COMPLETED in last 24h (times user waited and continued to app). */
    suspend fun getWaitCompletedCountLast24h(packageName: String): Int {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        return dao.countEventsSince(packageName, UsageEventType.WAIT_COMPLETED, since)
    }

    /**
     * Returns per-package stats (open attempts and continue count) for the given packages
     * within the time range [since, now]. Empty map if packageNames is empty.
     */
    suspend fun getStatsForPackages(packageNames: Set<String>, since: Long): Map<String, AppStats> {
        if (packageNames.isEmpty()) return emptyMap()
        val rows = dao.countEventsGrouped(packageNames.toList(), since)
        val map = mutableMapOf<String, AppStats>()
        for (pkg in packageNames) {
            map[pkg] = AppStats(openAttempts = 0, continueCount = 0)
        }
        for (row in rows) {
            val current = map[row.packageName] ?: AppStats(0, 0)
            val updated = when (row.eventType) {
                UsageEventType.OPEN_ATTEMPT -> current.copy(openAttempts = row.count)
                UsageEventType.WAIT_COMPLETED -> current.copy(continueCount = row.count)
                else -> current
            }
            map[row.packageName] = updated
        }
        return map
    }

    /**
     * Returns per-package daily stats (attempts and entered per day bucket) for the given packages
     * within [since, now]. Day bucket = timestamp / 86400000 (UTC). Empty map if packageNames is empty.
     */
    suspend fun getDailyStatsForPackages(packageNames: Set<String>, since: Long): Map<String, List<DayStats>> {
        if (packageNames.isEmpty()) return emptyMap()

        val rows = dao.countEventsGroupedByDay(packageNames.toList(), since)
        val byPackage = mutableMapOf<String, MutableMap<Long, DayStats>>()

        for (row in rows) {
            val dayMap = byPackage.getOrPut(row.packageName) { mutableMapOf() }
            val current = dayMap[row.dayBucket] ?: DayStats(row.dayBucket, 0, 0)

            val updated = when (row.eventType) {
                UsageEventType.OPEN_ATTEMPT -> current.copy(attempts = row.count)
                UsageEventType.WAIT_COMPLETED -> current.copy(entered = row.count)
                else -> current
            }
            dayMap[row.dayBucket] = updated
        }
        
        return byPackage.mapValues { (_, dayMap) ->
            dayMap.values.sortedBy { it.dayBucket }
        }
    }

    /**
     * Returns per-package daily stats for the given packages, one [DayStats] per (since, until) range.
     * Each range is typically one local calendar day [startOfDayMs, startOfNextDayMs).
     * The i-th element in each list has dayBucket = i (used as index; last index = "today" for week view).
     * Empty map if packageNames or dayRanges is empty.
     */
    suspend fun getDailyStatsForPackagesInRanges(
        packageNames: Set<String>,
        dayRanges: List<Pair<Long, Long>>
    ): Map<String, List<DayStats>> {
        if (packageNames.isEmpty() || dayRanges.isEmpty()) return emptyMap()
        val packagesList = packageNames.toList()
        val result = mutableMapOf<String, MutableList<DayStats>>()
        for (pkg in packageNames) {
            result[pkg] = MutableList(dayRanges.size) { DayStats(it.toLong(), 0, 0) }
        }
        for ((index, range) in dayRanges.withIndex()) {
            val (since, until) = range
            val rows = dao.countEventsGroupedInRange(packagesList, since, until)
            for (row in rows) {
                val list = result[row.packageName] ?: continue
                val current = list[index]
                val updated = when (row.eventType) {
                    UsageEventType.OPEN_ATTEMPT -> current.copy(attempts = row.count)
                    UsageEventType.WAIT_COMPLETED -> current.copy(entered = row.count)
                    else -> current
                }
                list[index] = updated
            }
        }
        return result
    }
}
