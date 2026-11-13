package com.chillpill.ui.appblock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chillpill.R
import kotlin.math.roundToInt

/**
 * "How long do you need?" for PICK apps (specs/per-app-modes.md): a minute slider (1–30, or 1–90
 * after "need longer?") with quick-set chips. Choosing only moves the value; the screen's own
 * "Open for N min" button commits it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationPicker(
    title: String,
    minutes: Int,
    extended: Boolean,
    onMinutesChange: (Int) -> Unit,
    onToggleRange: () -> Unit,
    modifier: Modifier = Modifier,
    /** False while the picker is laid out but not revealed yet (re-intervention wait). */
    enabled: Boolean = true
) {
    val max = if (extended) AppBlockViewModel.PICK_EXTENDED_MAX_MINUTES else AppBlockViewModel.PICK_MAX_MINUTES
    val quick = if (extended) AppBlockViewModel.PICK_EXTENDED_QUICK_MINUTES else AppBlockViewModel.PICK_QUICK_MINUTES
    val haptics = LocalHapticFeedback.current
    val minutesLabel = stringResource(R.string.pick_minutes, minutes)

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = minutesLabel,
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Slider(
            value = minutes.toFloat(),
            onValueChange = {
                val v = it.roundToInt().coerceIn(1, max)
                if (v != minutes) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMinutesChange(v)
                }
            },
            valueRange = 1f..max.toFloat(),
            enabled = enabled,
            steps = max - 2, // one stop per minute
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = minutesLabel }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            for (q in quick) {
                FilterChip(
                    selected = q == minutes,
                    onClick = { onMinutesChange(q) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = q.toString(),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        TextButton(onClick = onToggleRange, enabled = enabled, modifier = Modifier.padding(top = 2.dp)) {
            Text(
                text = if (extended) {
                    stringResource(R.string.pick_shorter_range, AppBlockViewModel.PICK_MAX_MINUTES)
                } else {
                    stringResource(R.string.pick_need_longer)
                }
            )
        }
    }
}

/** What a block screen needs to show the picker; null for apps that are not PICK. */
class PickState(
    val minutes: Int,
    val extended: Boolean,
    /** 0-s wait: no fill animation before the picker. */
    val instant: Boolean,
    val onMinutesChange: (Int) -> Unit,
    val onToggleRange: () -> Unit
)

/** Collects the picker state of [viewModel]; null unless its app is PICK. */
@Composable
fun rememberPickState(viewModel: AppBlockViewModel): PickState? {
    val isPick by viewModel.isPick.collectAsStateWithLifecycle()
    val minutes by viewModel.pickMinutes.collectAsStateWithLifecycle()
    val extended by viewModel.pickExtended.collectAsStateWithLifecycle()
    val instant by viewModel.instant.collectAsStateWithLifecycle()
    if (!isPick) return null
    return PickState(minutes, extended, instant, viewModel::onPickMinutesChanged, viewModel::onPickRangeToggled)
}
