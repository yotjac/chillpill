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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
            val drawable = context.getDrawable(R.drawable.block_background) ?: return@remember null
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
    onContinue: () -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundPainter = rememberBlockBackgroundPainter()
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

        when (phase) {
            AppBlockPhase.WAITING -> {
                // Semi-transparent overlay that drains downward as progress goes 0 -> 1
                // Overlay height = (1f - progress) of screen height from top
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(1f - progress)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                )
            }
            AppBlockPhase.COMPLETED -> {
                // Overlay gone; card with message and buttons
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val message = if (isReIntervention) {
                                stringResource(R.string.block_message_grace_expired)
                            } else {
                                stringResource(R.string.block_message_opens_count, openCount24h)
                            }
                            Text(
                                text = message,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = onContinue,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(stringResource(R.string.block_continue_to_app))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onGoHome,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(stringResource(R.string.block_back_to_home))
                            }
                        }
                    }
                }
            }
        }
    }
}
