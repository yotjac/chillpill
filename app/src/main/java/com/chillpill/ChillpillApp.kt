package com.chillpill

import android.app.Application
import androidx.room.Room
import com.chillpill.data.settings.BlockBackgroundStore
import com.chillpill.data.settings.SettingsRepository
import com.chillpill.data.restricted.RestrictedAppsRepository
import com.chillpill.data.suggestion.AppOpenTracker
import com.chillpill.data.suggestion.SuggestionRepository
import com.chillpill.data.usage.UsageDatabase
import com.chillpill.data.usage.UsageEventsRepository
import com.chillpill.service.engine.SessionEngine

class ChillpillApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val blockBackgroundStore: BlockBackgroundStore by lazy { BlockBackgroundStore(this) }
    val restrictedAppsRepository: RestrictedAppsRepository by lazy { RestrictedAppsRepository(this) }
    val appOpenTracker: AppOpenTracker by lazy { AppOpenTracker() }
    val suggestionRepository: SuggestionRepository by lazy { SuggestionRepository(this) }
    val usageEventsRepository: UsageEventsRepository by lazy {
        UsageEventsRepository(
            Room.databaseBuilder(this, UsageDatabase::class.java, "chillpill_usage")
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build()
        )
    }

    /** The blocking pipeline's single owner of state (specs/session-engine.md). */
    val sessionEngine: SessionEngine by lazy { SessionEngine(this) }
}
