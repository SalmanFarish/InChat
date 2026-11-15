package com.example.inchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TerminalGreenColorScheme =
    darkColorScheme(
        primary = TerminalGreen,
        onPrimary = Color(0xFF001B05),
        primaryContainer = Color(0xFF0A2810),
        onPrimaryContainer = Color(0xFFC7FFD0),

        secondary = TerminalGreenSoft,
        onSecondary = Color(0xFF001B05),
        secondaryContainer = Color(0xFF122917),
        onSecondaryContainer = Color(0xFFB9F7C1),

        tertiary = TerminalGreenBright,
        onTertiary = Color(0xFF001B05),
        tertiaryContainer = Color(0xFF0C3214),
        onTertiaryContainer = Color(0xFFD2FFD8),

        background = AppBackground,
        onBackground = TextPrimary,

        surface = AppSurface,
        onSurface = TextPrimary,
        surfaceVariant = AppSurfaceVariant,
        onSurfaceVariant = TextSecondary,
        surfaceDim = AppBackground,
        surfaceBright = AppSurfaceVariant,
        surfaceContainerLowest = AppBackground,
        surfaceContainerLow = AppSurface,
        surfaceContainer = AppSurface,
        surfaceContainerHigh = AppSurfaceVariant,
        surfaceContainerHighest = AppSurfaceVariant,
        surfaceTint = Black,

        outline = AppOutline,
        outlineVariant = AppOutlineVariant,

        error = TerminalError,
        onError = Color(0xFF240000),
        errorContainer = Color(0xFF351111),
        onErrorContainer = Color(0xFFFFDADA)
    )

/**
 * InChat uses its AMOLED terminal-green appearance on every device.
 * The system light/dark setting does not switch to a white interface.
 */
@Composable
fun InChatTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TerminalGreenColorScheme,
        typography = Typography,
        content = content
    )
}
