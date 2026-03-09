package com.chillpill.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardOptions
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
    modifier: Modifier = Modifier
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app))
    val waitTimeSecondsInput by viewModel.waitTimeSecondsInput.collectAsStateWithLifecycle()
    val gracePeriodMinutesInput by viewModel.gracePeriodMinutesInput.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val monitoredPackages by viewModel.monitoredPackages.collectAsStateWithLifecycle()
    val appSearchQuery by viewModel.appSearchQuery.collectAsStateWithLifecycle()
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

            // Monitored apps section
            Text(
                text = "Monitored apps",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            OutlinedTextField(
                value = appSearchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                label = { Text("Search apps") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            val query = appSearchQuery.trim().lowercase()
            val filteredApps = if (query.isEmpty()) installedApps
                else installedApps.filter { it.label.lowercase().contains(query) }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { appInfo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = appInfo.packageName in monitoredPackages,
                            onCheckedChange = { viewModel.onMonitoredChanged(appInfo.packageName, it) }
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        AppIcon(packageName = appInfo.packageName, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = appInfo.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
