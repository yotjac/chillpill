package com.chillpill

import android.app.Application
import androidx.room.Room
import com.chillpill.data.blockstate.BlockSharedState
import com.chillpill.data.settings.SettingsRepository
import com.chillpill.data.restricted.RestrictedAppsRepository
import com.chillpill.data.usage.UsageDatabase
import com.chillpill.data.usage.UsageEventsRepository

class ChillpillApp : Application() {

    val blockSharedState: BlockSharedState by lazy { BlockSharedState(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val restrictedAppsRepository: RestrictedAppsRepository by lazy { RestrictedAppsRepository(this) }
    val usageEventsRepository: UsageEventsRepository by lazy {
        UsageEventsRepository(
            Room.databaseBuilder(this, UsageDatabase::class.java, "chillpill_usage")
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build()
        )
    }
}
