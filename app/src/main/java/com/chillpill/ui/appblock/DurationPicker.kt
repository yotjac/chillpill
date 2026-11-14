package com.chillpill.ui.appblock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chillpill.R
import kotlin.math.roundToInt

/**
 * Top padding the screens put on the picker's (vertically centred) slot, so the picker sits a
 * little below the exact centre (specs/waiting-screen-polish.md): its centre moves down by half.
 */
val PickerTopOffset: Dp = 48.dp

/**
 * "How long do you need?" for PICK apps (specs/per-app-modes.md): a minute slider (1–30, or 1–90
 * after "need longer?") with −/+ one-minute buttons either side and quick-set chips. Choosing only
 * moves the value; the screen's own "Open for N min" button commits it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationPicker(
    title: String,
    /** Null: nothing picked yet (no chip selected, thumb at the start). */
    minutes: Int?,
    extended: Boolean,
    onMinutesChange: (Int) -> Unit,
    onToggleRange: () -> Unit,
    modifier: Modifier = Modifier,
    /** False while the picker is laid out but not revealed yet (re-intervention wait). */
    enabled: Boolean = true,
    /** Link picker: the whole range is 1–[limit], without "need longer?" (specs/link-pass.md). */
    limit: Int? = null
) {
    val max = limit
        ?: if (extended) AppBlockViewModel.PICK_EXTENDED_MAX_MINUTES else AppBlockViewModel.PICK_MAX_MINUTES
    val quick = when {
        limit != null -> AppBlockViewModel.linkQuickMinutes(limit)
        extended -> AppBlockViewModel.PICK_EXTENDED_QUICK_MINUTES
        else -> AppBlockViewModel.PICK_QUICK_MINUTES
    }
    val haptics = LocalHapticFeedback.current
    val minutesLabel = if (minutes != null) {
        stringResource(R.string.pick_minutes, minutes)
    } else {
        stringResource(R.string.pick_minutes_none)
    }

    // A soft card frames title, value, slider, chips and "need longer?" as one unit.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 16.dp,
                    // "need longer?" brings its own touch padding at the bottom.
                    bottom = if (limit == null) 4.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
            // A 1-minute limit leaves nothing to slide or step: the label and the chip say it all.
            if (max > 1) Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Nothing picked yet: either button picks 1 min, where the thumb is drawn.
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onMinutesChange(if (minutes == null) 1 else (minutes - 1).coerceIn(1, max))
                    },
                    enabled = enabled && (minutes == null || minutes > 1)
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.pick_minus_one))
                }
                Slider(
                    value = (minutes ?: 1).toFloat(),
                    onValueChange = {
                        val v = it.roundToInt().coerceIn(1, max)
                        if (v != minutes) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onMinutesChange(v)
                        }
                    },
                    valueRange = 1f..max.toFloat(),
                    enabled = enabled,
                    steps = (max - 2).coerceAtLeast(0), // one stop per minute
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = minutesLabel }
                )
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onMinutesChange(if (minutes == null) 1 else (minutes + 1).coerceIn(1, max))
                    },
                    enabled = enabled && (minutes == null || minutes < max)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.pick_plus_one))
                }
            }
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
            if (limit == null) {
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
    }
}

/** What a block screen needs to show the picker; null for apps that are not PICK. */
class PickState(
    /** Null until the user picks (PICK apps start empty). */
    val minutes: Int?,
    val extended: Boolean,
    /** 0-s wait: no fill animation before the picker. */
    val instant: Boolean,
    val onMinutesChange: (Int) -> Unit,
    val onToggleRange: () -> Unit,
    /** Link picker: its limit in minutes; null for a PICK app's picker. */
    val limit: Int? = null,
    /** Link picker capped because a link was opened in the last 15 min: say so. */
    val limited: Boolean = false
)

/** Collects the picker state of [viewModel]; null unless its app is PICK. */
@Composable
fun rememberPickState(viewModel: AppBlockViewModel): PickState? {
    val isPick by viewModel.isPick.collectAsStateWithLifecycle()
    val minutes by viewModel.pickMinutes.collectAsStateWithLifecycle()
    val extended by viewModel.pickExtended.collectAsStateWithLifecycle()
    val instant by viewModel.instant.collectAsStateWithLifecycle()
    val limit by viewModel.pickLimit.collectAsStateWithLifecycle()
    if (!isPick) return null
    return PickState(
        minutes, extended, instant, viewModel::onPickMinutesChanged, viewModel::onPickRangeToggled,
        limit, limited = viewModel.isLink && viewModel.linkLimited
    )
}
