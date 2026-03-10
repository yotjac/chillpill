package com.chillpill.ui.home

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.lifecycle.ViewModel
import com.chillpill.ChillpillApp
import com.chillpill.service.ChillpillAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(
    private val app: ChillpillApp
) : ViewModel() {

    private val _permissionsOk = MutableStateFlow(false)
    val permissionsOk: StateFlow<Boolean> = _permissionsOk.asStateFlow()

    private val _usageAccessGranted = MutableStateFlow(false)
    private val _usageAccessDismissed = MutableStateFlow(false)

    private val _showUsageAccessBanner = MutableStateFlow(false)
    val showUsageAccessBanner: StateFlow<Boolean> = _showUsageAccessBanner.asStateFlow()

    private fun updateShowUsageAccessBanner() {
        _showUsageAccessBanner.value = !_usageAccessGranted.value && !_usageAccessDismissed.value
    }

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val expected = ComponentName(app, ChillpillAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        _permissionsOk.value = enabled.split(':').any { it.trim() == expected }

        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        _usageAccessGranted.value = if (appOps != null) {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                app.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } else {
            false
        }
        updateShowUsageAccessBanner()
    }

    fun dismissUsageAccessBanner() {
        _usageAccessDismissed.value = true
        updateShowUsageAccessBanner()
    }
}
