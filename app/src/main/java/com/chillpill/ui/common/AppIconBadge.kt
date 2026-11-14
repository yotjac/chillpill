package com.chillpill.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The target app's icon in a circle, shown centred near the top of the waiting screens
 * (specs/waiting-screen-polish.md). Decorative: the app name is already in the screen's text.
 */
@Composable
fun AppIconBadge(packageName: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(72.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(packageName = packageName, modifier = Modifier.size(48.dp))
        }
    }
}
