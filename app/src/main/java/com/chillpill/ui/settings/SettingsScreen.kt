package com.chillpill.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.content.Context
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chillpill.ui.common.AppIcon

private val PendingWaitBorderColor = Color(0xFFFF9800)
private val PendingWaitLabelColor = Color(0xFFE65100)
private val PendingGraceBorderColor = Color(0xFFFFB300)
private val PendingGraceLabelColor = Color(0xFFD84315)
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
    val waitTimeSecondsInput by viewModel.waitTimeSecondsInput.collectAsStateWithLifecycle()
    val gracePeriodMinutesInput by viewModel.gracePeriodMinutesInput.collectAsStateWithLifecycle()
    val originalWaitTime by viewModel.originalWaitTimeSeconds.collectAsStateWithLifecycle()
    val originalGracePeriod by viewModel.originalGracePeriodMinutes.collectAsStateWithLifecycle()
    val originalRestrictedPackages by viewModel.originalRestrictedPackages.collectAsStateWithLifecycle()
    val restrictedPackages by viewModel.restrictedPackages.collectAsStateWithLifecycle()
    val restrictedAppsInfo by viewModel.restrictedAppsInfo.collectAsStateWithLifecycle()
    val reInterventionDisabledPackages by viewModel.reInterventionDisabledPackages.collectAsStateWithLifecycle()
    val originalReInterventionDisabledPackages by viewModel.originalReInterventionDisabledPackages.collectAsStateWithLifecycle()
    val expandedAppPackage by viewModel.expandedAppPackage.collectAsStateWithLifecycle()
    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()
    val showConfirmationScreen by viewModel.showConfirmationScreen.collectAsStateWithLifecycle()
    val confirmationProgress by viewModel.confirmationProgress.collectAsStateWithLifecycle()
    val confirmationPhase by viewModel.confirmationPhase.collectAsStateWithLifecycle()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val defaultFieldColors = OutlinedTextFieldDefaults.colors()
    val waitPendingColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = PendingWaitBorderColor,
        unfocusedBorderColor = PendingWaitBorderColor,
        focusedLabelColor = PendingWaitLabelColor,
        unfocusedLabelColor = PendingWaitLabelColor,
        cursorColor = PendingWaitBorderColor
    )
    val gracePendingColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = PendingGraceBorderColor,
        unfocusedBorderColor = PendingGraceBorderColor,
        focusedLabelColor = PendingGraceLabelColor,
        unfocusedLabelColor = PendingGraceLabelColor,
        cursorColor = PendingGraceBorderColor
    )

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (showConfirmationScreen) {
                            Text(
                                text = "Waiting…",
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
                                Text("Save")
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = "Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    val waitTimeInvalid = waitTimeSecondsInput.isEmpty() ||
                        (waitTimeSecondsInput.toIntOrNull() ?: 0) !in 1..7200
                    val gracePeriodInvalid = gracePeriodMinutesInput.isEmpty() ||
                        (gracePeriodMinutesInput.toIntOrNull() ?: 0) !in 1..1440
                    val draftWait = waitTimeSecondsInput.toIntOrNull()?.coerceIn(1, 7200) ?: originalWaitTime
                    val draftGrace = gracePeriodMinutesInput.toIntOrNull()?.coerceIn(1, 1440) ?: originalGracePeriod
                    val waitPending = draftWait != originalWaitTime && !waitTimeInvalid
                    val gracePending = draftGrace != originalGracePeriod && !gracePeriodInvalid

                    OutlinedTextField(
                        value = waitTimeSecondsInput,
                        onValueChange = { viewModel.onWaitTimeChanged(it) },
                        label = { Text("Wait time (seconds)") },
                        isError = waitTimeInvalid,
                        colors = if (waitPending) waitPendingColors else defaultFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) viewModel.onWaitTimeFocusLost() },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = gracePeriodMinutesInput,
                        onValueChange = { viewModel.onGracePeriodChanged(it) },
                        label = { Text("Grace period (minutes)") },
                        isError = gracePeriodInvalid,
                        colors = if (gracePending) gracePendingColors else defaultFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) viewModel.onGracePeriodFocusLost() },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    val restrictedAdded = (restrictedPackages - originalRestrictedPackages).size
                    val restrictedDeleted = (originalRestrictedPackages - restrictedPackages).size
                    val restrictedDeltaLabel = buildString {
                        if (restrictedDeleted > 0) append("-$restrictedDeleted")
                        if (restrictedDeleted > 0 && restrictedAdded > 0) append(" ")
                        if (restrictedAdded > 0) append("+$restrictedAdded")
                    }
                    val restrictedDirty = restrictedPackages != originalRestrictedPackages ||
                        reInterventionDisabledPackages != originalReInterventionDisabledPackages

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Restricted apps",
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
                            Icon(Icons.Filled.Edit, contentDescription = "Edit restricted apps")
                        }
                    }
                    if (restrictedAppsInfo.isEmpty()) {
                        Text(
                            text = "No apps restricted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(restrictedAppsInfo, key = { it.packageName }) { appInfo ->
                                val isExpanded = expandedAppPackage == appInfo.packageName
                                val isNewlyAdded = appInfo.packageName in restrictedPackages &&
                                    appInfo.packageName !in originalRestrictedPackages
                                val reInterventionDirty =
                                    (appInfo.packageName in reInterventionDisabledPackages) !=
                                        (appInfo.packageName in originalReInterventionDisabledPackages)
                                val cardPending = isNewlyAdded || reInterventionDirty
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
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.onToggleExpanded(appInfo.packageName) }
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AppIcon(
                                                packageName = appInfo.packageName,
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(modifier = Modifier.size(12.dp))
                                            Text(
                                                text = appInfo.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isNewlyAdded) {
                                                Text(
                                                    text = "new",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (cardPending) {
                                                        RestrictedPendingContentColor
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    }
                                                )
                                                Spacer(modifier = Modifier.size(4.dp))
                                            }
                                            IconButton(
                                                onClick = { viewModel.removeRestrictedApp(appInfo.packageName) }
                                            ) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = "Remove from restricted"
                                                )
                                            }
                                            Icon(
                                                imageVector = if (isExpanded) {
                                                    Icons.Filled.ExpandLess
                                                } else {
                                                    Icons.Filled.ExpandMore
                                                },
                                                contentDescription = if (isExpanded) {
                                                    "Collapse settings"
                                                } else {
                                                    "Expand settings"
                                                }
                                            )
                                        }
                                        AnimatedVisibility(
                                            visible = isExpanded,
                                            enter = expandVertically(),
                                            exit = shrinkVertically()
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp)
                                            ) {
                                                HorizontalDivider(
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Block again after grace",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Switch(
                                                        checked = appInfo.reInterventionEnabled,
                                                        onCheckedChange = {
                                                            viewModel.onReInterventionToggled(
                                                                appInfo.packageName,
                                                                it
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        }
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
