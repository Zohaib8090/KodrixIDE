package com.kodrix.zohaib.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val KodrixDarkColors = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1F6FEB),
    secondary = Color(0xFF3FB950),
    onSecondary = Color.Black,
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFC9D1D9),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFC9D1D9),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFF85149),
    outline = Color(0xFF30363D)
)

@Composable
fun KodrixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KodrixDarkColors,
        content = content
    )
}
