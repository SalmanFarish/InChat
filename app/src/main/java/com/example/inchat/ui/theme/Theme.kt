package com.example.inchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TerminalGreenColorScheme =
    darkColorScheme(
        primary = TerminalGreen,
        onPrimary = Black,
        primaryContainer = Color(0xFF0A2810),
        onPrimaryContainer = White,

        secondary = TerminalGreenSoft,
        onSecondary = Black,
        secondaryContainer = Color(0xFF122917),
        onSecondaryContainer = White,

        tertiary = TerminalGreenBright,
        onTertiary = Black,
        tertiaryContainer = Color(0xFF0C3214),
        onTertiaryContainer = White,

        background = Black,
        onBackground = White,

        surface = Black,
        onSurface = White,
        surfaceVariant = Black,
        onSurfaceVariant = White,
        surfaceDim = Black,
        surfaceBright = Black,
        surfaceContainerLowest = Black,
        surfaceContainerLow = Black,
        surfaceContainer = Black,
        surfaceContainerHigh = Black,
        surfaceContainerHighest = Black,
        surfaceTint = Black,

        outline = AppOutline,
        outlineVariant = AppOutlineVariant,

        error = TerminalError,
        onError = Black,
        errorContainer = Color(0xFF351111),
        onErrorContainer = White
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
