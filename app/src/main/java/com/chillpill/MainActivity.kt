package com.chillpill

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.chillpill.ui.navigation.ChillpillNavHost
import com.chillpill.ui.navigation.Routes
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.chillpill.OPEN_SETTINGS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ChillpillApp
        val openSettingsOnLaunch = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
        if (openSettingsOnLaunch) intent?.removeExtra(EXTRA_OPEN_SETTINGS)
        val startDestination = when {
            openSettingsOnLaunch -> Routes.SETTINGS_FLOW
            else -> runBlocking {
                if (app.settingsRepository.setupCompleted.first()) Routes.HOME else Routes.SETUP
            }
        }
        setContent {
            ChillpillTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChillpillNavHost(
                        app = app,
                        startDestination = startDestination,
                        onFixPermissions = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onFixUsageAccess = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                        openSettingsOnLaunch = openSettingsOnLaunch
                    )
                }
            }
        }
    }
}
