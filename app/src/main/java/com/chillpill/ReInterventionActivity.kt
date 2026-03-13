package com.chillpill

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.chillpill.service.GracePeriodService
import com.chillpill.ui.appblock.AppBlockEvent
import com.chillpill.ui.appblock.AppBlockViewModel
import com.chillpill.ui.reintervention.ReInterventionScreen
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ReInterventionActivity : ComponentActivity() {

    private val viewModel: AppBlockViewModel by viewModels { factory }

    private val factory: ViewModelProvider.Factory get() {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as? ChillpillApp
                    ?: run {
                        Log.e(TAG, "ViewModel factory: application is not ChillpillApp")
                        throw IllegalStateException("ReInterventionActivity requires ChillpillApp")
                    }
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                return AppBlockViewModel(app, packageName, isReIntervention = true) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        Log.d(TAG, "onCreate: packageName=$packageName")
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "onCreate: missing packageName, finishing")
            finish()
            return
        }
        if (application !is ChillpillApp) {
            Log.e(TAG, "ReInterventionActivity started without ChillpillApp")
            finish()
            return
        }
        Log.d(TAG, "onCreate: showing re-intervention screen for packageName=$packageName")

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    AppBlockEvent.RequestFinish -> launchTargetAppAndFinish()
                    AppBlockEvent.RequestGoHome -> goHomeAndFinish()
                }
            }
        }

        setContent {
            BackHandler { goHomeAndFinish() }
            ChillpillTheme {
                val phase by viewModel.phase.collectAsStateWithLifecycle()
                val progress by viewModel.progress.collectAsStateWithLifecycle()
                ReInterventionScreen(
                    modifier = Modifier.fillMaxSize(),
                    phase = phase,
                    progress = progress,
                    appName = appName,
                    onContinue = viewModel::onContinue,
                    onGoHome = viewModel::onGoHome
                )
            }
        }
    }

    private fun launchTargetAppAndFinish() {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        startGracePeriodService(packageName)
        try {
            val launchIntent = buildLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchTargetAppAndFinish failed for packageName=$packageName", e)
            try {
                val fallback = packageManager.getLaunchIntentForPackage(packageName)
                    ?: resolveLauncherActivity(packageName)
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(fallback)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "launchTargetAppAndFinish fallback failed for packageName=$packageName", e2)
            }
        }
        finish()
    }

    private fun startGracePeriodService(packageName: String) {
        try {
            val intent = Intent(this, GracePeriodService::class.java).apply {
                action = GracePeriodService.ACTION_START
                putExtra(GracePeriodService.EXTRA_PACKAGE_NAME, packageName)
            }
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e(TAG, "startGracePeriodService failed for packageName=$packageName", e)
        }
    }

    private fun buildLaunchIntentForPackage(packageName: String): Intent? =
        packageManager.getLaunchIntentForPackage(packageName) ?: resolveLauncherActivity(packageName)

    private fun resolveLauncherActivity(packageName: String): Intent? {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(launcherIntent, 0)
        val match = resolveInfos.firstOrNull { it.activityInfo.packageName == packageName } ?: return null
        val info = match.activityInfo
        return Intent().setClassName(info.packageName.toString(), info.name)
    }

    private fun goHomeAndFinish() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d(TAG, "onUserLeaveHint: user left, finishing")
        finish()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: packageName=${intent.getStringExtra(EXTRA_PACKAGE_NAME)}")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReInterventionActivity"
        const val EXTRA_PACKAGE_NAME = "packageName"
    }
}
