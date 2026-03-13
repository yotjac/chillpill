package com.chillpill.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chillpill.ChillpillApp
import com.chillpill.ui.common.AppIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: ChillpillApp,
    onBack: () -> Unit,
    onEditRestrictedApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app))
    val waitTimeSecondsInput by viewModel.waitTimeSecondsInput.collectAsStateWithLifecycle()
    val gracePeriodMinutesInput by viewModel.gracePeriodMinutesInput.collectAsStateWithLifecycle()
    val restrictedAppsInfo by viewModel.restrictedAppsInfo.collectAsStateWithLifecycle()
    val expandedAppPackage by viewModel.expandedAppPackage.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
        ) {
            // Configuration section
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
            OutlinedTextField(
                value = waitTimeSecondsInput,
                onValueChange = { viewModel.onWaitTimeChanged(it) },
                label = { Text("Wait time (seconds)") },
                isError = waitTimeInvalid,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) viewModel.onGracePeriodFocusLost() },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // Restricted apps section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Restricted apps",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEditRestrictedApps) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit restricted apps")
                }
            }
            if (restrictedAppsInfo.isEmpty()) {
                Text(
                    text = "No apps restricted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
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
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
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
                                    IconButton(
                                        onClick = { viewModel.removeRestrictedApp(appInfo.packageName) }
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Remove from restricted"
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = if (isExpanded) "Collapse settings" else "Expand settings"
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
