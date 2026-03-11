package com.chillpill.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val WAIT_TIME_SECONDS = intPreferencesKey("wait_time_seconds")
        val GRACE_PERIOD_MINUTES = intPreferencesKey("grace_period_minutes")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
    }

    val settings: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        Settings(
            waitTimeSeconds = prefs[Keys.WAIT_TIME_SECONDS] ?: 12,
            gracePeriodMinutes = prefs[Keys.GRACE_PERIOD_MINUTES] ?: 5
        )
    }

    val setupCompleted: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.SETUP_COMPLETED] ?: false
    }

    suspend fun setWaitTimeSeconds(seconds: Int) {
        context.settingsDataStore.edit { it[Keys.WAIT_TIME_SECONDS] = seconds }
    }

    suspend fun setGracePeriodMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.GRACE_PERIOD_MINUTES] = minutes }
    }

    suspend fun setSettings(settings: Settings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.WAIT_TIME_SECONDS] = settings.waitTimeSeconds
            prefs[Keys.GRACE_PERIOD_MINUTES] = settings.gracePeriodMinutes
        }
    }

    suspend fun setSetupCompleted(completed: Boolean) {
        context.settingsDataStore.edit { it[Keys.SETUP_COMPLETED] = completed }
    }
}
