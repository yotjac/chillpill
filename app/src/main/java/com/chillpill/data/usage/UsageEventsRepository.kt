package com.chillpill.data.usage

import kotlinx.coroutines.flow.Flow

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
}
