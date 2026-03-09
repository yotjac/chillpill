package com.chillpill.ui.common

import android.content.pm.PackageManager
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            try {
                imageView.setImageDrawable(context.packageManager.getApplicationIcon(packageName))
            } catch (_: PackageManager.NameNotFoundException) {
                imageView.setImageDrawable(context.getDrawable(android.R.drawable.sym_def_app_icon))
            }
        }
    )
}
