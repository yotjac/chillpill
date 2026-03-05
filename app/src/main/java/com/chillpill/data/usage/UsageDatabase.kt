package com.chillpill.data.usage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UsageEvent::class], version = 2, exportSchema = false)
abstract class UsageDatabase : RoomDatabase() {
    abstract fun usageEventsDao(): UsageEventsDao
}
