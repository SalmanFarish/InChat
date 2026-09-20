package com.example.inchat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * InChat Typography
 *
 * Designed for a clean, compact, X-inspired interface:
 *
 * - Strong, bold headings
 * - Compact titles
 * - Comfortable body text
 * - Small, restrained metadata
 * - Tight letter spacing
 */
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
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    40.sp,

                lineHeight =
                    44.sp,

                letterSpacing =
                    (-1.0).sp
            ),

        displayMedium =
            TextStyle(
                fontFamily =
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    34.sp,

                lineHeight =
                    39.sp,

                letterSpacing =
                    (-0.8).sp
            ),

        displaySmall =
            TextStyle(
                fontFamily =
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    30.sp,

                lineHeight =
                    35.sp,

                letterSpacing =
                    (-0.6).sp
            ),

        /*
         * =====================================================
         * HEADLINES
         * =====================================================
         */
        headlineLarge =
            TextStyle(
                fontFamily =
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    28.sp,

                lineHeight =
                    33.sp,

                letterSpacing =
                    (-0.5).sp
            ),

        headlineMedium =
            TextStyle(
                fontFamily =
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    24.sp,

                lineHeight =
                    29.sp,

                letterSpacing =
                    (-0.4).sp
            ),

        headlineSmall =
            TextStyle(
                fontFamily =
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    21.sp,

                lineHeight =
                    26.sp,

                letterSpacing =
                    (-0.25).sp
            ),

        /*
         * =====================================================
         * TITLES
         * =====================================================
         */
        titleLarge =
            TextStyle(
                fontFamily =
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.Bold,

                fontSize =
                    20.sp,

                lineHeight =
                    24.sp,

                letterSpacing =
                    (-0.2).sp
            ),

        titleMedium =
            TextStyle(
                fontFamily =
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.SemiBold,

                fontSize =
                    17.sp,

                lineHeight =
                    22.sp,

                letterSpacing =
                    (-0.1).sp
            ),

        titleSmall =
            TextStyle(
                fontFamily =
                    FontFamily.SansSerif,

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
                    FontFamily.SansSerif,

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
                    FontFamily.SansSerif,

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
                    FontFamily.SansSerif,

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
                    FontFamily.SansSerif,

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
                    FontFamily.SansSerif,

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
                    FontFamily.SansSerif,

                fontWeight =
                    FontWeight.Medium,

                fontSize =
                    11.sp,

                lineHeight =
                    14.sp,

                letterSpacing =
                    0.05.sp
            )
    )
