package com.example.inchat.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

enum class ChatTheme(
    val id: String,
    val title: String,
    val description: String
) {

    PURE_BLACK(
        id = "pure_black",
        title = "Pure Black",
        description = "Clean AMOLED black"
    ),

    ASCII_MINIMAL(
        id = "ascii_minimal",
        title = "ASCII Minimal",
        description = "Subtle text pattern"
    ),

    DOTS(
        id = "dots",
        title = "Dots",
        description = "Minimal dot texture"
    ),

    WAVE(
        id = "wave",
        title = "Wave",
        description = "Soft repeating waves"
    ),

    GRID(
        id = "grid",
        title = "Grid",
        description = "Fine geometric grid"
    ),

    TERMINAL(
        id = "terminal",
        title = "Terminal",
        description = "Anonymous terminal style"
    ),

    BRACKETS(
        id = "brackets",
        title = "Brackets",
        description = "Minimal ASCII brackets"
    ),

    SIGNAL(
        id = "signal",
        title = "Signal",
        description = "Sparse signal pattern"
    );

    companion object {

        fun fromId(
            id: String?
        ): ChatTheme {

            return entries.firstOrNull {
                it.id == id
            } ?: PURE_BLACK
        }

        fun isValidId(
            id: String
        ): Boolean {

            return entries.any {
                it.id == id
            }
        }

        val all: List<ChatTheme>
            get() =
                entries
    }
}

/*
 * ============================================================
 * CHAT WALLPAPER
 * ============================================================
 *
 * The patterns are drawn behind the chat content.
 *
 * Only the existing monochrome Material colors are used.
 */
@Composable
fun ChatWallpaper(
    theme: ChatTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {

    val backgroundColor =
        MaterialTheme
            .colorScheme
            .background

    val patternColor =
        MaterialTheme
            .colorScheme
            .onBackground
            .copy(
                alpha = 0.085f
            )

    Box(
        modifier =
            modifier
                .background(
                    backgroundColor
                )
    ) {

        when (
            theme
        ) {

            ChatTheme.PURE_BLACK -> {
                /*
                 * No wallpaper.
                 */
            }

            ChatTheme.ASCII_MINIMAL -> {

                Text(
                    text =
                        buildAsciiMinimal(),

                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                10.dp
                            ),

                    fontFamily =
                        FontFamily.Monospace,

                    fontSize =
                        12.sp,

                    lineHeight =
                        21.sp,

                    color =
                        patternColor
                )
            }

            ChatTheme.DOTS -> {

                Canvas(
                    modifier =
                        Modifier.fillMaxSize()
                ) {

                    val spacing =
                        30.dp.toPx()

                    var x =
                        10.dp.toPx()

                    while (
                        x < size.width
                    ) {

                        var y =
                            10.dp.toPx()

                        while (
                            y < size.height
                        ) {

                            drawCircle(

                                color =
                                    patternColor,

                                radius =
                                    1.8.dp.toPx(),

                                center =
                                    Offset(
                                        x,
                                        y
                                    )
                            )

                            y +=
                                spacing
                        }

                        x +=
                            spacing
                    }
                }
            }

            ChatTheme.WAVE -> {

                Canvas(
                    modifier =
                        Modifier.fillMaxSize()
                ) {

                    val rowSpacing =
                        46.dp.toPx()

                    val amplitude =
                        7.dp.toPx()

                    val wavelength =
                        70.dp.toPx()

                    var baseY =
                        18.dp.toPx()

                    while (
                        baseY < size.height
                    ) {

                        val path =
                            Path()

                        path.moveTo(
                            0f,
                            baseY
                        )

                        var x =
                            0f

                        while (
                            x <= size.width
                        ) {

                            val y =
                                baseY +
                                        sin(
                                            (
                                                    x /
                                                            wavelength
                                                    ) *
                                                    PI
                                        ).toFloat() *
                                        amplitude

                            path.lineTo(
                                x,
                                y
                            )

                            x +=
                                4.dp.toPx()
                        }

                        drawPath(

                            path =
                                path,

                            color =
                                patternColor,

                            style =
                                Stroke(
                                    width =
                                        1.dp.toPx()
                                )
                        )

                        baseY +=
                            rowSpacing
                    }
                }
            }

            ChatTheme.GRID -> {

                Canvas(
                    modifier =
                        Modifier.fillMaxSize()
                ) {

                    val spacing =
                        34.dp.toPx()

                    var x =
                        0f

                    while (
                        x <= size.width
                    ) {

                        drawLine(

                            color =
                                patternColor,

                            start =
                                Offset(
                                    x,
                                    0f
                                ),

                            end =
                                Offset(
                                    x,
                                    size.height
                                ),

                            strokeWidth =
                                1.dp.toPx()
                        )

                        x +=
                            spacing
                    }

                    var y =
                        0f

                    while (
                        y <= size.height
                    ) {

                        drawLine(

                            color =
                                patternColor,

                            start =
                                Offset(
                                    0f,
                                    y
                                ),

                            end =
                                Offset(
                                    size.width,
                                    y
                                ),

                            strokeWidth =
                                1.dp.toPx()
                        )

                        y +=
                            spacing
                    }
                }
            }

            ChatTheme.TERMINAL -> {

                Text(

                    text =
                        buildTerminal(),

                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                12.dp
                            ),

                    fontFamily =
                        FontFamily.Monospace,

                    fontSize =
                        11.sp,

                    lineHeight =
                        19.sp,

                    color =
                        patternColor
                )
            }

            ChatTheme.BRACKETS -> {

                Text(

                    text =
                        buildBrackets(),

                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                14.dp
                            ),

                    fontFamily =
                        FontFamily.Monospace,

                    fontSize =
                        12.sp,

                    lineHeight =
                        23.sp,

                    color =
                        patternColor
                )
            }

            ChatTheme.SIGNAL -> {

                Canvas(
                    modifier =
                        Modifier.fillMaxSize()
                ) {

                    val rowSpacing =
                        40.dp.toPx()

                    var y =
                        18.dp.toPx()

                    while (
                        y < size.height
                    ) {

                        val center =
                            size.width *
                                    0.55f

                        drawLine(

                            color =
                                patternColor,

                            start =
                                Offset(
                                    18.dp.toPx(),
                                    y
                                ),

                            end =
                                Offset(
                                    center,
                                    y
                                ),

                            strokeWidth =
                                1.dp.toPx()
                        )

                        drawLine(

                            color =
                                patternColor,

                            start =
                                Offset(
                                    center +
                                            10.dp.toPx(),

                                    y
                                ),

                            end =
                                Offset(
                                    size.width -
                                            18.dp.toPx(),

                                    y
                                ),

                            strokeWidth =
                                1.dp.toPx()
                        )

                        drawCircle(

                            color =
                                patternColor,

                            radius =
                                2.dp.toPx(),

                            center =
                                Offset(
                                    center,
                                    y
                                )
                        )

                        y +=
                            rowSpacing
                    }
                }
            }
        }

        content()
    }
}

/*
 * ============================================================
 * THEME PREVIEW
 * ============================================================
 */
@Composable
fun ChatThemePreview(
    theme: ChatTheme,
    selected: Boolean,
    modifier: Modifier = Modifier
) {

    Surface(

        modifier =
            modifier,

        shape =
            RoundedCornerShape(
                14.dp
            ),

        color =
            MaterialTheme
                .colorScheme
                .surfaceVariant,

        tonalElevation =
            if (
                selected
            ) {
                3.dp
            } else {
                0.dp
            }
    ) {

        ChatWallpaper(

            theme =
                theme,

            modifier =
                Modifier.fillMaxSize()
        ) {

            Box(
                modifier =
                    Modifier.fillMaxSize()
            ) {

                Surface(

                    modifier =
                        Modifier
                            .align(
                                Alignment.TopStart
                            )
                            .padding(
                                7.dp
                            )
                            .width(
                                55.dp
                            )
                            .height(
                                15.dp
                            ),

                    shape =
                        RoundedCornerShape(
                            7.dp
                        ),

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurface
                            .copy(
                                alpha =
                                    0.14f
                            )
                ) {}

                Surface(

                    modifier =
                        Modifier
                            .align(
                                Alignment.BottomEnd
                            )
                            .padding(
                                7.dp
                            )
                            .width(
                                60.dp
                            )
                            .height(
                                16.dp
                            ),

                    shape =
                        RoundedCornerShape(
                            7.dp
                        ),

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurface
                            .copy(
                                alpha =
                                    0.23f
                            )
                ) {}
            }
        }
    }
}

/*
 * ============================================================
 * THEME ROW
 * ============================================================
 */
@Composable
fun ChatThemeRow(
    theme: ChatTheme,
    selected: Boolean,
    onClick: () -> Unit
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick =
                        onClick
                )
                .padding(
                    start =
                        20.dp,

                    top =
                        10.dp,

                    end =
                        20.dp,

                    bottom =
                        10.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        ChatThemePreview(

            theme =
                theme,

            selected =
                selected,

            modifier =
                Modifier
                    .size(
                        width =
                            82.dp,

                        height =
                            58.dp
                    )
        )

        Spacer(
            modifier =
                Modifier.width(
                    13.dp
                )
        )

        Column(
            modifier =
                Modifier.weight(
                    1f
                )
        ) {

            Text(

                text =
                    theme.title,

                fontSize =
                    15.sp,

                fontWeight =
                    if (
                        selected
                    ) {
                        FontWeight.Bold
                    } else {
                        FontWeight.SemiBold
                    }
            )

            Spacer(
                modifier =
                    Modifier.height(
                        2.dp
                    )
            )

            Text(

                text =
                    theme.description,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        if (
            selected
        ) {

            Surface(

                modifier =
                    Modifier.size(
                        9.dp
                    ),

                shape =
                    CircleShape,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurface
            ) {}
        }
    }
}

private fun buildAsciiMinimal(): String {

    val pattern =
        "·    ·      ·\n" +
                "   ·      ·   \n" +
                "·      ·      ·\n" +
                "   ·      ·   \n"

    return pattern.repeat(
        24
    )
}

private fun buildTerminal(): String {

    val pattern =
        "> _\n" +
                "> .\n" +
                "> _\n" +
                "> .\n"

    return pattern.repeat(
        28
    )
}

private fun buildBrackets(): String {

    val pattern =
        "[ ]     { }\n" +
                "   < >\n" +
                "{ }     [ ]\n" +
                "   / \\\n"

    return pattern.repeat(
        19
    )
}