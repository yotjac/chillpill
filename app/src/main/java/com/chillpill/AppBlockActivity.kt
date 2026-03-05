package com.chillpill

import android.content.Intent
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
                val className = intent.getStringExtra(EXTRA_CLASS_NAME)
                val isReIntervention = intent.getBooleanExtra(EXTRA_IS_RE_INTERVENTION, false)
                return AppBlockViewModel(app, packageName, className, isReIntervention) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra(EXTRA_PACKAGE_NAME).isNullOrBlank()) {
            finish()
            return
        }
        if (application !is ChillpillApp) {
            Log.e(TAG, "AppBlockActivity started without ChillpillApp")
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    AppBlockEvent.RequestFinish -> finish()
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
                    onContinue = viewModel::onContinue,
                    onGoHome = viewModel::onGoHome
                )
            }
        }
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
        finish()
    }

    companion object {
        private const val TAG = "AppBlockActivity"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_CLASS_NAME = "className"
        const val EXTRA_IS_RE_INTERVENTION = "isReIntervention"
    }
}
