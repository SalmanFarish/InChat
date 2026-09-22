package com.example.inchat.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.max

data class ChatThemePalette(
    val background: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
    val incomingText: Color,
    val outgoingText: Color,
    val secondaryText: Color
)

enum class ChatTheme(
    val id: String,
    val title: String,
    val description: String,
    val wallpaperName: String,
    val legacyIds: List<String>,
    val darkPalette: ChatThemePalette,
    val lightPalette: ChatThemePalette
) {
    DESSERT(
        id = "dessert",
        title = "Dessert",
        description = "Playful ice-cream line art",
        wallpaperName = "chat_wallpaper_dessert",
        legacyIds = listOf("pure_black"),
        darkPalette = ChatThemePalette(
            Color(0xFF080808),
            Color(0xFF1B1B1B),
            Color(0xFF404040),
            Color(0xFFEAEAEA),
            Color.White,
            Color(0xFFB8B8B8)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF5F5F5),
            Color.White,
            Color(0xFF303030),
            Color(0xFF202020),
            Color.White,
            Color(0xFF606060)
        )
    ),

    STARFIELD(
        id = "starfield",
        title = "Starfield",
        description = "Stars, planets and drifting clouds",
        wallpaperName = "chat_wallpaper_starfield",
        legacyIds = listOf("ascii_minimal", "terminal"),
        darkPalette = ChatThemePalette(
            Color(0xFF0A0A0B),
            Color(0xFF1A1A1D),
            Color(0xFF454548),
            Color(0xFFEDEDF0),
            Color.White,
            Color(0xFFB9B9BE)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF5F5F6),
            Color.White,
            Color(0xFF303034),
            Color(0xFF202024),
            Color.White,
            Color(0xFF606066)
        )
    ),

    SIGNAL(
        id = "signal",
        title = "Signal",
        description = "Neon communication symbols",
        wallpaperName = "chat_wallpaper_signal",
        legacyIds = listOf("dots"),
        darkPalette = ChatThemePalette(
            Color(0xFF050507),
            Color(0xFF171722),
            Color(0xFF49386B),
            Color(0xFFEDE9F7),
            Color.White,
            Color(0xFFC4BDD3)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF5F4F8),
            Color.White,
            Color(0xFF5C447F),
            Color(0xFF24212A),
            Color.White,
            Color(0xFF686071)
        )
    ),

    STICKER(
        id = "sticker",
        title = "Sticker",
        description = "Dense monochrome sticker collage",
        wallpaperName = "chat_wallpaper_sticker",
        legacyIds = listOf("wave"),
        darkPalette = ChatThemePalette(
            Color(0xFF171717),
            Color(0xFF2A2A2A),
            Color(0xFF515151),
            Color(0xFFEAEAEA),
            Color.White,
            Color(0xFFBEBEBE)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFEAEAEA),
            Color.White,
            Color(0xFF444444),
            Color(0xFF242424),
            Color.White,
            Color(0xFF626262)
        )
    ),

    COSMOS(
        id = "cosmos",
        title = "Cosmos",
        description = "Astronauts, rockets and planets",
        wallpaperName = "chat_wallpaper_cosmos",
        legacyIds = listOf("grid", "brackets", "signal"),
        darkPalette = ChatThemePalette(
            Color(0xFF050505),
            Color(0xFF151515),
            Color(0xFF3D3D3D),
            Color(0xFFE8E8E8),
            Color.White,
            Color(0xFFB7B7B7)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF1F1F1),
            Color.White,
            Color(0xFF383838),
            Color(0xFF202020),
            Color.White,
            Color(0xFF606060)
        )
    );

    fun palette(darkTheme: Boolean): ChatThemePalette =
        if (darkTheme) darkPalette else lightPalette

    companion object {
        fun fromId(id: String?): ChatTheme =
            entries.firstOrNull {
                it.id == id || it.legacyIds.contains(id)
            } ?: DESSERT

        fun isValidId(id: String): Boolean =
            entries.any { it.id == id }

        fun legacyIdFor(id: String): String? =
            entries.firstOrNull { it.id == id }
                ?.legacyIds
                ?.firstOrNull()

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
    val context = LocalContext.current
    val darkTheme =
        androidx.compose.foundation.isSystemInDarkTheme()

    val palette =
        theme.palette(darkTheme)

    val bitmap =
        remember(
            context,
            theme.wallpaperName
        ) {
            loadChatWallpaper(
                context = context,
                drawableName = theme.wallpaperName
            )
        }

    Box(
        modifier =
            modifier.background(
                palette.background
            )
    ) {
        if (bitmap != null) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val paint =
                    Paint(
                        Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        shader =
                            BitmapShader(
                                bitmap,
                                Shader.TileMode.REPEAT,
                                Shader.TileMode.REPEAT
                            )

                        if (!darkTheme) {
                            val matrix =
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f
                                    )
                                )

                            colorFilter =
                                ColorMatrixColorFilter(
                                    matrix
                                )
                        }
                    }

                drawContext
                    .canvas
                    .nativeCanvas
                    .drawRect(
                        0f,
                        0f,
                        size.width,
                        size.height,
                        paint
                    )

                val overlayPaint =
                    Paint(
                        Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        color =
                            if (darkTheme) {
                                android.graphics.Color.argb(
                                    20,
                                    0,
                                    0,
                                    0
                                )
                            } else {
                                android.graphics.Color.argb(
                                    34,
                                    255,
                                    255,
                                    255
                                )
                            }
                    }

                drawContext
                    .canvas
                    .nativeCanvas
                    .drawRect(
                        0f,
                        0f,
                        size.width,
                        size.height,
                        overlayPaint
                    )
            }
        }

        content()
    }
}

private fun loadChatWallpaper(
    context: Context,
    drawableName: String
): android.graphics.Bitmap? {
    val resId =
        context.resources.getIdentifier(
            drawableName,
            "drawable",
            context.packageName
        )

    if (resId == 0) {
        return null
    }

    return BitmapFactory.decodeResource(
        context.resources,
        resId
    )
}

@Composable
fun ChatThemePreview(
    theme: ChatTheme,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme =
        androidx.compose.foundation.isSystemInDarkTheme()

    val palette =
        theme.palette(darkTheme)

    Surface(
        modifier =
            modifier.then(
                if (selected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = palette.outgoingBubble,
                        shape =
                            androidx.compose.foundation.shape.RoundedCornerShape(
                                16.dp
                            )
                    )
                } else {
                    Modifier
                }
            ),
        shape =
            androidx.compose.foundation.shape.RoundedCornerShape(
                16.dp
            ),
        color = palette.background
    ) {
        ChatWallpaper(
            theme = theme,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                verticalArrangement =
                    Arrangement.SpaceBetween
            ) {
                Surface(
                    modifier =
                        Modifier
                            .align(
                                Alignment.Start
                            )
                            .width(92.dp),
                    shape =
                        androidx.compose.foundation.shape.RoundedCornerShape(
                            12.dp
                        ),
                    color =
                        palette.incomingBubble
                ) {
                    Text(
                        text = "Hello",
                        modifier =
                            Modifier.padding(
                                horizontal = 9.dp,
                                vertical = 6.dp
                            ),
                        fontSize = 10.sp,
                        color = palette.incomingText
                    )
                }

                Surface(
                    modifier =
                        Modifier
                            .align(
                                Alignment.End
                            )
                            .width(104.dp),
                    shape =
                        androidx.compose.foundation.shape.RoundedCornerShape(
                            12.dp
                        ),
                    color =
                        palette.outgoingBubble
                ) {
                    Text(
                        text = "Looks good",
                        modifier =
                            Modifier.padding(
                                horizontal = 9.dp,
                                vertical = 6.dp
                            ),
                        fontSize = 10.sp,
                        color = palette.outgoingText
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
    val darkTheme =
        androidx.compose.foundation.isSystemInDarkTheme()

    val palette =
        theme.palette(darkTheme)

    Row(
        modifier =
            Modifier
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
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        ChatThemePreview(
            theme = theme,
            selected = selected,
            modifier =
                Modifier.size(
                    width = 110.dp,
                    height = 78.dp
                )
        )

        Spacer(
            modifier =
                Modifier.width(14.dp)
        )

        Column(
            modifier =
                Modifier.weight(1f)
        ) {
            Text(
                text = theme.title,
                fontSize = 16.sp,
                fontWeight =
                    if (selected) {
                        FontWeight.Bold
                    } else {
                        FontWeight.SemiBold
                    },
                color = palette.outgoingBubble
            )

            Spacer(
                modifier =
                    Modifier.height(2.dp)
            )

            Text(
                text = theme.description,
                fontSize = 13.sp,
                color = palette.secondaryText
            )
        }

        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .border(
                        width = 2.dp,
                        color =
                            if (selected) {
                                palette.outgoingBubble
                            } else {
                                palette.secondaryText.copy(
                                    alpha = 0.45f
                                )
                            },
                        shape = CircleShape
                    )
                    .padding(4.dp)
        ) {
            if (selected) {
                androidx.compose.foundation.layout.Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                palette.outgoingBubble,
                                CircleShape
                            )
                )
            }
        }
    }
}
