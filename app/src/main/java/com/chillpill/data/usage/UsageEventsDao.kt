package com.chillpill.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageEventsDao {

    @Insert
    suspend fun insert(event: UsageEvent)

    @Query("SELECT * FROM usage_events WHERE package_name = :packageName AND timestamp >= :since ORDER BY timestamp DESC")
    fun getEventsSince(packageName: String, since: Long): Flow<List<UsageEvent>>

    @Query("SELECT COUNT(*) FROM usage_events WHERE package_name = :packageName AND timestamp >= :since AND event_type = :eventType")
    suspend fun countEventsSince(packageName: String, eventType: String, since: Long): Int
}
