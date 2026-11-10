package com.example.inchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CyberpunkColorScheme =
    darkColorScheme(
        primary = NeonCyan,
        onPrimary = Black,
        primaryContainer = Color(0xFF06212A),
        onPrimaryContainer = Color(0xFFC7F9FF),

        secondary = NeonMagenta,
        onSecondary = Black,
        secondaryContainer = Color(0xFF260D25),
        onSecondaryContainer = Color(0xFFFFD5F7),

        tertiary = NeonCyan,
        onTertiary = Black,
        tertiaryContainer = Color(0xFF09202A),
        onTertiaryContainer = Color(0xFFD7FAFF),

        background = AppBackground,
        onBackground = TextPrimary,

        surface = AppSurface,
        onSurface = TextPrimary,
        surfaceVariant = AppSurfaceVariant,
        onSurfaceVariant = SoftLavender,

        outline = AppOutline,
        outlineVariant = AppOutlineVariant,

        error = NeonError,
        onError = Black,
        errorContainer = Color(0xFF35121F),
        onErrorContainer = Color(0xFFFFD9E2)
    )

/**
 * InChat uses its AMOLED Cyberpunk appearance on every device.
 * The system light/dark setting no longer switches the app to a
 * white interface.
 */
@Composable
fun InChatTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CyberpunkColorScheme,
        typography = Typography,
        content = content
    )
}
