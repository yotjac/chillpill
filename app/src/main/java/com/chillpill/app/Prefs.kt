package com.chillpill.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val PREFS_NAME = "chillpill_prefs"
private const val KEY_WHITELIST = "whitelist"
private const val KEY_TIMER_SECONDS = "timer_seconds"
private const val DEFAULT_TIMER_SECONDS = 5

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_WHITELIST, value) }

    var timerSeconds: Int
        get() = prefs.getInt(KEY_TIMER_SECONDS, DEFAULT_TIMER_SECONDS).coerceIn(1, 999)
        set(value) = prefs.edit { putInt(KEY_TIMER_SECONDS, value.coerceIn(1, 999)) }
}
