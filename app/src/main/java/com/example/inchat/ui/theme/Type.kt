package com.example.inchat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * InChat Typography
 *
 * Designed for a clear, modern messaging interface:
 *
 * - Rounded geometric sans-serif typography
 * - Compact titles
 * - Readable body text
 * - Small, restrained metadata
 * - Controlled letter spacing
 */
private val googleSansFlexFont = GoogleFont("Google Sans Flex")

val GoogleSansFlex = FontFamily(
    Font(
        googleFont = googleSansFlexFont,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        googleFont = googleSansFlexFont,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        googleFont = googleSansFlexFont,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        googleFont = googleSansFlexFont,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    )
)

val Typography =
    Typography(

        /*
         * =====================================================
         * DISPLAY
         * =====================================================
         */
        displayLarge =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    40.sp,

                lineHeight =
                    44.sp,

                letterSpacing =
                    (-0.5).sp
            ),

        displayMedium =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    34.sp,

                lineHeight =
                    39.sp,

                letterSpacing =
                    (-0.4).sp
            ),

        displaySmall =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    30.sp,

                lineHeight =
                    35.sp,

                letterSpacing =
                    (-0.3).sp
            ),

        /*
         * =====================================================
         * HEADLINES
         * =====================================================
         */
        headlineLarge =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    28.sp,

                lineHeight =
                    33.sp,

                letterSpacing =
                    (-0.25).sp
            ),

        headlineMedium =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    24.sp,

                lineHeight =
                    29.sp,

                letterSpacing =
                    (-0.2).sp
            ),

        headlineSmall =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    21.sp,

                lineHeight =
                    26.sp,

                letterSpacing =
                    (-0.1).sp
            ),

        /*
         * =====================================================
         * TITLES
         * =====================================================
         */
        titleLarge =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    20.sp,

                lineHeight =
                    24.sp,

                letterSpacing =
                    (-0.1).sp
            ),

        titleMedium =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.SemiBold,

                fontSize =
                    17.sp,

                lineHeight =
                    22.sp,

                letterSpacing =
                    0.sp
            ),

        titleSmall =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.SemiBold,

                fontSize =
                    15.sp,

                lineHeight =
                    20.sp,

                letterSpacing =
                    0.sp
            ),

        /*
         * =====================================================
         * BODY
         * =====================================================
         */
        bodyLarge =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Normal,

                fontSize =
                    16.sp,

                lineHeight =
                    22.sp,

                letterSpacing =
                    0.sp
            ),

        bodyMedium =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Normal,

                fontSize =
                    15.sp,

                lineHeight =
                    20.sp,

                letterSpacing =
                    0.sp
            ),

        bodySmall =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Normal,

                fontSize =
                    13.sp,

                lineHeight =
                    18.sp,

                letterSpacing =
                    0.sp
            ),

        /*
         * =====================================================
         * LABELS / METADATA
         * =====================================================
         */
        labelLarge =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.SemiBold,

                fontSize =
                    14.sp,

                lineHeight =
                    18.sp,

                letterSpacing =
                    0.sp
            ),

        labelMedium =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Medium,

                fontSize =
                    12.sp,

                lineHeight =
                    16.sp,

                letterSpacing =
                    0.05.sp
            ),

        labelSmall =
            TextStyle(
                fontFamily =
                    GoogleSansFlex,

                fontWeight =
                    FontWeight.Medium,

                fontSize =
                    11.sp,

                lineHeight =
                    14.sp,

                letterSpacing =
                    0.1.sp
            )
    )
