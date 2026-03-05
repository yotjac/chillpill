package com.chillpill.data.usage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_events")
data class UsageEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    val timestamp: Long,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    @ColumnInfo(name = "session_start_time")
    val sessionStartTime: Long? = null
)
