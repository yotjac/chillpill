package com.chillpill.ui.appblock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chillpill.R
import com.chillpill.data.settings.BlockBackground
import com.chillpill.data.settings.BlockBackgroundStore
import com.chillpill.data.settings.BlockBackgrounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the chosen block background off the main thread.
 *
 * Returns null while [background] is still unknown, while the image is being decoded, and
 * whenever nothing could be loaded at all (e.g. REPLACED package state) — callers fall back to a
 * solid surface colour, so the block screen always appears immediately and never flashes an
 * image the user did not choose.
 */
@Composable
private fun rememberBlockBackgroundPainter(background: BlockBackground?): BitmapPainter? {
    val context = LocalContext.current
    val painter by produceState<BitmapPainter?>(initialValue = null, context, background) {
        value = if (background == null) {
            null
        } else {
            withContext(Dispatchers.IO) { loadBackgroundPainter(context, background) }
        }
    }
    return painter
}

/**
 * Fallback chain: the chosen image, then the default bundled image, then null (solid colour).
 */
private fun loadBackgroundPainter(context: Context, background: BlockBackground): BitmapPainter? = try {
    val chosen = when (background) {
        is BlockBackground.Custom -> decodeCustomImage(context, background.fileName)
        is BlockBackground.BuiltIn -> decodeDrawable(context, BlockBackgrounds.drawableResFor(background.id))
    }
    when {
        chosen != null -> chosen
        background is BlockBackground.BuiltIn && background.id == BlockBackgrounds.DEFAULT_ID -> null
        else -> decodeDrawable(context, BlockBackgrounds.drawableResFor(BlockBackgrounds.DEFAULT_ID))
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** Decodes a user-picked photo, sampled to the screen; null when the file is gone or unreadable. */
private fun decodeCustomImage(context: Context, fileName: String): BitmapPainter? = try {
    val file = BlockBackgroundStore.fileFor(context, fileName)
    if (!file.exists()) {
        null
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val metrics = context.resources.displayMetrics
            val target = maxOf(metrics.widthPixels, metrics.heightPixels, 1)
            var sampleSize = 1
            var longSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longSide / 2 >= target) {
                longSide /= 2
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, options)?.let { BitmapPainter(it.asImageBitmap()) }
        }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

private fun decodeDrawable(context: Context, resId: Int): BitmapPainter? = try {
    val drawable = context.getDrawable(resId)
    val bitmap = (drawable as? BitmapDrawable)?.bitmap
    when {
        bitmap != null -> BitmapPainter(bitmap.asImageBitmap())
        drawable == null -> null
        else -> {
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 512
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 512
            val rendered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(rendered))
            BitmapPainter(rendered.asImageBitmap())
        }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

@Composable
fun AppBlockScreen(
    phase: AppBlockPhase,
    progress: Float,
    openCount24h: Int,
    isReIntervention: Boolean,
    appName: String,
    background: BlockBackground?,
    onContinue: () -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundPainter = rememberBlockBackgroundPainter(background)
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
                        text = pluralStringResource(R.plurals.block_attempts_last_24h, openCount24h, appName),
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
                            text = stringResource(R.string.block_home),
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
