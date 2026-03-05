package com.chillpill

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Trampoline activity used as the accessibility service's settingsActivity.
 * Launches MainActivity with OPEN_SETTINGS so the user lands on the app's Settings screen.
 */
class ChillpillSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        finish()
    }
}
