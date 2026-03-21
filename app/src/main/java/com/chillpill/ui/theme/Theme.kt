package com.chillpill.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

// Modern palette: slate/teal primary, warm neutrals
private val Slate900 = Color(0xFF0F172A)
private val Slate700 = Color(0xFF334155)
private val Teal600 = Color(0xFF0D9488)
private val Teal500 = Color(0xFF14B8A6)
private val WarmGray100 = Color(0xFFF5F5F4)
private val WarmGray50 = Color(0xFFFAFAF9)

private val LightColorScheme = lightColorScheme(
    primary = Teal600,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Slate900,
    secondary = Slate700,
    onSecondary = Color.White,
    background = WarmGray50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = WarmGray100,
    onSurfaceVariant = Slate700,
    tertiary = Teal500,
    tertiaryContainer = Color(0xFF99F6E4),
    onTertiaryContainer = Slate900,
    outline = Color(0xFF94A3B8)
)

private val DarkColorScheme = darkColorScheme(
    primary = Teal500,
    onPrimary = Slate900,
    primaryContainer = Teal600,
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF94A3B8),
    onSecondary = Slate900,
    background = Slate900,
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    tertiary = Color(0xFF5EEAD4),
    tertiaryContainer = Teal600,
    onTertiaryContainer = Color(0xFFCCFBF1),
    outline = Color(0xFF64748B)
)

private val ChillpillShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun ChillpillTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * When `false`, skips all status bar / window insets logic. Use for Compose hosted in a
     * [android.app.Service] overlay — [LocalView] is not an Activity window and touching
     * window APIs here has caused crashes.
     */
    applyWindowDecor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    if (applyWindowDecor) {
        val view = LocalView.current
        // Only Activities have a Window; never cast context to Activity blindly.
        val activity = view.context as? Activity
        if (!view.isInEditMode && activity != null) {
            SideEffect {
                val window = activity.window
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ChillpillShapes,
        content = content
    )
}
