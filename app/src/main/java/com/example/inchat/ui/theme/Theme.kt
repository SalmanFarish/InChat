package com.example.inchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable

/*
 * ============================================================
 * INCHAT DARK THEME
 * ============================================================
 *
 * Strict monochrome palette.
 *
 * No dynamic colors.
 * No purple.
 * No pink.
 * No blue.
 * No cyan.
 */
private val DarkColorScheme =
    darkColorScheme(
        primary = NeonCyan,
        onPrimary = AppBackground,
        primaryContainer = Color(0xFF103447),
        onPrimaryContainer = Color(0xFFBDF8FF),
        secondary = ElectricBlue,
        onSecondary = White,
        secondaryContainer = Color(0xFF172B4A),
        onSecondaryContainer = Color(0xFFD6E8FF),
        tertiary = NeonMagenta,
        onTertiary = AppBackground,
        tertiaryContainer = Color(0xFF381B3D),
        onTertiaryContainer = Color(0xFFFFD8F7),
        background = AppBackground,
        onBackground = TextPrimary,
        surface = AppSurface,
        onSurface = TextPrimary,
        surfaceVariant = AppSurfaceVariant,
        onSurfaceVariant = SoftLavender,
        outline = AppOutline,
        outlineVariant = AppOutlineVariant,
        error = NeonError,
        onError = AppBackground,
        errorContainer = Color(0xFF3C1D32),
        onErrorContainer = Color(0xFFFFD9E5)
    )

/*
 * ============================================================
 * INCHAT LIGHT THEME
 * ============================================================
 *
 * The existing clean monochrome light theme remains available.
 */
private val LightColorScheme =
    lightColorScheme(

        primary =
            Black,

        onPrimary =
            White,

        secondary =
            SurfaceElevated,

        onSecondary =
            White,

        tertiary =
            SurfaceElevated,

        onTertiary =
            White,

        background =
            White,

        onBackground =
            Black,

        surface =
            White,

        onSurface =
            Black,

        surfaceVariant =
            TextSecondary,

        onSurfaceVariant =
            Black,

        outline =
            Divider,

        outlineVariant =
            Divider,

        error =
            Black,

        onError =
            White,

        errorContainer =
            TextSecondary,

        onErrorContainer =
            Black
    )

/*
 * ============================================================
 * INCHAT THEME
 * ============================================================
 */
@Composable
fun InChatTheme(
    darkTheme: Boolean =
        isSystemInDarkTheme(),

    content: @Composable () -> Unit
) {

    val colorScheme =
        if (
            darkTheme
        ) {

            DarkColorScheme

        } else {

            LightColorScheme
        }

    MaterialTheme(

        colorScheme =
            colorScheme,

        typography =
            Typography,

        content =
            content
    )
}
