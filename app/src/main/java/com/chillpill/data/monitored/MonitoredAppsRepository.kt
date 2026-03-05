package com.chillpill.data.monitored

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.monitoredAppsDataStore: DataStore<Preferences> by preferencesDataStore(name = "monitored_apps")

class MonitoredAppsRepository(private val context: Context) {

    private object Keys {
        val PACKAGE_NAMES = stringSetPreferencesKey("package_names")
    }

    val monitoredPackages: Flow<Set<String>> = context.monitoredAppsDataStore.data.map { prefs ->
        prefs[Keys.PACKAGE_NAMES] ?: emptySet()
    }

    suspend fun setMonitored(packageNames: Set<String>) {
        context.monitoredAppsDataStore.edit { it[Keys.PACKAGE_NAMES] = packageNames }
    }

    suspend fun add(packageName: String) {
        context.monitoredAppsDataStore.edit { prefs ->
            val current = prefs[Keys.PACKAGE_NAMES] ?: emptySet()
            prefs[Keys.PACKAGE_NAMES] = current + packageName
        }
    }

    suspend fun remove(packageName: String) {
        context.monitoredAppsDataStore.edit { prefs ->
            val current = prefs[Keys.PACKAGE_NAMES] ?: emptySet()
            prefs[Keys.PACKAGE_NAMES] = current - packageName
        }
    }
}
