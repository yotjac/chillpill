package com.chillpill.ui.settings

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.chillpill.ChillpillApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String
)

private const val DEFAULT_WAIT_SECONDS = 12
private const val DEFAULT_GRACE_MINUTES = 5
private const val MIN_WAIT_SECONDS = 1
private const val MAX_WAIT_SECONDS = 7200  // 2 hours
private const val MIN_GRACE_MINUTES = 1
private const val MAX_GRACE_MINUTES = 1440  // 24 hours

class SettingsViewModel(
    private val app: ChillpillApp
) : ViewModel() {

    private val _waitTimeSecondsInput = MutableStateFlow("")
    val waitTimeSecondsInput: StateFlow<String> = _waitTimeSecondsInput.asStateFlow()

    private val _gracePeriodMinutesInput = MutableStateFlow("")
    val gracePeriodMinutesInput: StateFlow<String> = _gracePeriodMinutesInput.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _restrictedPackages = MutableStateFlow<Set<String>>(emptySet())
    val restrictedPackages: StateFlow<Set<String>> = _restrictedPackages.asStateFlow()

    private val _restrictedAppsInfo = MutableStateFlow<List<AppInfo>>(emptyList())
    val restrictedAppsInfo: StateFlow<List<AppInfo>> = _restrictedAppsInfo.asStateFlow()

    private val _appSearchQuery = MutableStateFlow("")
    val appSearchQuery: StateFlow<String> = _appSearchQuery.asStateFlow()

    init {
        viewModelScope.launch {
            app.settingsRepository.settings.first().let { settings ->
                _waitTimeSecondsInput.value = settings.waitTimeSeconds.toString()
                _gracePeriodMinutesInput.value = settings.gracePeriodMinutes.toString()
            }
            loadInstalledApps()
        }
        viewModelScope.launch {
            app.restrictedAppsRepository.restrictedPackages.collect { set ->
                _restrictedPackages.value = set
                loadRestrictedAppsInfo()
            }
        }
    }

    private fun loadRestrictedAppsInfo() {
        viewModelScope.launch {
            val packages = _restrictedPackages.value
            val list = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                packages.mapNotNull { packageName ->
                    try {
                        val applicationInfo = pm.getApplicationInfo(packageName, 0)
                        val label = pm.getApplicationLabel(applicationInfo).toString()
                        AppInfo(packageName = packageName, label = label)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }.sortedBy { it.label.lowercase() }
            }
            _restrictedAppsInfo.value = list
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                val chillpillPackage = app.packageName
                val restrictedSet = app.restrictedAppsRepository.restrictedPackages.first()

                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

                val launcherPackages = resolveInfos
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != chillpillPackage }
                    .toSet()

                val packageNames = (launcherPackages + restrictedSet).distinct()

                val appList = packageNames.mapNotNull { packageName ->
                    try {
                        val applicationInfo = pm.getApplicationInfo(packageName, 0)
                        val label = pm.getApplicationLabel(applicationInfo).toString()
                        AppInfo(packageName = packageName, label = label)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                sortAppsByUsage(app.applicationContext, appList)
            }
            _installedApps.value = apps
        }
    }

    /**
     * Sorts apps by global device usage (UsageStatsManager totalTimeInForeground) when available,
     * otherwise alphabetically by label.
     */
    private fun sortAppsByUsage(context: Context, apps: List<AppInfo>): List<AppInfo> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return apps.sortedBy { it.label.lowercase() }
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 7 * 24 * 60 * 60 * 1000L
        @Suppress("DEPRECATION")
        val statsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_WEEKLY,
            startTime,
            endTime
        ) ?: return apps.sortedBy { it.label.lowercase() }
        val totalTimeByPackage = mutableMapOf<String, Long>()
        for (stats in statsList) {
            val pkg = stats.packageName
            totalTimeByPackage[pkg] = (totalTimeByPackage[pkg] ?: 0L) + stats.totalTimeInForeground
        }
        return apps.sortedWith(
            compareByDescending<AppInfo> { totalTimeByPackage[it.packageName] ?: 0L }
                .thenBy { it.label.lowercase() }
        )
    }

    fun onWaitTimeChanged(s: String) {
        val digitsOnly = s.filter { it.isDigit() }
        _waitTimeSecondsInput.value = digitsOnly
        val n = digitsOnly.toIntOrNull() ?: return
        val clamped = n.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS)
        viewModelScope.launch {
            app.settingsRepository.setWaitTimeSeconds(clamped)
            if (clamped != n) _waitTimeSecondsInput.value = clamped.toString()
        }
    }

    fun onGracePeriodChanged(s: String) {
        val digitsOnly = s.filter { it.isDigit() }
        _gracePeriodMinutesInput.value = digitsOnly
        val n = digitsOnly.toIntOrNull() ?: return
        val clamped = n.coerceIn(MIN_GRACE_MINUTES, MAX_GRACE_MINUTES)
        viewModelScope.launch {
            app.settingsRepository.setGracePeriodMinutes(clamped)
            if (clamped != n) _gracePeriodMinutesInput.value = clamped.toString()
        }
    }

    fun onRestrictedChanged(packageName: String, selected: Boolean) {
        _restrictedPackages.value = if (selected) {
            _restrictedPackages.value + packageName
        } else {
            _restrictedPackages.value - packageName
        }
        viewModelScope.launch {
            app.restrictedAppsRepository.setRestricted(_restrictedPackages.value)
        }
        loadRestrictedAppsInfo()
    }

    fun removeRestrictedApp(packageName: String) {
        _restrictedPackages.value = _restrictedPackages.value - packageName
        viewModelScope.launch {
            app.restrictedAppsRepository.setRestricted(_restrictedPackages.value)
        }
        loadRestrictedAppsInfo()
    }

    fun onSearchQueryChanged(query: String) {
        _appSearchQuery.value = query
    }

    /** Call when focus leaves a config field to commit or reset invalid values. */
    fun onWaitTimeFocusLost() {
        val s = _waitTimeSecondsInput.value
        if (s.isEmpty()) {
            _waitTimeSecondsInput.value = DEFAULT_WAIT_SECONDS.toString()
            viewModelScope.launch { app.settingsRepository.setWaitTimeSeconds(DEFAULT_WAIT_SECONDS) }
        } else {
            val n = s.toIntOrNull()
            if (n == null || n < MIN_WAIT_SECONDS) {
                _waitTimeSecondsInput.value = DEFAULT_WAIT_SECONDS.toString()
                viewModelScope.launch { app.settingsRepository.setWaitTimeSeconds(DEFAULT_WAIT_SECONDS) }
            } else if (n > MAX_WAIT_SECONDS) {
                _waitTimeSecondsInput.value = MAX_WAIT_SECONDS.toString()
                viewModelScope.launch { app.settingsRepository.setWaitTimeSeconds(MAX_WAIT_SECONDS) }
            }
        }
    }

    fun onGracePeriodFocusLost() {
        val s = _gracePeriodMinutesInput.value
        if (s.isEmpty()) {
            _gracePeriodMinutesInput.value = DEFAULT_GRACE_MINUTES.toString()
            viewModelScope.launch { app.settingsRepository.setGracePeriodMinutes(DEFAULT_GRACE_MINUTES) }
        } else {
            val n = s.toIntOrNull()
            if (n == null || n < MIN_GRACE_MINUTES) {
                _gracePeriodMinutesInput.value = DEFAULT_GRACE_MINUTES.toString()
                viewModelScope.launch { app.settingsRepository.setGracePeriodMinutes(DEFAULT_GRACE_MINUTES) }
            } else if (n > MAX_GRACE_MINUTES) {
                _gracePeriodMinutesInput.value = MAX_GRACE_MINUTES.toString()
                viewModelScope.launch { app.settingsRepository.setGracePeriodMinutes(MAX_GRACE_MINUTES) }
            }
        }
    }

    class Factory(private val app: ChillpillApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(app) as T
        }
    }
}
