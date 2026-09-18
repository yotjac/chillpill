package com.chillpill

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import com.chillpill.service.BlockingSharedState
import com.chillpill.service.GracePeriodService
import com.chillpill.ui.appblock.AppBlockEvent
import com.chillpill.ui.appblock.AppBlockScreen
import com.chillpill.ui.appblock.AppBlockViewModel
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AppBlockActivity : ComponentActivity() {

    private val viewModel: AppBlockViewModel by viewModels { factory }

    /** True once Continue / Go Home / back started navigating away (see onUserLeaveHint). */
    private var leavingByButton = false

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Draw under the camera cutout too; see values-v28/themes.xml.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    AppBlockEvent.RequestFinish -> launchTargetAppAndFinish()
                    AppBlockEvent.RequestGoHome -> goHomeAndFinish()
                }
            }
        }

        setContent {
            BackHandler { viewModel.onGoHome() }
            ChillpillTheme {
                val phase by viewModel.phase.collectAsStateWithLifecycle()
                val progress by viewModel.progress.collectAsStateWithLifecycle()
                val openCount24h by viewModel.openCount24h.collectAsStateWithLifecycle()
                val background by viewModel.blockBackground.collectAsStateWithLifecycle()
                AppBlockScreen(
                    modifier = Modifier.fillMaxSize(),
                    phase = phase,
                    progress = progress,
                    openCount24h = openCount24h,
                    isReIntervention = viewModel.isReIntervention,
                    appName = appName,
                    background = background,
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
        leavingByButton = true
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
            // No service means nothing will ever end this grace window; end the session now so the
            // next open shows the block instead of being allowed in forever.
            Log.e(TAG, "startGracePeriodService failed for packageName=$packageName; ending session", e)
            BlockingSharedState.sessions.endSession(packageName)
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
        leavingByButton = true
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
        // Continue / Go Home / Back also trigger onUserLeaveHint (we start another activity); those
        // paths go through the view model and record their own events, so only count genuine
        // system-gesture dismissals here.
        if (!leavingByButton) viewModel.recordDismissedViaSystemGesture()
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
