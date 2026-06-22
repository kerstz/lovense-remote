package com.edge2.remote.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = Edge2Dark.gradStart,
    onPrimary = Color.White,
    secondary = Edge2Dark.tige,
    background = Edge2Dark.bg,
    onBackground = Edge2Dark.ink,
    surface = Edge2Dark.surface,
    onSurface = Edge2Dark.ink,
    surfaceVariant = Edge2Dark.surfaceElevated,
    onSurfaceVariant = Edge2Dark.muted,
    outline = Edge2Dark.muted,
    error = Edge2Dark.danger,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = Edge2Light.gradStart,
    onPrimary = Color.White,
    secondary = Edge2Light.tige,
    background = Edge2Light.bg,
    onBackground = Edge2Light.ink,
    surface = Edge2Light.surface,
    onSurface = Edge2Light.ink,
    surfaceVariant = Edge2Light.surface,
    onSurfaceVariant = Edge2Light.muted,
    outline = Edge2Light.muted,
    error = Edge2Light.danger,
    onError = Color.White,
)

/** Thème identité Edge2 : sombre par défaut, clair en variante. Pas de dynamic color. */
@Composable
fun Edge2Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) Edge2Dark else Edge2Light
    val scheme = if (darkTheme) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalEdge2Colors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Edge2Typography, content = content)
    }
}

/** Accès aux couleurs identité : `Edge2.colors.base`, etc. */
object Edge2 {
    val colors: Edge2Colors
        @Composable @ReadOnlyComposable get() = LocalEdge2Colors.current
}
