package com.chillpill.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chillpill.data.settings.BlockBackground
import com.chillpill.data.settings.BlockBackgroundStore
import com.chillpill.data.settings.BlockBackgrounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.chillpill.R

private val TileWidth = 72.dp
private val TileHeight = 128.dp
private val TileShape = RoundedCornerShape(12.dp)
private const val THUMBNAIL_MAX_PX = 256

/**
 * Horizontal picker for the block ("waiting") screen background: the bundled images, the user's
 * own photo when there is one, and a tile that opens the system photo picker.
 *
 * Stateless — selection flows down, events flow up.
 */
@Composable
fun BlockBackgroundPicker(
    selected: BlockBackground,
    customFileName: String?,
    isPending: Boolean,
    onBuiltInSelected: (String) -> Unit,
    onCustomSelected: (String) -> Unit,
    onPickCustom: () -> Unit,
    onRemoveCustom: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedBorderColor = if (isPending) {
        PendingWaitBorderColor
    } else {
        MaterialTheme.colorScheme.primary
    }
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(BlockBackgrounds.all, key = { it.id }) { builtIn ->
            val isSelected = selected is BlockBackground.BuiltIn && selected.id == builtIn.id
            val builtInLabel = stringResource(builtIn.labelRes)
            BackgroundTile(
                selected = isSelected,
                selectedBorderColor = selectedBorderColor,
                contentDescription = builtInLabel,
                onClick = { onBuiltInSelected(builtIn.id) }
            ) {
                val thumbnail by rememberDrawableThumbnail(builtIn.drawableRes)
                ThumbnailContent(thumbnail, builtInLabel)
            }
        }

        if (customFileName != null) {
            item(key = "custom") {
                val isSelected = selected is BlockBackground.Custom
                BackgroundTile(
                    selected = isSelected,
                    selectedBorderColor = selectedBorderColor,
                    contentDescription = stringResource(R.string.background_your_photo),
                    onClick = { onCustomSelected(customFileName) },
                    onRemove = onRemoveCustom
                ) {
                    val thumbnail by rememberFileThumbnail(customFileName)
                    ThumbnailContent(thumbnail, stringResource(R.string.background_your_photo))
                }
            }
        }

        item(key = "pick") {
            Box(
                modifier = Modifier
                    .width(TileWidth)
                    .height(TileHeight)
                    .clip(TileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, TileShape)
                    .selectable(
                        selected = false,
                        role = Role.Button,
                        onClick = onPickCustom
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.AddPhotoAlternate,
                    contentDescription = stringResource(R.string.background_choose_photo),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BackgroundTile(
    selected: Boolean,
    selectedBorderColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // Captured before the semantics block, where `contentDescription` would otherwise resolve to
    // the (write-only) semantics property of the same name.
    val description = contentDescription
    val removeLabel = stringResource(R.string.background_remove_photo)
    Box(
        modifier = modifier
            .width(TileWidth)
            .height(TileHeight)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) selectedBorderColor else MaterialTheme.colorScheme.outlineVariant,
                shape = TileShape
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics {
                this.contentDescription = description
                if (onRemove != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(removeLabel) {
                            onRemove()
                            true
                        }
                    )
                }
            }
    ) {
        content()
        if (selected) {
            Surface(
                color = selectedBorderColor,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(1.dp)
                )
            }
        }
        if (onRemove != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clickable(onClick = onRemove)
                    // Announced as a custom action on the tile instead, so TalkBack users are
                    // not left with an unreachable node inside a selectable parent.
                    .clearAndSetSemantics { }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(1.dp)
                )
            }
        }
    }
}

@Composable
private fun ThumbnailContent(thumbnail: ImageBitmap?, label: String) {
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun rememberDrawableThumbnail(resId: Int) =
    LocalContext.current.let { context ->
        produceState<ImageBitmap?>(initialValue = null, context, resId) {
            value = withContext(Dispatchers.IO) { decodeThumbnail { options -> BitmapFactory.decodeResource(context.resources, resId, options) } }
        }
    }

@Composable
private fun rememberFileThumbnail(fileName: String) =
    LocalContext.current.let { context ->
        produceState<ImageBitmap?>(initialValue = null, context, fileName) {
            value = withContext(Dispatchers.IO) {
                val path = BlockBackgroundStore.fileFor(context, fileName).absolutePath
                decodeThumbnail { options -> BitmapFactory.decodeFile(path, options) }
            }
        }
    }

/**
 * Two-pass decode so a large photo is never fully loaded just to draw a 72 dp tile.
 */
private fun decodeThumbnail(decode: (BitmapFactory.Options) -> android.graphics.Bitmap?): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sampleSize = 1
        var longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / 2 >= THUMBNAIL_MAX_PX) {
            longSide /= 2
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        decode(options)?.asImageBitmap()
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
