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
import com.chillpill.data.restricted.AppPolicy
import com.chillpill.data.restricted.PolicyResolver
import com.chillpill.data.settings.BlockBackground
import com.chillpill.service.engine.Mode
import com.chillpill.ui.appblock.AppBlockPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String
)

private const val CONFIRMATION_TICK_MS = 100L

/**
 * Settings draft (specs/per-app-modes.md): each restricted app's policy (apps not in
 * [overrideDrafts] have the fixed [AppPolicy.DEFAULT]; editing one adds it), the confirmation wait,
 * the restricted set and the background. Nothing is persisted until Save; a looser draft goes
 * through the confirmation wait.
 */
class SettingsViewModel(
    private val app: ChillpillApp,
    private val draftOnly: Boolean = true
) : ViewModel() {

    // ---- persisted values (loaded in init, refreshed before Save) -----------------------------

    private val _originalOverrides = MutableStateFlow<Map<String, AppPolicy>>(emptyMap())
    val originalOverrides: StateFlow<Map<String, AppPolicy>> = _originalOverrides.asStateFlow()

    private val _originalConfirmationWait = MutableStateFlow(10)
    val originalConfirmationWait: StateFlow<Int> = _originalConfirmationWait.asStateFlow()

    private val _originalRestrictedPackages = MutableStateFlow<Set<String>>(emptySet())
    val originalRestrictedPackages: StateFlow<Set<String>> = _originalRestrictedPackages.asStateFlow()

    private val _originalBlockBackground = MutableStateFlow<BlockBackground>(BlockBackground.Default)
    val originalBlockBackground: StateFlow<BlockBackground> = _originalBlockBackground.asStateFlow()

    // ---- draft --------------------------------------------------------------------------------

    private val _overrideDrafts = MutableStateFlow<Map<String, PolicyDraft>>(emptyMap())
    val overrideDrafts: StateFlow<Map<String, PolicyDraft>> = _overrideDrafts.asStateFlow()

    private val _confirmationWaitInput = MutableStateFlow("")
    val confirmationWaitInput: StateFlow<String> = _confirmationWaitInput.asStateFlow()

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

    private val _expandedAppPackage = MutableStateFlow<String?>(null)
    val expandedAppPackage: StateFlow<String?> = _expandedAppPackage.asStateFlow()

    private val _appSearchQuery = MutableStateFlow("")
    val appSearchQuery: StateFlow<String> = _appSearchQuery.asStateFlow()

    /** Everything Save would write, as it would be written. */
    private data class Values(
        val overrides: Map<String, AppPolicy>,
        val confirmationWait: Int,
        val restricted: Set<String>,
        val background: BlockBackground
    )

    private fun draftValues() = Values(
        overrides = draftOverrides(),
        confirmationWait = draftConfirmationWait(),
        restricted = _restrictedPackages.value,
        background = _blockBackground.value
    )

    private fun originalValues() = Values(
        overrides = _originalOverrides.value,
        confirmationWait = _originalConfirmationWait.value,
        restricted = _originalRestrictedPackages.value,
        background = _originalBlockBackground.value
    )

    // An empty or invalid field counts as "unchanged" (it falls back to the saved value).
    val hasChanges: StateFlow<Boolean> = combine(
        combine(_overrideDrafts, _confirmationWaitInput, _restrictedPackages, _blockBackground) { _, _, _, _ -> Unit },
        combine(_originalOverrides, _originalConfirmationWait, _originalRestrictedPackages, _originalBlockBackground) { _, _, _, _ -> Unit }
    ) { _, _ -> draftValues() != originalValues() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
            _originalConfirmationWait.value = settings.confirmationWaitSeconds
            _confirmationWaitInput.value = settings.confirmationWaitSeconds.toString()
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
            val restrictions = app.restrictions.first()
            _originalRestrictedPackages.value = restrictions.restricted
            _restrictedPackages.value = restrictions.restricted
            _originalOverrides.value = restrictions.overrides
            _overrideDrafts.value = restrictions.overrides.mapValues { PolicyDraft.of(it.value) }
            loadRestrictedAppsInfo()
            loadInstalledApps()
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
                        AppInfo(packageName, pm.getApplicationLabel(applicationInfo).toString())
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

    // ---- restricted apps ----------------------------------------------------------------------

    fun onRestrictedChanged(packageName: String, selected: Boolean) {
        _restrictedPackages.value = if (selected) {
            _restrictedPackages.value + packageName
        } else {
            _restrictedPackages.value - packageName
        }
        if (!selected) _overrideDrafts.value = _overrideDrafts.value - packageName
        if (!draftOnly) {
            // App selection outside Settings (setup): the restricted set only; overrides of
            // removed apps are ignored when read, new apps get the default policy.
            viewModelScope.launch {
                app.restrictedAppsRepository.setRestricted(_restrictedPackages.value)
            }
        }
        loadRestrictedAppsInfo()
        loadInstalledApps()
    }

    fun removeRestrictedApp(packageName: String) {
        _restrictedPackages.value = _restrictedPackages.value - packageName
        _overrideDrafts.value = _overrideDrafts.value - packageName
        loadRestrictedAppsInfo()
        loadInstalledApps()
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

    // ---- policy editors (one per restricted app) -----------------------------------------------

    /** Saved policy of [pkg] (what "pending" is measured against). */
    fun originalFor(pkg: String): AppPolicy = _originalOverrides.value[pkg] ?: AppPolicy.DEFAULT

    /** The first edit of an app without its own draft starts from its saved policy. */
    private fun updateDraft(pkg: String, change: (PolicyDraft) -> PolicyDraft) {
        val current = _overrideDrafts.value[pkg] ?: PolicyDraft.of(originalFor(pkg))
        _overrideDrafts.value = _overrideDrafts.value + (pkg to change(current))
    }

    fun onPolicyModeChanged(pkg: String, mode: Mode) = updateDraft(pkg) { it.withMode(mode) }

    fun onPolicyExtensionToggled(pkg: String, allowed: Boolean) =
        updateDraft(pkg) { it.copy(extensionAllowed = allowed) }

    /** "Skip the wait for links" (specs/link-pass.md). */
    fun onPolicyLinkPassToggled(pkg: String, enabled: Boolean) =
        updateDraft(pkg) { it.copy(linkPass = enabled) }

    /** "Let the current reel finish" (specs/reel-finish.md). */
    fun onPolicyFinishReelToggled(pkg: String, enabled: Boolean) =
        updateDraft(pkg) { it.copy(finishReel = enabled) }

    /** Digits only; above the maximum snaps to the maximum (below the minimum waits for focus loss). */
    fun onPolicyFieldChanged(pkg: String, field: PolicyField, text: String) = updateDraft(pkg) { draft ->
        val digits = text.filter { it.isDigit() }.take(6)
        val max = PolicyDraft.range(field, draft.mode).last
        val n = digits.toIntOrNull()
        draft.withText(field, if (n != null && n > max) max.toString() else digits)
    }

    /**
     * An empty or too-small field goes back to the saved value, not an app default: resetting to
     * a default could itself look like lowering (or raising) the standards.
     */
    fun onPolicyFieldFocusLost(pkg: String, field: PolicyField) = updateDraft(pkg) { draft ->
        if (draft.isValid(field)) draft
        else draft.withText(field, PolicyDraft.valueOf(originalFor(pkg), field).coerceIn(PolicyDraft.range(field, draft.mode)).toString())
    }

    fun onConfirmationWaitChanged(text: String) {
        val digits = text.filter { it.isDigit() }.take(6)
        val n = digits.toIntOrNull()
        _confirmationWaitInput.value =
            if (n != null && n > AppPolicy.MAX_WAIT_SECONDS) AppPolicy.MAX_WAIT_SECONDS.toString() else digits
    }

    fun onConfirmationWaitFocusLost() {
        val n = _confirmationWaitInput.value.toIntOrNull()
        if (n == null || n < 1) _confirmationWaitInput.value = _originalConfirmationWait.value.toString()
    }

    /** Policies that differ from the default, of restricted apps only (as Save stores them). */
    private fun draftOverrides(): Map<String, AppPolicy> = PolicyResolver.normalized(
        _overrideDrafts.value.mapValues { (pkg, d) -> d.toPolicy(originalFor(pkg)) },
        _restrictedPackages.value
    )

    private fun draftConfirmationWait(): Int =
        _confirmationWaitInput.value.toIntOrNull()?.takeIf { it in 1..AppPolicy.MAX_WAIT_SECONDS }
            ?: _originalConfirmationWait.value

    // ---- save ---------------------------------------------------------------------------------

    /**
     * Re-reads what is actually persisted before judging the draft. The originals are loaded once
     * in init, but the suggestion overlay can add a restricted app while this screen is open;
     * such apps are merged into the draft so saving neither drops them nor counts as a removal.
     */
    private suspend fun refreshOriginals() {
        val settings = app.settingsRepository.settings.first()
        val restrictions = app.restrictions.first()
        val addedElsewhere = restrictions.restricted - _originalRestrictedPackages.value
        _originalConfirmationWait.value = settings.confirmationWaitSeconds
        _originalRestrictedPackages.value = restrictions.restricted
        _originalOverrides.value = restrictions.overrides
        if (addedElsewhere.isNotEmpty()) {
            _restrictedPackages.value = _restrictedPackages.value + addedElsewhere
            loadRestrictedAppsInfo()
        }
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            refreshOriginals()
            val draft = draftValues()
            val original = originalValues()
            val reducing = isRestrictionReducing(
                originalRestricted = original.restricted,
                draftRestricted = draft.restricted,
                originalOverrides = original.overrides,
                draftOverrides = draft.overrides,
                originalConfirmationWaitSeconds = original.confirmationWait,
                draftConfirmationWaitSeconds = draft.confirmationWait
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
            // The persisted wait: a draft that shortens it does not apply to its own Save.
            val waitTimeSeconds = _originalConfirmationWait.value.coerceAtLeast(1)
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
            val v = draftValues()
            withContext(Dispatchers.IO) {
                val settings = app.settingsRepository.settings.first()
                app.settingsRepository.setSettings(settings.copy(confirmationWaitSeconds = v.confirmationWait, blockBackground = v.background))
                app.restrictedAppsRepository.setRestrictedWithOverrides(v.restricted, v.overrides)
            }
            // Keep the photo still offered in the picker plus whatever was just persisted (the
            // user may have removed the photo while the save was in flight); drop the rest.
            app.blockBackgroundStore.cleanup(
                setOfNotNull(_customBackgroundFileName.value, (v.background as? BlockBackground.Custom)?.fileName)
            )
            _originalOverrides.value = v.overrides
            _originalConfirmationWait.value = v.confirmationWait
            _originalRestrictedPackages.value = v.restricted
            _originalBlockBackground.value = v.background
            // Show what was stored (invalid fields have fallen back, waits were sanitized).
            _overrideDrafts.value = v.overrides.mapValues { PolicyDraft.of(it.value) }
            _confirmationWaitInput.value = v.confirmationWait.toString()
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
