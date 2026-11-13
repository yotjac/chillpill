package com.chillpill.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chillpill.data.restricted.PolicyResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        /** Legacy (before per-app modes): read for the fallback in [PolicyResolver], no longer written. */
        val WAIT_TIME_SECONDS = intPreferencesKey("wait_time_seconds")
        val GRACE_PERIOD_MINUTES = intPreferencesKey("grace_period_minutes")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val BLOCK_BACKGROUND = stringPreferencesKey("block_background")
        val CONFIRMATION_WAIT_SECONDS = intPreferencesKey("confirmation_wait_seconds")
    }

    val settings: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        val legacyWait = prefs[Keys.WAIT_TIME_SECONDS]
        Settings(
            confirmationWaitSeconds = PolicyResolver.confirmationWaitSeconds(
                stored = prefs[Keys.CONFIRMATION_WAIT_SECONDS],
                legacyWaitSeconds = legacyWait,
                setupCompleted = prefs[Keys.SETUP_COMPLETED] ?: false
            ),
            blockBackground = BlockBackground.decode(prefs[Keys.BLOCK_BACKGROUND]),
            legacyWaitSeconds = legacyWait,
            legacyGraceMinutes = prefs[Keys.GRACE_PERIOD_MINUTES]
        )
    }

    val setupCompleted: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.SETUP_COMPLETED] ?: false
    }

    suspend fun setBlockBackground(background: BlockBackground) {
        context.settingsDataStore.edit { it[Keys.BLOCK_BACKGROUND] = BlockBackground.encode(background) }
    }

    suspend fun setSettings(settings: Settings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CONFIRMATION_WAIT_SECONDS] = settings.confirmationWaitSeconds
            prefs[Keys.BLOCK_BACKGROUND] = BlockBackground.encode(settings.blockBackground)
        }
    }

    suspend fun setSetupCompleted(completed: Boolean) {
        context.settingsDataStore.edit { it[Keys.SETUP_COMPLETED] = completed }
    }
}
