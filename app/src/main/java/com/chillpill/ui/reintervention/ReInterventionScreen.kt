package com.chillpill.ui.reintervention

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chillpill.R
import com.chillpill.ui.appblock.AppBlockPhase
import com.chillpill.ui.appblock.DurationPicker
import com.chillpill.ui.appblock.PickState

@Composable
fun ReInterventionScreen(
    phase: AppBlockPhase,
    progress: Float,
    appName: String,
    onContinue: () -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier,
    pick: PickState? = null
) {
    val animatedProgress: Float by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "reInterventionOverlayProgress"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Black background behind the animated fill
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
        // Animated fill background from bottom
        val overlayHeight = animatedProgress
        if (overlayHeight > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(overlayHeight)
                    .align(Alignment.BottomStart)
                    .background(MaterialTheme.colorScheme.surface)
            )
        }

        val showKeepUsing = phase == AppBlockPhase.COMPLETED && animatedProgress >= 0.99f
        // PICK app: the picker replaces the text once the wait is over. While waiting it takes no
        // space, so the text is centred exactly like on other apps.
        val showPicker = pick != null && showKeepUsing

        // Content: centered text + buttons at bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Centered text block
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!showPicker) {
                    Text(
                        text = stringResource(
                            if (pick != null) R.string.re_intervention_times_up else R.string.re_intervention_still_using
                        ),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.re_intervention_chance_to_stop),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                } else if (pick != null) {
                    // Another round: replaces the text once the time's-up wait is over, centred.
                    DurationPicker(
                        title = stringResource(R.string.pick_another_round),
                        minutes = pick.minutes,
                        extended = pick.extended,
                        onMinutesChange = pick.onMinutesChange,
                        onToggleRange = pick.onToggleRange
                    )
                }
            }

            // Buttons: fixed layout from start; keep-using invisible/inactive until wait ends
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onGoHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.block_home),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .alpha(if (showKeepUsing) 1f else 0f),
                    shape = MaterialTheme.shapes.medium,
                    border = null,
                    // A Pick your time app opens only once a length has been picked.
                    enabled = showKeepUsing && (pick == null || pick.minutes != null)
                ) {
                    Text(
                        text = if (pick != null) {
                            pick.minutes?.let { stringResource(R.string.pick_open_for, it) }
                                ?: stringResource(R.string.pick_choose_first)
                        } else {
                            stringResource(R.string.re_intervention_keep_using)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
