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
import com.chillpill.ui.theme.ChillpillTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ChillpillApp
        setContent {
            ChillpillTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChillpillNavHost(
                        app = app,
                        onFixPermissions = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                }
            }
        }
    }
}
