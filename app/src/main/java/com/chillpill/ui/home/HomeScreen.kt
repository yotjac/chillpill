package com.chillpill.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chillpill.ChillpillApp
import com.chillpill.ui.common.AppIcon
import java.util.Calendar

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
    val restrictedPackages by viewModel.restrictedPackages.collectAsStateWithLifecycle()
    val todayAttempts by viewModel.todayAttempts.collectAsStateWithLifecycle()
    val todayEntered by viewModel.todayEntered.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshPermissions() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
        viewModel.refreshStats()
    }

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
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                HomeGreeting()
                Spacer(modifier = Modifier.height(24.dp))
                HeroStatsCard(
                    attempts = todayAttempts,
                    entered = todayEntered
                )
                if (restrictedPackages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(32.dp))
                    RestrictedAppsRow(restrictedPackages = restrictedPackages)
                }
                Spacer(modifier = Modifier.height(32.dp))
                NavigationCards(
                    onOpenSettings = onOpenSettings,
                    onOpenStatistics = onOpenStatistics
                )
                Spacer(modifier = Modifier.height(32.dp))
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
private fun HomeGreeting(modifier: Modifier = Modifier) {
    val calendar = Calendar.getInstance()
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun HeroStatsCard(
    attempts: Int,
    entered: Int,
    modifier: Modifier = Modifier
) {
    val totalBlocked = (attempts - entered).coerceAtLeast(0)

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedContent(
                    targetState = totalBlocked,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "hero_stat_number"
                ) { value ->
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 72.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    text = "distractions blocked today",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun RestrictedAppsRow(
    restrictedPackages: Set<String>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = "Your Restricted Apps",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val packagesList = restrictedPackages.sorted()
            val maxVisible = 6
            val visible = packagesList.take(maxVisible)
            visible.forEach { packageName ->
                AppIcon(
                    packageName = packageName,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            }
            val remaining = packagesList.size - visible.size
            if (remaining > 0) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+$remaining",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationCards(
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NavigationCard(
            title = "Settings",
            subtitle = "Fine-tune your limits",
            icon = Icons.Outlined.Settings,
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f)
        )
        NavigationCard(
            title = "Statistics",
            subtitle = "See your progress",
            icon = Icons.Outlined.BarChart,
            onClick = onOpenStatistics,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NavigationCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(120.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(),
        elevation = CardDefaults.elevatedCardElevation(),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
