package com.chillpill.data.suggestion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

private val Context.suggestionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "suggestion_prefs"
)

/**
 * Stores which packages the user has chosen to ignore suggestion popups for, along with
 * an expiry timestamp. Used to avoid nagging the user repeatedly for the same app.
 */
class SuggestionRepository(
    private val context: Context,
    private val gson: Gson = Gson()
    ) {

    private object Keys {
        val IGNORED_PACKAGES = stringPreferencesKey("ignored_packages")
        val PERMANENTLY_EXCLUDED = stringSetPreferencesKey("permanently_excluded_packages")
    }

    /**
     * Returns true if the user has chosen to ignore suggestions for [packageName] and the
     * ignore window has not yet expired.
     */
    suspend fun isIgnored(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val map = loadIgnoredMap()
        val expiry = map[packageName] ?: return@withContext false
        if (expiry <= now) {
            // Expired; clean up eagerly.
            val mutable = map.toMutableMap()
            mutable.remove(packageName)
            saveIgnoredMap(mutable)
            return@withContext false
        }
        true
    }

    /**
     * Mark [packageName] as ignored for the next [IGNORE_WINDOW_MS].
     */
    suspend fun markIgnored(packageName: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val map = loadIgnoredMap().toMutableMap()
        map[packageName] = now + IGNORE_WINDOW_MS
        saveIgnoredMap(map)
    }

    /**
     * Remove [packageName] from the ignored set (e.g. when the app is added to restricted
     * apps, so we no longer need to track its ignore state).
     */
    suspend fun removeIgnored(packageName: String) = withContext(Dispatchers.IO) {
        val map = loadIgnoredMap().toMutableMap()
        if (map.remove(packageName) != null) {
            saveIgnoredMap(map)
        }
    }

    /**
     * Returns true if [packageName] has been permanently excluded via the \"Never ask me again\"
     * action. This is independent of the hardcoded [ExcludedApps] list.
     */
    suspend fun isPermanentlyExcluded(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.suggestionDataStore.data.firstOrNull()
        val set = prefs?.get(Keys.PERMANENTLY_EXCLUDED) ?: emptySet()
        packageName in set
    }

    /**
     * Adds [packageName] to the permanently excluded set so we never show a suggestion popup
     * for it again (unless app data is cleared).
     */
    suspend fun addPermanentlyExcluded(packageName: String) = withContext(Dispatchers.IO) {
        context.suggestionDataStore.edit { prefs ->
            val current = prefs[Keys.PERMANENTLY_EXCLUDED] ?: emptySet()
            prefs[Keys.PERMANENTLY_EXCLUDED] = current + packageName
        }
    }

    private suspend fun loadIgnoredMap(): Map<String, Long> {
        return try {
            val prefs = context.suggestionDataStore.data.firstOrNull()
            val json = prefs?.get(Keys.IGNORED_PACKAGES) ?: return emptyMap()
            val type = object : TypeToken<Map<String, Long>>() {}.type
            gson.fromJson<Map<String, Long>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun saveIgnoredMap(map: Map<String, Long>) {
        context.suggestionDataStore.edit { prefs ->
            if (map.isEmpty()) {
                prefs.remove(Keys.IGNORED_PACKAGES)
            } else {
                val json = gson.toJson(map)
                prefs[Keys.IGNORED_PACKAGES] = json
            }
        }
    }

    companion object {
        private const val IGNORE_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L // 7 days
    }
}

