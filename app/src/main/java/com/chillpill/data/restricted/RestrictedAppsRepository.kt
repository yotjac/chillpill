package com.chillpill.data.restricted

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.restrictedAppsDataStore: DataStore<Preferences> by preferencesDataStore(name = "restricted_apps")

class RestrictedAppsRepository(private val context: Context) {

    private object Keys {
        val PACKAGE_NAMES = stringSetPreferencesKey("package_names")
    }

    val restrictedPackages: Flow<Set<String>> = context.restrictedAppsDataStore.data.map { prefs ->
        prefs[Keys.PACKAGE_NAMES] ?: emptySet()
    }

    suspend fun setRestricted(packageNames: Set<String>) {
        context.restrictedAppsDataStore.edit { it[Keys.PACKAGE_NAMES] = packageNames }
    }

}
