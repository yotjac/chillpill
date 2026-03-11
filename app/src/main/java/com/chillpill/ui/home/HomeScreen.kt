package com.chillpill.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chillpill.ChillpillApp

@Composable
fun HomeScreen(
    app: ChillpillApp,
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
    onFixPermissions: () -> Unit,
    onFixUsageAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
        }
    )
    val permissionsOk by viewModel.permissionsOk.collectAsStateWithLifecycle()
    val showUsageAccessBanner by viewModel.showUsageAccessBanner.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshPermissions() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!permissionsOk) {
                PermissionBanner(onFixHereClick = onFixPermissions)
            }
            if (showUsageAccessBanner) {
                DismissiblePermissionBanner(
                    onFixHereClick = onFixUsageAccess,
                    onDismiss = { viewModel.dismissUsageAccessBanner() }
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Chillpill",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Settings")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onOpenStatistics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Statistics")
                }
            }
        }
    }
}

private val AlertBannerBackground = Color(0xFFFCE8E8)
private val AlertBannerText = Color(0xFF991B1B)

private val AlertBannerLink = Color(0xFF7F1D1D)

@Composable
private fun PermissionBanner(
    onFixHereClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AlertBannerBackground,
        shape = RoundedCornerShape(0.dp)
    ) {
        val annotatedString = buildAnnotatedString {
            append("Accessibility permission required: ")
            val fixHereStart = length
            append("fix here")
            addStringAnnotation(tag = "fix_here", annotation = "", start = fixHereStart, end = length)
            addStyle(
                style = SpanStyle(color = AlertBannerLink, textDecoration = TextDecoration.Underline),
                start = fixHereStart,
                end = length
            )
        }
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Permission required",
                tint = AlertBannerText,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            ClickableText(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = AlertBannerText,
                    textDecoration = TextDecoration.None
                ),
                onClick = { offset ->
                    annotatedString.getStringAnnotations("fix_here", offset, offset + 1).firstOrNull()?.let {
                        onFixHereClick()
                    }
                }
            )
        }
    }
}

@Composable
private fun DismissiblePermissionBanner(
    onFixHereClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AlertBannerBackground,
        shape = RoundedCornerShape(0.dp)
    ) {
        val annotatedString = buildAnnotatedString {
            append("Usage access improves app sorting: ")
            val fixHereStart = length
            append("fix here")
            addStringAnnotation(tag = "fix_here", annotation = "", start = fixHereStart, end = length)
            addStyle(
                style = SpanStyle(color = AlertBannerLink, textDecoration = TextDecoration.Underline),
                start = fixHereStart,
                end = length
            )
        }
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Permission optional",
                tint = AlertBannerText,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            ClickableText(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = AlertBannerText,
                    textDecoration = TextDecoration.None
                ),
                modifier = Modifier.weight(1f),
                onClick = { offset ->
                    annotatedString.getStringAnnotations("fix_here", offset, offset + 1).firstOrNull()?.let {
                        onFixHereClick()
                    }
                }
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = AlertBannerText
                )
            }
        }
    }
}
