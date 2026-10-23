package com.example.chesslab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val Light = lightColorScheme(
    primary = LightAccent, onPrimary = LightCard,
    background = LightBg, onBackground = LightFg,
    surface = LightBg, onSurface = LightFg,
    surfaceVariant = LightCard, onSurfaceVariant = LightFg,
    outline = LightLine, secondaryContainer = LightLine, onSecondaryContainer = LightFg
)

private val Dark = darkColorScheme(
    primary = DarkAccent, onPrimary = DarkBg,
    background = DarkBg, onBackground = DarkFg,
    surface = DarkBg, onSurface = DarkFg,
    surfaceVariant = DarkCard, onSurfaceVariant = DarkFg,
    outline = DarkLine, secondaryContainer = DarkLine, onSecondaryContainer = DarkFg
)

@Composable
fun ChessLabTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
