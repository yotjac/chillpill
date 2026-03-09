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
     * within [since, now]. Day bucket = timestamp / 86400000. Empty map if packageNames is empty.
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
}
