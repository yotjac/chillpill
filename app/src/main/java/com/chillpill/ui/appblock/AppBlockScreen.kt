package com.chillpill.ui.appblock

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chillpill.R

/**
 * Loads block background as a Painter using Context (no composable resource loading).
 * Returns null when the drawable cannot be loaded (e.g. REPLACED package state).
 */
@Composable
private fun rememberBlockBackgroundPainter(): BitmapPainter? {
    val context = LocalContext.current
    return remember(context) {
        try {
            val drawable = context.getDrawable(R.drawable.block_activity_background) ?: return@remember null
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 512
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 512
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            BitmapPainter(bitmap.asImageBitmap())
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun AppBlockScreen(
    phase: AppBlockPhase,
    progress: Float,
    openCount24h: Int,
    isReIntervention: Boolean,
    appName: String,
    onContinue: () -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundPainter = rememberBlockBackgroundPainter()
    val animatedProgress: Float by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "overlayProgress"
    )
    Box(modifier = modifier.fillMaxSize()) {
        // Full-screen background: drawable or solid-color fallback when resource loading fails (e.g. REPLACED package state)
        if (backgroundPainter != null) {
            Image(
                painter = backgroundPainter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            )
        }

        // Shared overlay: creeps up with animated progress
        val overlayHeight = animatedProgress
        if (overlayHeight > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(overlayHeight)
                    .align(Alignment.BottomStart)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            )
        }

        // One-shot shine animation when completed content first appears
        var shineProgress by remember { mutableStateOf(0f) }
        val showCompleted = phase == AppBlockPhase.COMPLETED && animatedProgress >= 0.99f
        LaunchedEffect(showCompleted) {
            if (showCompleted) {
                shineProgress = 0f
                androidx.compose.animation.core.Animatable(0f).apply {
                    animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 1300, easing = FastOutSlowInEasing)
                    ) {
                        shineProgress = value
                    }
                }
            }
        }

        // Shine band sweeping from top to bottom when shineProgress runs 0f -> 1f
        if (shineProgress > 0f && shineProgress < 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val height = size.height
                        val bandHeight = height * 0.4f
                        val yCenter = (shineProgress * (height + bandHeight)) - bandHeight / 2f
                        val top = (yCenter - bandHeight / 2f).coerceAtLeast(0f)
                        val bottom = (yCenter + bandHeight / 2f).coerceAtMost(height)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.22f),
                                    Color.Transparent
                                ),
                                startY = top,
                                endY = bottom
                            ),
                            size = size
                        )
                    }
            )
        }

        if (showCompleted) {
            // Completed phase: stats centered, buttons at the bottom
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = openCount24h.toString(),
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.block_attempts_last_24h, appName),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
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
                            text = stringResource(R.string.block_back_to_home),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                        border = null
                    ) {
                        Text(
                            text = stringResource(R.string.block_continue_to_app),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        } else {
            // Waiting phase: overlay animation only; no additional content
        }
    }
}
