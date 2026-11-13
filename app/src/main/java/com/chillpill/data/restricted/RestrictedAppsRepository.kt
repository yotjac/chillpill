package com.chillpill.data.restricted

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.restrictedAppsDataStore: DataStore<Preferences> by preferencesDataStore(name = "restricted_apps")

/** Everything in the restricted-apps store, from one DataStore read (consistent version). */
data class RestrictedAppsSnapshot(
    val restricted: Set<String>,
    /** Per-app policies; null until the first Save after per-app modes (then legacy applies). */
    val storedOverrides: Map<String, AppPolicy>?,
    /** Legacy: apps with "Block again after grace" off. Only read while [storedOverrides] is null. */
    val legacyReInterventionDisabled: Set<String>
)

class RestrictedAppsRepository(private val context: Context) {

    private object Keys {
        val PACKAGE_NAMES = stringSetPreferencesKey("package_names")
        /** Legacy (before per-app modes); read for the fallback, no longer written. */
        val RE_INTERVENTION_DISABLED = stringSetPreferencesKey("re_intervention_disabled")
        /** JSON map package -> [AppPolicy] (see [AppPolicyCodec]); apps not in it get [AppPolicy.DEFAULT]. */
        val POLICY_OVERRIDES = stringPreferencesKey("policy_overrides")
    }

    val restrictedPackages: Flow<Set<String>> = context.restrictedAppsDataStore.data.map { prefs ->
        prefs[Keys.PACKAGE_NAMES] ?: emptySet()
    }

    val snapshot: Flow<RestrictedAppsSnapshot> = context.restrictedAppsDataStore.data.map { prefs ->
        RestrictedAppsSnapshot(
            restricted = prefs[Keys.PACKAGE_NAMES] ?: emptySet(),
            storedOverrides = AppPolicyCodec.decodeMap(prefs[Keys.POLICY_OVERRIDES]),
            legacyReInterventionDisabled = prefs[Keys.RE_INTERVENTION_DISABLED] ?: emptySet()
        )
    }

    suspend fun setRestricted(packageNames: Set<String>) {
        context.restrictedAppsDataStore.edit { it[Keys.PACKAGE_NAMES] = packageNames }
    }

    /** Restricted set and per-app policies in one write (Settings Save). */
    suspend fun setRestrictedWithOverrides(packageNames: Set<String>, overrides: Map<String, AppPolicy>) {
        context.restrictedAppsDataStore.edit { prefs ->
            prefs[Keys.PACKAGE_NAMES] = packageNames
            prefs[Keys.POLICY_OVERRIDES] = AppPolicyCodec.encodeMap(PolicyResolver.normalized(overrides, packageNames))
        }
    }

    suspend fun addRestricted(packageName: String) {
        context.restrictedAppsDataStore.edit { prefs ->
            val current = prefs[Keys.PACKAGE_NAMES] ?: emptySet()
            prefs[Keys.PACKAGE_NAMES] = current + packageName
        }
    }
}
