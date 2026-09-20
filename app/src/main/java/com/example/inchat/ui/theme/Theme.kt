package com.example.inchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

        /*
         * Primary interactive color.
         */
        primary =
            White,

        onPrimary =
            Black,

        /*
         * Secondary interactive elements.
         */
        secondary =
            TextSecondary,

        onSecondary =
            Black,

        /*
         * Tertiary remains monochrome.
         */
        tertiary =
            TextSecondary,

        onTertiary =
            Black,

        /*
         * Main AMOLED background.
         */
        background =
            Black,

        onBackground =
            TextPrimary,

        /*
         * Main surfaces.
         */
        surface =
            Black,

        onSurface =
            TextPrimary,

        /*
         * Slightly elevated surfaces.
         */
        surfaceVariant =
            SurfaceSubtle,

        onSurfaceVariant =
            TextSecondary,

        /*
         * Borders / dividers.
         */
        outline =
            Divider,

        outlineVariant =
            Divider,

        /*
         * Error state remains monochrome.
         *
         * We deliberately avoid introducing a red
         * error color.
         */
        error =
            White,

        onError =
            Black,

        errorContainer =
            SurfaceElevated,

        onErrorContainer =
            White
    )

/*
 * ============================================================
 * INCHAT LIGHT THEME
 * ============================================================
 *
 * Still strictly monochrome.
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
