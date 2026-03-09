package com.chillpill.ui.statistics

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

enum class TimeRange(val label: String, val durationMs: Long) {
    TODAY("Today", 24 * 60 * 60 * 1000L),
    THREE_DAYS("3 Days", 3 * 24 * 60 * 60 * 1000L),
    SEVEN_DAYS("7 Days", 7 * 24 * 60 * 60 * 1000L),
    THIRTY_DAYS("30 Days", 30 * 24 * 60 * 60 * 1000L)
}

data class AppStatistic(
    val packageName: String,
    val appLabel: String,
    val openAttempts: Int,
    val continueCount: Int
)

class StatisticsViewModel(
    private val app: ChillpillApp
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)
    val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

    private val _stats = MutableStateFlow<List<AppStatistic>>(emptyList())
    val stats: StateFlow<List<AppStatistic>> = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadStats()
    }

    fun onRangeSelected(range: TimeRange) {
        if (_selectedRange.value == range) return
        _selectedRange.value = range
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            _isLoading.value = true
            withContext(Dispatchers.IO) {
                val packages = app.monitoredAppsRepository.monitoredPackages.first()
                val since = System.currentTimeMillis() - _selectedRange.value.durationMs
                val statsMap = app.usageEventsRepository.getStatsForPackages(packages, since)
                val pm = app.packageManager
                val list = packages.map { packageName ->
                    val label = try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        packageName
                    }
                    val appStats = statsMap[packageName] ?: com.chillpill.data.usage.AppStats(0, 0)
                    AppStatistic(
                        packageName = packageName,
                        appLabel = label,
                        openAttempts = appStats.openAttempts,
                        continueCount = appStats.continueCount
                    )
                }.sortedBy { it.appLabel.lowercase() }
                _stats.value = list
            }
            _isLoading.value = false
        }
    }

    class Factory(private val app: ChillpillApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StatisticsViewModel(app) as T
        }
    }
}
