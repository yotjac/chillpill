package com.chillpill.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val PREFS_NAME = "chillpill_prefs"
private const val KEY_BLACKLIST = "blacklist"
private const val KEY_TIMER_SECONDS = "timer_seconds"
private const val KEY_GRACE_PERIOD_MINUTES = "grace_period_minutes"
private const val KEY_GRACE_ENTRIES = "grace_entries"
private const val DEFAULT_TIMER_SECONDS = 5
private const val DEFAULT_GRACE_PERIOD_MINUTES = 5

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Package names that will be intercepted (show block screen when opened). */
    var blacklist: Set<String>
        get() = prefs.getStringSet(KEY_BLACKLIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_BLACKLIST, value) }

    var timerSeconds: Int
        get() = prefs.getInt(KEY_TIMER_SECONDS, DEFAULT_TIMER_SECONDS).coerceIn(1, 999)
        set(value) = prefs.edit { putInt(KEY_TIMER_SECONDS, value.coerceIn(1, 999)) }

    /** Grace period in minutes after "Continue to app" during which that app is not blocked. */
    var gracePeriodMinutes: Int
        get() = prefs.getInt(KEY_GRACE_PERIOD_MINUTES, DEFAULT_GRACE_PERIOD_MINUTES).coerceIn(1, 1440)
        set(value) = prefs.edit { putInt(KEY_GRACE_PERIOD_MINUTES, value.coerceIn(1, 1440)) }

    /**
     * Returns the expiry time (millis) for the given package's grace period, or null if not in grace.
     * Callers should treat null or past timestamp as "not in grace".
     */
    fun getGraceUntil(packageName: String): Long? {
        val now = System.currentTimeMillis()
        val raw = prefs.getStringSet(KEY_GRACE_ENTRIES, emptySet()) ?: emptySet()
        var found: Long? = null
        val valid = raw.mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val pkg = parts[0]
            val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (ts <= now) return@mapNotNull null // expired
            if (pkg == packageName) found = maxOf(found ?: 0L, ts)
            "$pkg|$ts"
        }.toMutableSet()
        if (valid.size != raw.size) prefs.edit { putStringSet(KEY_GRACE_ENTRIES, valid) }
        return found
    }

    /** Starts grace period for this package (until now + gracePeriodMinutes). */
    fun setGraceStarted(packageName: String) {
        val minutes = gracePeriodMinutes
        val until = System.currentTimeMillis() + minutes * 60L * 1000L
        val raw = prefs.getStringSet(KEY_GRACE_ENTRIES, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val updated = raw
            .mapNotNull { entry ->
                val parts = entry.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val pkg = parts[0]
                val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
                if (ts <= now) return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                "$pkg|$ts"
            }
            .toMutableSet()
        updated.add("$packageName|$until")
        prefs.edit { putStringSet(KEY_GRACE_ENTRIES, updated) }
    }
}
