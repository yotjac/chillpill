package com.chillpill

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.chillpill.service.BlockingSharedState
import com.chillpill.service.GracePeriodService
import com.chillpill.ui.suggestion.SuggestRestrictionScreen
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SuggestRestrictionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "SuggestRestrictionActivity started without package name")
            finish()
            return
        }

        val app = application as? ChillpillApp
        if (app == null) {
            Log.e(TAG, "SuggestRestrictionActivity requires ChillpillApp")
            finish()
            return
        }

        val appName = resolveAppName(packageName)

        setContent {
            ChillpillTheme {
                SuggestRestrictionScreen(
                    appName = appName,
                    packageName = packageName,
                    onIgnore = {
                        lifecycleScope.launch {
                            app.suggestionRepository.markIgnored(packageName)
                            app.appOpenTracker.clearSuggestionShown(packageName)
                            finish()
                        }
                    },
                    onRestrict = {
                        lifecycleScope.launch {
                            app.restrictedAppsRepository.addRestricted(packageName)
                            app.suggestionRepository.removeIgnored(packageName)
                            app.appOpenTracker.clearSuggestionShown(packageName)
                            val graceMinutes = app.settingsRepository.settings.first().gracePeriodMinutes
                            val graceMs = graceMinutes * 60L * 1000L
                            BlockingSharedState.setGraceValidUntil(packageName, System.currentTimeMillis() + graceMs)
                            val serviceIntent = Intent(this@SuggestRestrictionActivity, GracePeriodService::class.java).apply {
                                action = GracePeriodService.ACTION_START
                                putExtra(GracePeriodService.EXTRA_PACKAGE_NAME, packageName)
                            }
                            ContextCompat.startForegroundService(this@SuggestRestrictionActivity, serviceIntent)
                            finish()
                        }
                    },
                    onNeverAskAgain = {
                        lifecycleScope.launch {
                            app.suggestionRepository.addPermanentlyExcluded(packageName)
                            app.appOpenTracker.clearSuggestionShown(packageName)
                            finish()
                        }
                    }
                )
            }
        }
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    companion object {
        private const val TAG = "SuggestRestrictionActivity"
        const val EXTRA_PACKAGE_NAME = "packageName"
    }
}

