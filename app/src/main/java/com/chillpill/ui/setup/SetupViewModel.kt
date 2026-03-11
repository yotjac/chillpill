package com.chillpill.ui.setup

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.chillpill.ChillpillApp
import com.chillpill.service.ChillpillAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SetupViewModel(
    private val app: ChillpillApp
) : ViewModel() {

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _accessibilityGranted = MutableStateFlow(false)
    val accessibilityGranted: StateFlow<Boolean> = _accessibilityGranted.asStateFlow()

    private val _usageAccessGranted = MutableStateFlow(false)
    val usageAccessGranted: StateFlow<Boolean> = _usageAccessGranted.asStateFlow()

    val monitoredPackages: StateFlow<Set<String>> = app.monitoredAppsRepository.monitoredPackages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val canAdvance: StateFlow<Boolean> = combine(
        _currentStep,
        _accessibilityGranted,
        _usageAccessGranted,
        monitoredPackages
    ) { step, accessibility, usageAccess, packages ->
        when (step) {
            0 -> true
            1 -> accessibility && usageAccess
            2 -> packages.isNotEmpty()
            3 -> true
            else -> false
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val expected = ComponentName(app, ChillpillAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        _accessibilityGranted.value = enabled.split(':').any { it.trim() == expected }

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
    }

    fun nextStep() {
        val step = _currentStep.value
        if (step < 3) _currentStep.value = step + 1
    }

    fun removeMonitoredApp(packageName: String) {
        viewModelScope.launch {
            val current = app.monitoredAppsRepository.monitoredPackages.first()
            app.monitoredAppsRepository.setMonitored(current - packageName)
        }
    }

    fun completeSetup(onDone: () -> Unit) {
        viewModelScope.launch {
            app.settingsRepository.setSetupCompleted(true)
            onDone()
        }
    }

    class Factory(private val app: ChillpillApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SetupViewModel(app) as T
        }
    }
}
