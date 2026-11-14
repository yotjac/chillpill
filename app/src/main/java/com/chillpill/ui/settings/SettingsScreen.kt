package com.chillpill.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import com.chillpill.data.restricted.AppPolicy
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import android.content.Context
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chillpill.ui.common.AppIcon
import androidx.compose.ui.res.stringResource
import com.chillpill.R

internal val PendingWaitBorderColor = Color(0xFFFF9800)
internal val PendingWaitLabelColor = Color(0xFFE65100)
private val RestrictedPendingContainerColor = Color(0xFFFFE0B2)
private val RestrictedPendingContentColor = Color(0xFFF57C00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onEditRestrictedApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overrideDrafts by viewModel.overrideDrafts.collectAsStateWithLifecycle()
    val originalOverrides by viewModel.originalOverrides.collectAsStateWithLifecycle()
    val confirmationWaitInput by viewModel.confirmationWaitInput.collectAsStateWithLifecycle()
    val originalConfirmationWait by viewModel.originalConfirmationWait.collectAsStateWithLifecycle()
    val originalRestrictedPackages by viewModel.originalRestrictedPackages.collectAsStateWithLifecycle()
    val restrictedPackages by viewModel.restrictedPackages.collectAsStateWithLifecycle()
    val restrictedAppsInfo by viewModel.restrictedAppsInfo.collectAsStateWithLifecycle()
    val expandedAppPackage by viewModel.expandedAppPackage.collectAsStateWithLifecycle()
    val blockBackground by viewModel.blockBackground.collectAsStateWithLifecycle()
    val originalBlockBackground by viewModel.originalBlockBackground.collectAsStateWithLifecycle()
    val customBackgroundFileName by viewModel.customBackgroundFileName.collectAsStateWithLifecycle()
    val backgroundImportFailed by viewModel.backgroundImportFailed.collectAsStateWithLifecycle()
    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()
    val showConfirmationScreen by viewModel.showConfirmationScreen.collectAsStateWithLifecycle()
    val confirmationProgress by viewModel.confirmationProgress.collectAsStateWithLifecycle()
    val confirmationPhase by viewModel.confirmationPhase.collectAsStateWithLifecycle()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    val importFailedMessage = stringResource(R.string.settings_background_import_failed)
    val pickBackgroundImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.onCustomBackgroundPicked(uri) }

    LaunchedEffect(backgroundImportFailed) {
        if (backgroundImportFailed) {
            snackbarHostState.showSnackbar(importFailedMessage)
            viewModel.onBackgroundImportErrorShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (showConfirmationScreen) {
                            Text(
                                text = stringResource(R.string.settings_waiting),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        } else {
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    val imm =
                                        view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                    imm?.hideSoftInputFromWindow(view.windowToken, 0)
                                    scope.launch {
                                        delay(300)
                                        viewModel.onSaveClicked()
                                    }
                                },
                                enabled = hasChanges,
                                modifier = Modifier.padding(end = 16.dp)
                            ) {
                                Text(stringResource(R.string.action_save))
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            fun original(pkg: String) = originalOverrides[pkg] ?: AppPolicy.DEFAULT
            fun effective(pkg: String) = overrideDrafts[pkg]?.toPolicy(original(pkg)) ?: original(pkg)
            val overridesDirty = restrictedPackages.any { effective(it) != original(it) }
            val restrictedAdded = (restrictedPackages - originalRestrictedPackages).size
            val restrictedDeleted = (originalRestrictedPackages - restrictedPackages).size
            val deltaRemoved = stringResource(R.string.settings_delta_removed, restrictedDeleted)
            val deltaAdded = stringResource(R.string.settings_delta_added, restrictedAdded)
            val restrictedDeltaLabel = buildString {
                if (restrictedDeleted > 0) append(deltaRemoved)
                if (restrictedDeleted > 0 && restrictedAdded > 0) append(" ")
                if (restrictedAdded > 0) append(deltaAdded)
            }
            val restrictedDirty = restrictedPackages != originalRestrictedPackages || overridesDirty

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "loosening") {
                    Column {
                        SectionTitle(stringResource(R.string.settings_section_loosening))
                        val valid = confirmationWaitInput.toIntOrNull()?.let { it in 1..AppPolicy.MAX_WAIT_SECONDS } ?: false
                        PolicyNumberField(
                            label = stringResource(R.string.settings_confirmation_wait_label),
                            supporting = stringResource(R.string.settings_confirmation_wait_hint),
                            text = confirmationWaitInput,
                            isError = !valid,
                            pending = valid && confirmationWaitInput.toInt() != originalConfirmationWait,
                            onValueChange = viewModel::onConfirmationWaitChanged,
                            onFocusLost = viewModel::onConfirmationWaitFocusLost
                        )
                    }
                }

                item(key = "background") {
                    Column {
                        SectionTitle(stringResource(R.string.settings_section_background))
                        BlockBackgroundPicker(
                            selected = blockBackground,
                            customFileName = customBackgroundFileName,
                            isPending = blockBackground != originalBlockBackground,
                            onBuiltInSelected = viewModel::onBuiltInBackgroundSelected,
                            onCustomSelected = viewModel::onCustomBackgroundSelected,
                            onPickCustom = {
                                pickBackgroundImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onRemoveCustom = viewModel::onCustomBackgroundRemoved,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item(key = "restricted-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_restricted_apps),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (restrictedDirty) {
                                    RestrictedPendingContentColor
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            if (restrictedDeltaLabel.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = restrictedDeltaLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )
                            }
                        }
                        IconButton(onClick = onEditRestrictedApps) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.settings_edit_restricted_apps))
                        }
                    }
                }

                if (restrictedAppsInfo.isEmpty()) {
                    item(key = "restricted-empty") {
                        Text(
                            text = stringResource(R.string.settings_no_apps_restricted),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )
                    }
                }

                items(restrictedAppsInfo, key = { it.packageName }) { appInfo ->
                    val pkg = appInfo.packageName
                    val isExpanded = expandedAppPackage == pkg
                    val isNewlyAdded = pkg in restrictedPackages && pkg !in originalRestrictedPackages
                    val appOriginal = original(pkg)
                    val appEffective = effective(pkg)
                    val cardPending = isNewlyAdded || appEffective != appOriginal
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (cardPending) {
                            CardDefaults.cardColors(
                                containerColor = RestrictedPendingContainerColor,
                                contentColor = RestrictedPendingContentColor
                            )
                        } else {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onToggleExpanded(pkg) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(
                                    packageName = pkg,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.size(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = appInfo.label,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = policySummary(appEffective),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (cardPending) {
                                            RestrictedPendingContentColor
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                if (isNewlyAdded) {
                                    Text(
                                        text = stringResource(R.string.settings_app_new_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RestrictedPendingContentColor
                                    )
                                    Spacer(modifier = Modifier.size(4.dp))
                                }
                                IconButton(onClick = { viewModel.removeRestrictedApp(pkg) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.settings_remove_from_restricted)
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (isExpanded) {
                                        stringResource(R.string.settings_collapse)
                                    } else {
                                        stringResource(R.string.settings_expand)
                                    }
                                )
                            }
                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                // Own surface: the editor keeps the theme's colours (and contrast)
                                // even when the card is tinted as pending.
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                                    ) {
                                        PolicyEditor(
                                            appName = appInfo.label,
                                            draft = overrideDrafts[pkg] ?: PolicyDraft.of(appOriginal),
                                            original = appOriginal,
                                            onModeChange = { viewModel.onPolicyModeChanged(pkg, it) },
                                            onFieldChange = { f, t -> viewModel.onPolicyFieldChanged(pkg, f, t) },
                                            onFieldFocusLost = { viewModel.onPolicyFieldFocusLost(pkg, it) },
                                            onExtensionChange = { viewModel.onPolicyExtensionToggled(pkg, it) },
                                            onLinkPassChange = { viewModel.onPolicyLinkPassToggled(pkg, it) },
                                            modifier = Modifier.padding(top = 12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showConfirmationScreen) {
            SettingsConfirmationScreen(
                phase = confirmationPhase,
                progress = confirmationProgress,
                onCancel = viewModel::onConfirmationBack,
                onSave = viewModel::onConfirmationSave
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}
