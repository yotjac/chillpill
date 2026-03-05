package com.chillpill.ui.settings

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

    private val _monitoredPackages = MutableStateFlow<Set<String>>(emptySet())
    val monitoredPackages: StateFlow<Set<String>> = _monitoredPackages.asStateFlow()

    private val _appSearchQuery = MutableStateFlow("")
    val appSearchQuery: StateFlow<String> = _appSearchQuery.asStateFlow()

    init {
        viewModelScope.launch {
            app.settingsRepository.settings.first().let { settings ->
                _waitTimeSecondsInput.value = settings.waitTimeSeconds.toString()
                _gracePeriodMinutesInput.value = settings.gracePeriodMinutes.toString()
            }
            app.monitoredAppsRepository.monitoredPackages.first().let { set ->
                _monitoredPackages.value = set
            }
            loadInstalledApps()
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                val chillpillPackage = app.packageName
                val monitoredSet = app.monitoredAppsRepository.monitoredPackages.first()

                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

                val launcherPackages = resolveInfos
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != chillpillPackage }
                    .toSet()

                val packageNames = (launcherPackages + monitoredSet).distinct()

                packageNames.mapNotNull { packageName ->
                    try {
                        val applicationInfo = pm.getApplicationInfo(packageName, 0)
                        val label = pm.getApplicationLabel(applicationInfo).toString()
                        AppInfo(packageName = packageName, label = label)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }.sortedBy { it.label.lowercase() }
            }
            _installedApps.value = apps
        }
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

    fun onMonitoredChanged(packageName: String, selected: Boolean) {
        _monitoredPackages.value = if (selected) {
            _monitoredPackages.value + packageName
        } else {
            _monitoredPackages.value - packageName
        }
        viewModelScope.launch {
            app.monitoredAppsRepository.setMonitored(_monitoredPackages.value)
        }
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
