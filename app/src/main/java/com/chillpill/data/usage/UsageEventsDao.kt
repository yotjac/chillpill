package com.chillpill.data.usage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class EventCountRow(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    val count: Int
)

@Dao
interface UsageEventsDao {

    @Insert
    suspend fun insert(event: UsageEvent)

    @Query("SELECT * FROM usage_events WHERE package_name = :packageName AND timestamp >= :since ORDER BY timestamp DESC")
    fun getEventsSince(packageName: String, since: Long): Flow<List<UsageEvent>>

    @Query("SELECT COUNT(*) FROM usage_events WHERE package_name = :packageName AND timestamp >= :since AND event_type = :eventType")
    suspend fun countEventsSince(packageName: String, eventType: String, since: Long): Int

    @Query("""
        SELECT package_name, event_type, COUNT(*) as count
        FROM usage_events
        WHERE package_name IN (:packageNames) AND timestamp >= :since
        GROUP BY package_name, event_type
    """)
    suspend fun countEventsGrouped(packageNames: List<String>, since: Long): List<EventCountRow>
}
