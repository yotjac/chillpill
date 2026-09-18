package com.chillpill.ui.settings

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.chillpill.ChillpillApp
import com.chillpill.data.settings.BlockBackground
import com.chillpill.ui.appblock.AppBlockPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

/**
 * True only if saving would lower the user's standards: wait time decreased, grace period
 * increased, or at least one restricted app removed. Every other edit (stricter values, added
 * apps, background, "block again" toggles) saves without the confirmation wait.
 */
internal fun isRestrictionReducing(
    originalWaitSeconds: Int,
    draftWaitSeconds: Int,
    originalGraceMinutes: Int,
    draftGraceMinutes: Int,
    originalRestricted: Set<String>,
    draftRestricted: Set<String>
): Boolean =
    draftWaitSeconds < originalWaitSeconds ||
        draftGraceMinutes > originalGraceMinutes ||
        !draftRestricted.containsAll(originalRestricted)

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

    private val _originalReInterventionDisabledPackages = MutableStateFlow<Set<String>>(emptySet())
    val originalReInterventionDisabledPackages: StateFlow<Set<String>> =
        _originalReInterventionDisabledPackages.asStateFlow()

    // Draft state (user edits, not persisted until Save)
    private val _waitTimeSecondsInput = MutableStateFlow("")
    val waitTimeSecondsInput: StateFlow<String> = _waitTimeSecondsInput.asStateFlow()

    private val _gracePeriodMinutesInput = MutableStateFlow("")
    val gracePeriodMinutesInput: StateFlow<String> = _gracePeriodMinutesInput.asStateFlow()

    private val _originalBlockBackground = MutableStateFlow<BlockBackground>(BlockBackground.Default)
    val originalBlockBackground: StateFlow<BlockBackground> = _originalBlockBackground.asStateFlow()

    private val _blockBackground = MutableStateFlow<BlockBackground>(BlockBackground.Default)
    val blockBackground: StateFlow<BlockBackground> = _blockBackground.asStateFlow()

    /**
     * The user's own photo currently available in the picker, if any. Independent of the
     * selection, so switching to a bundled image does not throw the photo away.
     */
    private val _customBackgroundFileName = MutableStateFlow<String?>(null)
    val customBackgroundFileName: StateFlow<String?> = _customBackgroundFileName.asStateFlow()

    private val _backgroundImportFailed = MutableStateFlow(false)
    val backgroundImportFailed: StateFlow<Boolean> = _backgroundImportFailed.asStateFlow()

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

    private data class DraftInputs(
        val waitTimeInput: String,
        val gracePeriodInput: String,
        val restrictedPackages: Set<String>,
        val reInterventionDisabledPackages: Set<String>,
        val blockBackground: BlockBackground
    )

    private data class OriginalValues(
        val waitTimeSeconds: Int,
        val gracePeriodMinutes: Int,
        val restrictedPackages: Set<String>,
        val reInterventionDisabledPackages: Set<String>,
        val blockBackground: BlockBackground
    )

    private val draftInputs: Flow<DraftInputs> = combine(
        _waitTimeSecondsInput,
        _gracePeriodMinutesInput,
        _restrictedPackages,
        _reInterventionDisabledPackages,
        _blockBackground
    ) { waitInput, graceInput, restricted, reInterventionDisabled, background ->
        DraftInputs(waitInput, graceInput, restricted, reInterventionDisabled, background)
    }

    private val originalValues: Flow<OriginalValues> = combine(
        _originalWaitTimeSeconds,
        _originalGracePeriodMinutes,
        _originalRestrictedPackages,
        _originalReInterventionDisabledPackages,
        _originalBlockBackground
    ) { wait, grace, restricted, reInterventionDisabled, background ->
        OriginalValues(wait, grace, restricted, reInterventionDisabled, background)
    }

    val hasChanges: StateFlow<Boolean> = combine(draftInputs, originalValues) { draft, original ->
        // An empty or invalid field is treated as "unchanged" rather than as an edit.
        val waitDraft = draft.waitTimeInput.toIntOrNull()
            ?.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS) ?: original.waitTimeSeconds
        val graceDraft = draft.gracePeriodInput.toIntOrNull()
            ?.coerceIn(MIN_GRACE_MINUTES, MAX_GRACE_MINUTES) ?: original.gracePeriodMinutes
        waitDraft != original.waitTimeSeconds ||
            graceDraft != original.gracePeriodMinutes ||
            draft.restrictedPackages != original.restrictedPackages ||
            draft.reInterventionDisabledPackages != original.reInterventionDisabledPackages ||
            draft.blockBackground != original.blockBackground
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Confirmation screen state (when saving would reduce restrictions)
    private val _showConfirmationScreen = MutableStateFlow(false)
    val showConfirmationScreen: StateFlow<Boolean> = _showConfirmationScreen.asStateFlow()

    private val _confirmationProgress = MutableStateFlow(0f)
    val confirmationProgress: StateFlow<Float> = _confirmationProgress.asStateFlow()

    private val _confirmationPhase = MutableStateFlow(AppBlockPhase.WAITING)
    val confirmationPhase: StateFlow<AppBlockPhase> = _confirmationPhase.asStateFlow()

    private var confirmationTimerJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = app.settingsRepository.settings.first()
            _originalWaitTimeSeconds.value = settings.waitTimeSeconds
            _originalGracePeriodMinutes.value = settings.gracePeriodMinutes
            _waitTimeSecondsInput.value = settings.waitTimeSeconds.toString()
            _gracePeriodMinutesInput.value = settings.gracePeriodMinutes.toString()
            _originalBlockBackground.value = settings.blockBackground
            _blockBackground.value = settings.blockBackground
            val persistedCustom = (settings.blockBackground as? BlockBackground.Custom)?.fileName
            // The stored photo stays on offer even while a bundled image is selected.
            val availableCustom = persistedCustom ?: app.blockBackgroundStore.latestFileName()
            _customBackgroundFileName.value = availableCustom
            if (draftOnly) {
                // Drop photos left behind by drafts that were never saved, or replaced earlier.
                // Only the settings screen does this; other screens must not touch these files.
                app.blockBackgroundStore.cleanup(setOfNotNull(persistedCustom, availableCustom))
            }
        }
        viewModelScope.launch {
            val restricted = app.restrictedAppsRepository.restrictedPackages.first()
            val reInterventionDisabled = app.restrictedAppsRepository.reInterventionDisabledPackages.first()
            _originalRestrictedPackages.value = restricted
            _originalReInterventionDisabledPackages.value = reInterventionDisabled
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

    fun onBuiltInBackgroundSelected(id: String) {
        _blockBackground.value = BlockBackground.BuiltIn(id)
    }

    /**
     * Imports the picked photo into app storage right away (the picker's URI grant is temporary)
     * and selects it in the draft. Nothing is persisted until Save.
     */
    fun onCustomBackgroundPicked(uri: Uri) {
        viewModelScope.launch {
            val fileName = app.blockBackgroundStore.importImage(uri)
            if (fileName == null) {
                _backgroundImportFailed.value = true
                return@launch
            }
            _customBackgroundFileName.value = fileName
            _blockBackground.value = BlockBackground.Custom(fileName)
        }
    }

    fun onCustomBackgroundSelected(fileName: String) {
        _blockBackground.value = BlockBackground.Custom(fileName)
    }

    fun onCustomBackgroundRemoved() {
        _customBackgroundFileName.value = null
        if (_blockBackground.value is BlockBackground.Custom) {
            _blockBackground.value = BlockBackground.Default
        }
    }

    fun onBackgroundImportErrorShown() {
        _backgroundImportFailed.value = false
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
        // An empty/invalid field goes back to the saved value, not the app default: resetting
        // to the default could itself look like lowering (or raising) the standards.
        val s = _waitTimeSecondsInput.value
        if (s.isEmpty()) {
            _waitTimeSecondsInput.value = _originalWaitTimeSeconds.value.toString()
        } else {
            val n = s.toIntOrNull()
            if (n == null || n < MIN_WAIT_SECONDS) {
                _waitTimeSecondsInput.value = _originalWaitTimeSeconds.value.toString()
            } else if (n > MAX_WAIT_SECONDS) {
                _waitTimeSecondsInput.value = MAX_WAIT_SECONDS.toString()
            }
        }
    }

    fun onGracePeriodFocusLost() {
        val s = _gracePeriodMinutesInput.value
        if (s.isEmpty()) {
            _gracePeriodMinutesInput.value = _originalGracePeriodMinutes.value.toString()
        } else {
            val n = s.toIntOrNull()
            if (n == null || n < MIN_GRACE_MINUTES) {
                _gracePeriodMinutesInput.value = _originalGracePeriodMinutes.value.toString()
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
     * Re-reads what is actually persisted before judging the draft. The originals are loaded once
     * in init, but the suggestion overlay can add a restricted app while this screen is open;
     * such apps are merged into the draft so saving neither drops them nor counts as a removal.
     */
    private suspend fun refreshOriginals() {
        val settings = app.settingsRepository.settings.first()
        val restricted = app.restrictedAppsRepository.restrictedPackages.first()
        val addedElsewhere = restricted - _originalRestrictedPackages.value
        _originalWaitTimeSeconds.value = settings.waitTimeSeconds
        _originalGracePeriodMinutes.value = settings.gracePeriodMinutes
        _originalRestrictedPackages.value = restricted
        if (addedElsewhere.isNotEmpty()) {
            _restrictedPackages.value = _restrictedPackages.value + addedElsewhere
            loadRestrictedAppsInfo()
        }
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            refreshOriginals()
            val reducing = isRestrictionReducing(
                originalWaitSeconds = _originalWaitTimeSeconds.value,
                draftWaitSeconds = getDraftWaitTimeSeconds(),
                originalGraceMinutes = _originalGracePeriodMinutes.value,
                draftGraceMinutes = getDraftGracePeriodMinutes(),
                originalRestricted = _originalRestrictedPackages.value,
                draftRestricted = _restrictedPackages.value
            )
            if (reducing) startConfirmation() else saveChanges()
        }
    }

    private fun startConfirmation() {
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
            val background = _blockBackground.value
            withContext(Dispatchers.IO) {
                app.settingsRepository.setWaitTimeSeconds(waitSeconds)
                app.settingsRepository.setGracePeriodMinutes(graceMinutes)
                app.settingsRepository.setBlockBackground(background)
                app.restrictedAppsRepository.setRestricted(restricted)
                app.restrictedAppsRepository.setReInterventionDisabled(reInterventionDisabled)
            }
            // Keep the photo still offered in the picker plus whatever was just persisted (the
            // user may have removed the photo while the save was in flight); drop the rest.
            app.blockBackgroundStore.cleanup(
                setOfNotNull(_customBackgroundFileName.value, (background as? BlockBackground.Custom)?.fileName)
            )
            _originalWaitTimeSeconds.value = waitSeconds
            _originalGracePeriodMinutes.value = graceMinutes
            _originalRestrictedPackages.value = restricted
            _originalReInterventionDisabledPackages.value = reInterventionDisabled
            _originalBlockBackground.value = background
            _showConfirmationScreen.value = false
            _confirmationProgress.value = 0f
            _confirmationPhase.value = AppBlockPhase.WAITING
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
