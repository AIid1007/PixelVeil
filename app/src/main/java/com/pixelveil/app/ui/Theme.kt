package com.pixelveil.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFD60A)
val Surface = Color(0xFF1A1A1D)
val Background = Color(0xFF0E0E10)
val Muted = Color(0xFF9A9AA0)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Accent,
    onSecondary = Color.Black,
    background = Background,
    onBackground = Color.White,
    surface = Surface,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF242428),
    onSurfaceVariant = Muted,
    error = Color(0xFFFF6B6B),
)

@Composable
fun PixelVeilTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
