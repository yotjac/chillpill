package com.chillpill.ui.statistics

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.chillpill.ChillpillApp
import com.chillpill.data.usage.DayStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val REFRESH_INTERVAL_MS = 15_000L

enum class TimeRange(val label: String, val durationMs: Long) {
    WEEK("Week", 7 * DAY_MS),
    MONTH("Month", 30 * DAY_MS),
    THREE_MONTHS("3 Months", 90 * DAY_MS),
    YEAR("Year", 365 * DAY_MS)
}

enum class FocusedSeries { NONE, ATTEMPTED, ENTERED }

data class BucketStat(val attempts: Int, val entered: Int)

data class AppStatistic(
    val packageName: String,
    val appLabel: String,
    val buckets: List<BucketStat>,
    val attemptsToday: Int,
    val enteredToday: Int
)

class StatisticsViewModel(
    private val app: ChillpillApp
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(TimeRange.WEEK)
    val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

    private val _stats = MutableStateFlow<List<AppStatistic>>(emptyList())
    val stats: StateFlow<List<AppStatistic>> = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _focusedSeries = MutableStateFlow(FocusedSeries.NONE)
    val focusedSeries: StateFlow<FocusedSeries> = _focusedSeries.asStateFlow()

    init {
        loadStats()
        startAutoRefresh()
    }

    fun onRangeSelected(range: TimeRange) {
        if (_selectedRange.value == range) return
        _selectedRange.value = range
        loadStats()
    }

    fun onLegendClicked(series: FocusedSeries) {
        _focusedSeries.value = if (_focusedSeries.value == series) FocusedSeries.NONE else series
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                loadStats(showLoading = false)
            }
        }
    }

    private fun bucketCount(range: TimeRange): Int = when (range) {
        TimeRange.WEEK -> 7
        TimeRange.MONTH -> 30
        TimeRange.THREE_MONTHS -> 13
        TimeRange.YEAR -> 12
    }

    private fun totalDaysForRange(range: TimeRange): Int = (range.durationMs / DAY_MS).toInt()

    private fun dailyToBuckets(daily: List<DayStats>, range: TimeRange): List<BucketStat> {
        val todayBucket = System.currentTimeMillis() / DAY_MS
        val count = bucketCount(range)
        val totalDays = totalDaysForRange(range)
        val startDayBucket = todayBucket - totalDays + 1

        val buckets = MutableList(count) { BucketStat(0, 0) }
        for (day in daily) {
            val dayOffset = (day.dayBucket - startDayBucket).toInt()
            if (dayOffset < 0 || dayOffset >= totalDays) continue
            val bucketIndex = when (range) {
                TimeRange.WEEK,
                TimeRange.MONTH -> dayOffset
                TimeRange.THREE_MONTHS -> (dayOffset * count / totalDays).coerceIn(0, count - 1)
                TimeRange.YEAR -> (dayOffset * count / totalDays).coerceIn(0, count - 1)
            }
            buckets[bucketIndex] = BucketStat(
                buckets[bucketIndex].attempts + day.attempts,
                buckets[bucketIndex].entered + day.entered
            )
        }
        return buckets
    }

    private fun loadStats(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            withContext(Dispatchers.IO) {
                val packages = app.monitoredAppsRepository.monitoredPackages.first()
                val range = _selectedRange.value
                val totalDays = totalDaysForRange(range)
                val todayBucket = System.currentTimeMillis() / DAY_MS
                val startDayBucket = todayBucket - totalDays + 1
                val since = startDayBucket * DAY_MS
                val dailyMap = app.usageEventsRepository.getDailyStatsForPackages(packages, since)
                val pm = app.packageManager
                val list = packages.map { packageName ->
                    val label = try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        packageName
                    }
                    val daily = dailyMap[packageName] ?: emptyList()
                    val buckets = dailyToBuckets(daily, range)
                    val todayStats = daily.find { it.dayBucket == todayBucket }
                    AppStatistic(
                        packageName = packageName,
                        appLabel = label,
                        buckets = buckets,
                        attemptsToday = todayStats?.attempts ?: 0,
                        enteredToday = todayStats?.entered ?: 0
                    )
                }.sortedByDescending { it.buckets.sumOf { b -> b.attempts } }
                _stats.value = list
            }
            if (showLoading) _isLoading.value = false
        }
    }

    class Factory(private val app: ChillpillApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StatisticsViewModel(app) as T
        }
    }
}
