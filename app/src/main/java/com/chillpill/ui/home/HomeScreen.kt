package com.chillpill.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Button
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chillpill.ChillpillApp

@Composable
fun HomeScreen(
    app: ChillpillApp,
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
    onFixPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
        }
    )
    val permissionsOk by viewModel.permissionsOk.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshPermissions() }

    Column(modifier = modifier.fillMaxSize()) {
        if (!permissionsOk) {
            PermissionBanner(onFixHereClick = onFixPermissions)
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
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Settings")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenStatistics,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Statistics")
            }
        }
    }
}

private val AlertBannerBackground = Color(0xFFFCE8E8)
private val AlertBannerText = Color(0xFF991B1B)

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
            append("Usage access and accessibility permission required: ")
            pushStringAnnotation(tag = "fix_here", annotation = "")
            with(SpanStyle(textDecoration = TextDecoration.Underline)) {
                append("fix here")
            }
            pop()
        }
        ClickableText(
            text = annotatedString,
            style = MaterialTheme.typography.bodyMedium.copy(color = AlertBannerText),
            modifier = Modifier.padding(16.dp),
            onClick = { offset ->
                annotatedString.getStringAnnotations("fix_here", offset, offset + 1).firstOrNull()?.let {
                    onFixHereClick()
                }
            }
        )
    }
}
