package com.chillpill.ui.settings

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.chillpill.ChillpillApp
import com.chillpill.ui.appblock.AppBlockPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val reInterventionEnabled: Boolean = true
)

private const val DEFAULT_WAIT_SECONDS = 12
private const val DEFAULT_GRACE_MINUTES = 5
private const val MIN_WAIT_SECONDS = 1
private const val MAX_WAIT_SECONDS = 7200  // 2 hours
private const val MIN_GRACE_MINUTES = 1
private const val MAX_GRACE_MINUTES = 1440  // 24 hours
private const val CONFIRMATION_TICK_MS = 100L

class SettingsViewModel(
    private val app: ChillpillApp,
    private val draftOnly: Boolean = true
) : ViewModel() {

    // Original (persisted) state - loaded once on init
    private val _originalWaitTimeSeconds = MutableStateFlow(DEFAULT_WAIT_SECONDS)
    val originalWaitTimeSeconds: StateFlow<Int> = _originalWaitTimeSeconds.asStateFlow()

    private val _originalGracePeriodMinutes = MutableStateFlow(DEFAULT_GRACE_MINUTES)
    val originalGracePeriodMinutes: StateFlow<Int> = _originalGracePeriodMinutes.asStateFlow()

    private val _originalRestrictedPackages = MutableStateFlow<Set<String>>(emptySet())
    val originalRestrictedPackages: StateFlow<Set<String>> = _originalRestrictedPackages.asStateFlow()

    private var originalReInterventionDisabled: Set<String> = emptySet()

    // Draft state (user edits, not persisted until Save)
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

    private val _reInterventionDisabledPackages = MutableStateFlow<Set<String>>(emptySet())
    val reInterventionDisabledPackages: StateFlow<Set<String>> = _reInterventionDisabledPackages.asStateFlow()

    private val _expandedAppPackage = MutableStateFlow<String?>(null)
    val expandedAppPackage: StateFlow<String?> = _expandedAppPackage.asStateFlow()

    private val _appSearchQuery = MutableStateFlow("")
    val appSearchQuery: StateFlow<String> = _appSearchQuery.asStateFlow()

    val hasChanges: StateFlow<Boolean> = combine(
        _waitTimeSecondsInput,
        _gracePeriodMinutesInput,
        _restrictedPackages,
        _reInterventionDisabledPackages
    ) { waitInput, graceInput, restricted, reInterventionDisabled ->
        val origWait = _originalWaitTimeSeconds.value
        val origGrace = _originalGracePeriodMinutes.value
        val waitDraft = waitInput.toIntOrNull()?.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS) ?: origWait
        val graceDraft = graceInput.toIntOrNull()?.coerceIn(MIN_GRACE_MINUTES, MAX_GRACE_MINUTES) ?: origGrace
        waitDraft != origWait ||
            graceDraft != origGrace ||
            restricted != _originalRestrictedPackages.value ||
            reInterventionDisabled != originalReInterventionDisabled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Confirmation screen state (when saving would reduce restrictions)
    private val _showConfirmationScreen = MutableStateFlow(false)
    val showConfirmationScreen: StateFlow<Boolean> = _showConfirmationScreen.asStateFlow()

    private val _confirmationProgress = MutableStateFlow(0f)
    val confirmationProgress: StateFlow<Float> = _confirmationProgress.asStateFlow()

    private val _confirmationPhase = MutableStateFlow(AppBlockPhase.WAITING)
    val confirmationPhase: StateFlow<AppBlockPhase> = _confirmationPhase.asStateFlow()

    private var confirmationTimerJob: Job? = null

    private val _saveCompletedEvent = Channel<Unit>(Channel.BUFFERED)
    val saveCompletedEvent = _saveCompletedEvent.receiveAsFlow()

    init {
        viewModelScope.launch {
            val settings = app.settingsRepository.settings.first()
            _originalWaitTimeSeconds.value = settings.waitTimeSeconds
            _originalGracePeriodMinutes.value = settings.gracePeriodMinutes
            _waitTimeSecondsInput.value = settings.waitTimeSeconds.toString()
            _gracePeriodMinutesInput.value = settings.gracePeriodMinutes.toString()
        }
        viewModelScope.launch {
            val restricted = app.restrictedAppsRepository.restrictedPackages.first()
            val reInterventionDisabled = app.restrictedAppsRepository.reInterventionDisabledPackages.first()
            _originalRestrictedPackages.value = restricted
            originalReInterventionDisabled = reInterventionDisabled
            _restrictedPackages.value = restricted
            _reInterventionDisabledPackages.value = reInterventionDisabled
            loadRestrictedAppsInfo()
            loadInstalledApps()
        }
    }

    private fun loadRestrictedAppsInfo() {
        viewModelScope.launch {
            val packages = _restrictedPackages.value
            val disabledPackages = _reInterventionDisabledPackages.value
            val list = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                packages.mapNotNull { packageName ->
                    try {
                        val applicationInfo = pm.getApplicationInfo(packageName, 0)
                        val label = pm.getApplicationLabel(applicationInfo).toString()
                        AppInfo(
                            packageName = packageName,
                            label = label,
                            reInterventionEnabled = packageName !in disabledPackages
                        )
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
                val restrictedSet = _restrictedPackages.value

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
        if (clamped != n) _waitTimeSecondsInput.value = clamped.toString()
    }

    fun onGracePeriodChanged(s: String) {
        val digitsOnly = s.filter { it.isDigit() }
        _gracePeriodMinutesInput.value = digitsOnly
        val n = digitsOnly.toIntOrNull() ?: return
        val clamped = n.coerceIn(MIN_GRACE_MINUTES, MAX_GRACE_MINUTES)
        if (clamped != n) _gracePeriodMinutesInput.value = clamped.toString()
    }

    fun onRestrictedChanged(packageName: String, selected: Boolean) {
        _restrictedPackages.value = if (selected) {
            _restrictedPackages.value + packageName
        } else {
            _restrictedPackages.value - packageName
        }.also { updated ->
            if (packageName !in updated) {
                _reInterventionDisabledPackages.value =
                    _reInterventionDisabledPackages.value - packageName
            }
        }
        if (!draftOnly) {
            viewModelScope.launch {
                app.restrictedAppsRepository.setRestricted(_restrictedPackages.value)
                app.restrictedAppsRepository.setReInterventionDisabled(_reInterventionDisabledPackages.value)
            }
        }
        loadRestrictedAppsInfo()
        loadInstalledApps()
    }

    fun removeRestrictedApp(packageName: String) {
        _restrictedPackages.value = _restrictedPackages.value - packageName
        _reInterventionDisabledPackages.value = _reInterventionDisabledPackages.value - packageName
        loadRestrictedAppsInfo()
        loadInstalledApps()
    }

    fun onReInterventionToggled(packageName: String, enabled: Boolean) {
        _reInterventionDisabledPackages.value = if (enabled) {
            _reInterventionDisabledPackages.value - packageName
        } else {
            _reInterventionDisabledPackages.value + packageName
        }
        loadRestrictedAppsInfo()
    }

    fun onToggleExpanded(packageName: String) {
        _expandedAppPackage.value = if (_expandedAppPackage.value == packageName) {
            null
        } else {
            packageName
        }
    }

    fun onSearchQueryChanged(query: String) {
        _appSearchQuery.value = query
    }

    /** Call when focus leaves a config field to validate/reset invalid values (draft only, no persist). */
    fun onWaitTimeFocusLost() {
        val s = _waitTimeSecondsInput.value
        if (s.isEmpty()) {
            _waitTimeSecondsInput.value = DEFAULT_WAIT_SECONDS.toString()
        } else {
            val n = s.toIntOrNull()
            if (n == null || n < MIN_WAIT_SECONDS) {
                _waitTimeSecondsInput.value = DEFAULT_WAIT_SECONDS.toString()
            } else if (n > MAX_WAIT_SECONDS) {
                _waitTimeSecondsInput.value = MAX_WAIT_SECONDS.toString()
            }
        }
    }

    fun onGracePeriodFocusLost() {
        val s = _gracePeriodMinutesInput.value
        if (s.isEmpty()) {
            _gracePeriodMinutesInput.value = DEFAULT_GRACE_MINUTES.toString()
        } else {
            val n = s.toIntOrNull()
            if (n == null || n < MIN_GRACE_MINUTES) {
                _gracePeriodMinutesInput.value = DEFAULT_GRACE_MINUTES.toString()
            } else if (n > MAX_GRACE_MINUTES) {
                _gracePeriodMinutesInput.value = MAX_GRACE_MINUTES.toString()
            }
        }
    }

    private fun getDraftWaitTimeSeconds(): Int {
        return _waitTimeSecondsInput.value.toIntOrNull()
            ?.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS) ?: _originalWaitTimeSeconds.value
    }

    private fun getDraftGracePeriodMinutes(): Int {
        return _gracePeriodMinutesInput.value.toIntOrNull()
            ?.coerceIn(MIN_GRACE_MINUTES, MAX_GRACE_MINUTES) ?: _originalGracePeriodMinutes.value
    }

    /**
     * True if the draft changes would reduce restrictions:
     * - grace period increased, or
     * - wait time decreased, or
     * - any restricted app removed
     */
    private fun isRestrictionReducing(): Boolean {
        val waitDraft = getDraftWaitTimeSeconds()
        val graceDraft = getDraftGracePeriodMinutes()
        val restrictedDraft = _restrictedPackages.value
        if (graceDraft > _originalGracePeriodMinutes.value) return true
        if (waitDraft < _originalWaitTimeSeconds.value) return true
        if (restrictedDraft.size < _originalRestrictedPackages.value.size ||
            !_originalRestrictedPackages.value.all { it in restrictedDraft }) return true
        return false
    }

    fun onSaveClicked() {
        if (isRestrictionReducing()) {
            _showConfirmationScreen.value = true
            _confirmationProgress.value = 0f
            _confirmationPhase.value = AppBlockPhase.WAITING
            confirmationTimerJob?.cancel()
            confirmationTimerJob = viewModelScope.launch {
                val waitTimeSeconds = _originalWaitTimeSeconds.value.coerceAtLeast(1)
                val totalMs = waitTimeSeconds * 1000L
                var elapsedMs = 0L
                while (elapsedMs < totalMs) {
                    delay(CONFIRMATION_TICK_MS)
                    elapsedMs += CONFIRMATION_TICK_MS
                    _confirmationProgress.value = (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
                }
                _confirmationProgress.value = 1f
                _confirmationPhase.value = AppBlockPhase.COMPLETED
            }
        } else {
            saveChanges()
        }
    }

    /** Called from confirmation screen when user taps "save changes" (after timer). */
    fun onConfirmationSave() {
        confirmationTimerJob?.cancel()
        confirmationTimerJob = null
        saveChanges()
    }

    /** Called from confirmation screen when user taps "back". */
    fun onConfirmationBack() {
        confirmationTimerJob?.cancel()
        confirmationTimerJob = null
        _showConfirmationScreen.value = false
        _confirmationProgress.value = 0f
        _confirmationPhase.value = AppBlockPhase.WAITING
    }

    private fun saveChanges() {
        viewModelScope.launch {
            val waitSeconds = getDraftWaitTimeSeconds()
            val graceMinutes = getDraftGracePeriodMinutes()
            val restricted = _restrictedPackages.value
            val reInterventionDisabled = _reInterventionDisabledPackages.value
            withContext(Dispatchers.IO) {
                app.settingsRepository.setWaitTimeSeconds(waitSeconds)
                app.settingsRepository.setGracePeriodMinutes(graceMinutes)
                app.restrictedAppsRepository.setRestricted(restricted)
                app.restrictedAppsRepository.setReInterventionDisabled(reInterventionDisabled)
            }
            _originalWaitTimeSeconds.value = waitSeconds
            _originalGracePeriodMinutes.value = graceMinutes
            _originalRestrictedPackages.value = restricted
            originalReInterventionDisabled = reInterventionDisabled
            _showConfirmationScreen.value = false
            _confirmationProgress.value = 0f
            _confirmationPhase.value = AppBlockPhase.WAITING
            _saveCompletedEvent.send(Unit)
        }
    }

    class Factory(
        private val app: ChillpillApp,
        private val draftOnly: Boolean = true
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(app, draftOnly) as T
        }
    }
}
