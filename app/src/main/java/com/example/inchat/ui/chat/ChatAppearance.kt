package com.example.inchat.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

enum class ChatTheme(
    val id: String,
    val title: String,
    val legacyId: String,
    val description: String,
    val backgroundColor: Color,
    val incomingBubbleColor: Color,
    val outgoingBubbleColor: Color,
    val incomingTextColor: Color,
    val outgoingTextColor: Color,
    val secondaryTextColor: Color,
    val patternColor: Color
) {
    MIDNIGHT(
        "midnight",
        "Midnight",
        "pure_black",
        "Deep blue with a clean glow",
        Color(0xFF080B12),
        Color(0xFF171D29),
        Color(0xFF315DAD),
        Color(0xFFE9EEF8),
        Color(0xFFFFFFFF),
        Color(0xFFB7C2D6),
        Color(0xFF4D6794)
    ),
    OCEAN(
        "ocean",
        "Ocean",
        "ascii_minimal",
        "Dark teal with calm accents",
        Color(0xFF061216),
        Color(0xFF10252A),
        Color(0xFF087E8B),
        Color(0xFFE5F3F5),
        Color(0xFFFFFFFF),
        Color(0xFFB2CED2),
        Color(0xFF2D6871)
    ),
    FOREST(
        "forest",
        "Forest",
        "dots",
        "Quiet green with soft contrast",
        Color(0xFF08120E),
        Color(0xFF13231C),
        Color(0xFF1B7654),
        Color(0xFFE5F2EB),
        Color(0xFFFFFFFF),
        Color(0xFFAFC6B9),
        Color(0xFF3F735D)
    ),
    EMBER(
        "ember",
        "Ember",
        "wave",
        "Warm rust over a dark base",
        Color(0xFF140B08),
        Color(0xFF281614),
        Color(0xFFAD4D2D),
        Color(0xFFF5E9E4),
        Color(0xFFFFFFFF),
        Color(0xFFD4B6AA),
        Color(0xFF7C4435)
    ),
    VIOLET(
        "violet",
        "Violet",
        "grid",
        "Dark plum with a vivid accent",
        Color(0xFF100A16),
        Color(0xFF25182F),
        Color(0xFF7342B4),
        Color(0xFFF0E9F7),
        Color(0xFFFFFFFF),
        Color(0xFFC5B4D2),
        Color(0xFF684A7B)
    ),
    MONO(
        "mono",
        "Mono",
        "terminal",
        "Minimal graphite, no distractions",
        Color(0xFF0C0D0F),
        Color(0xFF1B1D20),
        Color(0xFF3B3F45),
        Color(0xFFEDEEEF),
        Color(0xFFFFFFFF),
        Color(0xFFB9BCC1),
        Color(0xFF50545B)
    );

    companion object {
        fun fromId(id: String?): ChatTheme =
            entries.firstOrNull {
                it.id == id || it.legacyId == id
            } ?: MIDNIGHT

        fun isValidId(id: String): Boolean =
            entries.any {
                it.id == id || it.legacyId == id
            }

        fun legacyIdFor(id: String): String? =
            entries.firstOrNull {
                it.id == id
            }?.legacyId

        val all: List<ChatTheme>
            get() = entries
    }
}

@Composable
fun ChatWallpaper(
    theme: ChatTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.background(theme.backgroundColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (theme) {
                ChatTheme.MIDNIGHT -> {
                    val spacing = 42.dp.toPx()
                    var x = -size.height
                    while (x < size.width + size.height) {
                        drawLine(
                            color = theme.patternColor.copy(alpha = 0.12f),
                            start = Offset(x, 0f),
                            end = Offset(x + size.height, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                        x += spacing
                    }
                }

                ChatTheme.OCEAN -> {
                    val rowSpacing = 58.dp.toPx()
                    val amplitude = 8.dp.toPx()
                    val wavelength = 110.dp.toPx()
                    var baseY = 24.dp.toPx()

                    while (baseY < size.height) {
                        val path = Path().apply {
                            moveTo(0f, baseY)
                            var x = 0f
                            while (x <= size.width) {
                                val y =
                                    baseY +
                                        sin((x / wavelength) * PI).toFloat() *
                                            amplitude
                                lineTo(x, y)
                                x += 4.dp.toPx()
                            }
                        }

                        drawPath(
                            path = path,
                            color = theme.patternColor.copy(alpha = 0.11f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                        baseY += rowSpacing
                    }
                }

                ChatTheme.FOREST -> {
                    val spacing = 34.dp.toPx()
                    var x = 15.dp.toPx()
                    while (x < size.width) {
                        var y = 18.dp.toPx()
                        while (y < size.height) {
                            drawCircle(
                                color = theme.patternColor.copy(alpha = 0.18f),
                                radius = 1.5.dp.toPx(),
                                center = Offset(x, y)
                            )
                            y += spacing
                        }
                        x += spacing
                    }
                }

                ChatTheme.EMBER -> {
                    val spacing = 38.dp.toPx()
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(
                            color = theme.patternColor.copy(alpha = 0.10f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                        x += spacing
                    }

                    var y = 0f
                    while (y <= size.height) {
                        drawLine(
                            color = theme.patternColor.copy(alpha = 0.10f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                        y += spacing
                    }
                }

                ChatTheme.VIOLET -> {
                    val radius = 74.dp.toPx()
                    val stepX = 128.dp.toPx()
                    val stepY = 118.dp.toPx()
                    var x = 42.dp.toPx()
                    var row = 0

                    while (x < size.width + radius) {
                        var y =
                            44.dp.toPx() +
                                if (row % 2 == 0) 0f else stepY / 2f

                        while (y < size.height + radius) {
                            drawCircle(
                                color = theme.patternColor.copy(alpha = 0.08f),
                                radius = radius,
                                center = Offset(x, y),
                                style = Stroke(width = 1.dp.toPx())
                            )
                            y += stepY
                        }
                        x += stepX
                        row++
                    }
                }

                ChatTheme.MONO -> Unit
            }
        }

        content()
    }
}

@Composable
fun ChatThemePreview(
    theme: ChatTheme,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.then(
            if (selected) {
                Modifier.border(
                    width = 1.5.dp,
                    color = theme.outgoingBubbleColor,
                    shape = RoundedCornerShape(16.dp)
                )
            } else {
                Modifier
            }
        ),
        shape = RoundedCornerShape(16.dp),
        color = theme.backgroundColor
    ) {
        ChatWallpaper(
            theme = theme,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .width(88.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = theme.incomingBubbleColor
                ) {
                    Text(
                        text = "Hello",
                        modifier = Modifier.padding(
                            horizontal = 9.dp,
                            vertical = 6.dp
                        ),
                        fontSize = 10.sp,
                        color = theme.incomingTextColor
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.End)
                        .width(102.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = theme.outgoingBubbleColor
                ) {
                    Text(
                        text = "Looks good",
                        modifier = Modifier.padding(
                            horizontal = 9.dp,
                            vertical = 6.dp
                        ),
                        fontSize = 10.sp,
                        color = theme.outgoingTextColor
                    )
                }
            }
        }
    }
}

@Composable
fun ChatThemeRow(
    theme: ChatTheme,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = 16.dp,
                vertical = 9.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChatThemePreview(
            theme = theme,
            selected = selected,
            modifier = Modifier.size(
                width = 110.dp,
                height = 78.dp
            )
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.title,
                fontSize = 16.sp,
                fontWeight = if (selected) {
                    FontWeight.Bold
                } else {
                    FontWeight.SemiBold
                },
                color = theme.outgoingBubbleColor
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = theme.description,
                fontSize = 13.sp,
                color = theme.secondaryTextColor
            )
        }

        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) {
                        theme.outgoingBubbleColor
                    } else {
                        theme.secondaryTextColor.copy(alpha = 0.45f)
                    },
                    shape = CircleShape
                )
                .padding(4.dp)
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            theme.outgoingBubbleColor,
                            CircleShape
                        )
                )
            }
        }
    }
}
