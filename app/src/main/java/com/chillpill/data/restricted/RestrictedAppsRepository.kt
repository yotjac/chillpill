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

data class RestrictedAppsSnapshot(
    val restricted: Set<String>,
    val reInterventionDisabled: Set<String>
)

class RestrictedAppsRepository(private val context: Context) {

    private object Keys {
        val PACKAGE_NAMES = stringSetPreferencesKey("package_names")
        val RE_INTERVENTION_DISABLED = stringSetPreferencesKey("re_intervention_disabled")
    }

    val restrictedPackages: Flow<Set<String>> = context.restrictedAppsDataStore.data.map { prefs ->
        prefs[Keys.PACKAGE_NAMES] ?: emptySet()
    }

    val reInterventionDisabledPackages: Flow<Set<String>> = context.restrictedAppsDataStore.data.map { prefs ->
        prefs[Keys.RE_INTERVENTION_DISABLED] ?: emptySet()
    }

    /** Both sets from one DataStore read, so a caller that needs both sees a consistent version. */
    val snapshot: Flow<RestrictedAppsSnapshot> = context.restrictedAppsDataStore.data.map { prefs ->
        RestrictedAppsSnapshot(
            restricted = prefs[Keys.PACKAGE_NAMES] ?: emptySet(),
            reInterventionDisabled = prefs[Keys.RE_INTERVENTION_DISABLED] ?: emptySet()
        )
    }

    suspend fun setRestricted(packageNames: Set<String>) {
        context.restrictedAppsDataStore.edit { it[Keys.PACKAGE_NAMES] = packageNames }
    }

    suspend fun addRestricted(packageName: String) {
        context.restrictedAppsDataStore.edit { prefs ->
            val current = prefs[Keys.PACKAGE_NAMES] ?: emptySet()
            prefs[Keys.PACKAGE_NAMES] = current + packageName
        }
    }

    suspend fun setReInterventionDisabled(packageNames: Set<String>) {
        context.restrictedAppsDataStore.edit { it[Keys.RE_INTERVENTION_DISABLED] = packageNames }
    }

}
