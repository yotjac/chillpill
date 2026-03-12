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
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.chillpill.ui.appblock.AppBlockEvent
import com.chillpill.ui.appblock.AppBlockScreen
import com.chillpill.ui.appblock.AppBlockViewModel
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AppBlockActivity : ComponentActivity() {

    private val viewModel: AppBlockViewModel by viewModels { factory }

    private val factory: ViewModelProvider.Factory get() {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as? ChillpillApp
                    ?: run {
                        Log.e(TAG, "ViewModel factory: application is not ChillpillApp")
                        throw IllegalStateException("AppBlockActivity requires ChillpillApp")
                    }
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                val isReIntervention = intent.getBooleanExtra(EXTRA_IS_RE_INTERVENTION, false)
                return AppBlockViewModel(app, packageName, isReIntervention) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        Log.d(TAG, "onCreate: packageName=$packageName isReIntervention=${intent.getBooleanExtra(EXTRA_IS_RE_INTERVENTION, false)}")
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "onCreate: missing packageName, finishing")
            finish()
            return
        }
        if (application !is ChillpillApp) {
            Log.e(TAG, "AppBlockActivity started without ChillpillApp")
            finish()
            return
        }
        Log.d(TAG, "onCreate: showing block screen for packageName=$packageName")

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
                val openCount24h by viewModel.openCount24h.collectAsStateWithLifecycle()
                AppBlockScreen(
                    modifier = Modifier.fillMaxSize(),
                    phase = phase,
                    progress = progress,
                    openCount24h = openCount24h,
                    isReIntervention = viewModel.isReIntervention,
                    appName = appName,
                    onContinue = viewModel::onContinue,
                    onGoHome = viewModel::onGoHome
                )
            }
        }
    }

    /**
     * Brings the restricted app back to the foreground (equivalent to switching back from recents).
     * Uses FLAG_ACTIVITY_NEW_TASK only so the existing task is brought to front without clearing it.
     */
    private fun launchTargetAppAndFinish() {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
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
        private const val TAG = "AppBlockActivity"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_CLASS_NAME = "className"
        const val EXTRA_IS_RE_INTERVENTION = "isReIntervention"
    }
}
