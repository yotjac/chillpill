package com.chillpill.ui.home

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.lifecycle.ViewModel
import com.chillpill.ChillpillApp
import com.chillpill.service.ChillpillAccessibilityService
import com.chillpill.data.usage.UsageEventType
import java.util.Calendar
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers


class HomeViewModel(
    private val app: ChillpillApp
) : ViewModel() {

    private val restrictedAppsRepository = app.restrictedAppsRepository
    private val usageEventsRepository = app.usageEventsRepository

    private val _permissionsOk = MutableStateFlow(false)
    val permissionsOk: StateFlow<Boolean> = _permissionsOk.asStateFlow()

    private val _usageAccessGranted = MutableStateFlow(false)
    private val _usageAccessDismissed = MutableStateFlow(false)

    private val _showUsageAccessBanner = MutableStateFlow(false)
    val showUsageAccessBanner: StateFlow<Boolean> = _showUsageAccessBanner.asStateFlow()

    private val _restrictedPackages = MutableStateFlow<Set<String>>(emptySet())
    val restrictedPackages: StateFlow<Set<String>> = _restrictedPackages.asStateFlow()

    private val _todayAttempts = MutableStateFlow(0)
    val todayAttempts: StateFlow<Int> = _todayAttempts.asStateFlow()

    private val _todayEntered = MutableStateFlow(0)
    val todayEntered: StateFlow<Int> = _todayEntered.asStateFlow()

    private fun updateShowUsageAccessBanner() {
        _showUsageAccessBanner.value = !_usageAccessGranted.value && !_usageAccessDismissed.value
    }

    init {
        refreshPermissions()
        observeRestrictedAppsAndStats()
    }

    fun refreshStats() {
        viewModelScope.launch(Dispatchers.IO) {
            updateTodayStats(_restrictedPackages.value)
        }
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

    private fun observeRestrictedAppsAndStats() {
        viewModelScope.launch(Dispatchers.IO) {
            restrictedAppsRepository.restrictedPackages.collect { packages ->
                _restrictedPackages.value = packages
                updateTodayStats(packages)
            }
        }
    }

    private suspend fun updateTodayStats(packages: Set<String>) {
        if (packages.isEmpty()) {
            _todayAttempts.value = 0
            _todayEntered.value = 0
            return
        }

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDayMs = calendar.timeInMillis

        val stats = usageEventsRepository.getStatsForPackages(packages, startOfDayMs)
        var attempts = 0
        var entered = 0
        for (entry in stats.values) {
            attempts += entry.openAttempts
            entered += entry.continueCount
        }
        _todayAttempts.value = attempts
        _todayEntered.value = entered
    }
}
