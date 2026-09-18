package com.chillpill.ui.gracewarning

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chillpill.R
import com.chillpill.ui.theme.ChillpillTheme
import kotlinx.coroutines.delay

private val PillShape = RoundedCornerShape(22.dp)

/**
 * The grace-expiry warning: a small, non-modal strip shown over a restricted app for the last
 * seconds of its grace period, offering one "+10 s" extension (see specs/grace-expiry-warning.md).
 *
 * Stateless apart from its own countdown, which is derived from [deadlineWallMs] rather than
 * counted down locally, so an extension simply re-keys the effect with the new deadline.
 *
 * @param deadlineWallMs epoch millis at which grace runs out.
 * @param canExtend false once the single extension has been used: countdown only, no button.
 * @param dismissible false for the second warning, shown once the extension has been spent: the
 *   dismiss button goes too, so the coming re-block is announced whatever the user tapped earlier.
 */
@Composable
fun GraceWarningPill(
    deadlineWallMs: Long,
    canExtend: Boolean,
    dismissible: Boolean,
    onExtend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = System::currentTimeMillis
) {
    var secondsLeft by remember { mutableIntStateOf(secondsUntil(deadlineWallMs, nowMillis())) }

    // The extension only reaches the pill on the service's next tick (up to a second later), so the
    // tap confirms itself immediately: the row turns into "10 s added" and gives a little bounce,
    // instead of the countdown carrying on as if the tap had missed.
    var justExtended by remember(deadlineWallMs) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val bounce by animateFloatAsState(
        targetValue = if (justExtended) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "graceWarningPillBounce"
    )
    val containerColor by animateColorAsState(
        targetValue = if (justExtended) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
        },
        label = "graceWarningPillContainer"
    )
    val contentColor = if (justExtended) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    LaunchedEffect(deadlineWallMs) {
        while (true) {
            val remaining = deadlineWallMs - nowMillis()
            secondsLeft = secondsUntil(deadlineWallMs, nowMillis())
            if (remaining <= 0L) break
            // Tick on the second boundary so the number never appears to skip or stutter.
            delay((remaining % 1000L).takeIf { it > 0L } ?: 1000L)
        }
    }

    Row(
        modifier = modifier
            .widthIn(max = 320.dp)
            .graphicsLayer {
                scaleX = bounce
                scaleY = bounce
            }
            .shadow(6.dp, PillShape)
            .background(containerColor, PillShape)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (justExtended) Icons.Filled.Check else Icons.Outlined.Timer,
            contentDescription = null,
            tint = if (justExtended) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = if (justExtended) {
                stringResource(R.string.grace_warning_extended)
            } else {
                pluralStringResource(R.plurals.grace_warning_countdown, secondsLeft, secondsLeft)
            },
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // On the countdown itself rather than the row: a live region announces the node whose
            // own text changes, and marking the row would need mergeDescendants and swallow the
            // buttons' semantics.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (canExtend && !justExtended) {
            val extendDescription = stringResource(R.string.grace_warning_extend_description)
            FilledTonalButton(
                onClick = {
                    // Confirm before the deadline change makes its way back to us.
                    justExtended = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onExtend()
                },
                shape = PillShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .semantics { contentDescription = extendDescription }
            ) {
                Text(
                    text = stringResource(R.string.grace_warning_extend),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
        if (dismissible && !justExtended) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.grace_warning_dismiss_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Whole seconds still to run, rounded up, so "1 s" is shown for the whole last second. */
private fun secondsUntil(deadlineWallMs: Long, now: Long): Int {
    val remaining = deadlineWallMs - now
    if (remaining <= 0L) return 0
    return ((remaining + 999L) / 1000L).toInt()
}

@Preview(showBackground = true, name = "Extension available")
@Composable
private fun GraceWarningPillPreview() {
    ChillpillTheme(applyWindowDecor = false) {
        GraceWarningPill(
            deadlineWallMs = 9_000L,
            canExtend = true,
            dismissible = true,
            onExtend = {},
            onDismiss = {},
            nowMillis = { 0L }
        )
    }
}

@Preview(showBackground = true, name = "Extension used", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GraceWarningPillSpentPreview() {
    ChillpillTheme(darkTheme = true, applyWindowDecor = false) {
        GraceWarningPill(
            deadlineWallMs = 7_000L,
            canExtend = false,
            dismissible = true,
            onExtend = {},
            onDismiss = {},
            nowMillis = { 0L }
        )
    }
}

@Preview(showBackground = true, name = "Final seconds, not dismissible")
@Composable
private fun GraceWarningPillFinalPreview() {
    ChillpillTheme(applyWindowDecor = false) {
        GraceWarningPill(
            deadlineWallMs = 4_000L,
            canExtend = false,
            dismissible = false,
            onExtend = {},
            onDismiss = {},
            nowMillis = { 0L }
        )
    }
}
