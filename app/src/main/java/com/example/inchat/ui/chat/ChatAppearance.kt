package com.example.inchat.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

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
        id = "spectrum",
        title = "Spectrum",
        description = "Colorful stars, planets and rockets",
        wallpaperName = "chat_wallpaper_spectrum",
        legacyIds = listOf("dessert", "pure_black"),
        darkPalette = ChatThemePalette(
            Color(0xFF050505),
            Color(0xFF181818),
            Color(0xFF46336E),
            Color(0xFFF0F0F0),
            Color.White,
            Color(0xFFBDBDBD)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF5F3F7),
            Color.White,
            Color(0xFF6954A0),
            Color(0xFF222026),
            Color.White,
            Color(0xFF66616D)
        )
    ),

    STARFIELD(
        id = "space_cats",
        title = "Space Cats",
        description = "Playful cats, planets and rockets",
        wallpaperName = "chat_wallpaper_space_cats",
        legacyIds = listOf("starfield", "ascii_minimal"),
        darkPalette = ChatThemePalette(
            Color(0xFF070707),
            Color(0xFF1A1A1A),
            Color(0xFF8A542D),
            Color(0xFFECECEC),
            Color.White,
            Color(0xFFB8B8B8)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF6F2EE),
            Color.White,
            Color(0xFF8A5A32),
            Color(0xFF25211E),
            Color.White,
            Color(0xFF6F6964)
        )
    ),

    SIGNAL(
        id = "football",
        title = "Football",
        description = "Football, jerseys, boots and trophies",
        wallpaperName = "chat_wallpaper_football",
        legacyIds = listOf("signal", "dots"),
        darkPalette = ChatThemePalette(
            Color(0xFF111111),
            Color(0xFF202020),
            Color(0xFF47662F),
            Color(0xFFF0F0F0),
            Color.White,
            Color(0xFFBDBDBD)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF7F7F5),
            Color.White,
            Color(0xFF607C49),
            Color(0xFF242424),
            Color.White,
            Color(0xFF666666)
        )
    ),

    STICKER(
        id = "star_dust",
        title = "Star Dust",
        description = "Minimal stars, moons and tiny planets",
        wallpaperName = "chat_wallpaper_star_dust",
        legacyIds = listOf("sticker", "wave"),
        darkPalette = ChatThemePalette(
            Color(0xFF050505),
            Color(0xFF151515),
            Color(0xFF363636),
            Color(0xFFEDEDED),
            Color.White,
            Color(0xFFB8B8B8)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF3F3F3),
            Color.White,
            Color(0xFF4A4A4A),
            Color(0xFF202020),
            Color.White,
            Color(0xFF626262)
        )
    ),

    COSMOS(
        id = "cats",
        title = "Cats",
        description = "Hand-drawn cats, hearts and little icons",
        wallpaperName = "chat_wallpaper_cats",
        legacyIds = listOf("cosmos", "grid"),
        darkPalette = ChatThemePalette(
            Color(0xFF111111),
            Color(0xFF202020),
            Color(0xFF424242),
            Color(0xFFF0F0F0),
            Color.White,
            Color(0xFFBEBEBE)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF3F3F3),
            Color.White,
            Color(0xFF454545),
            Color(0xFF222222),
            Color.White,
            Color(0xFF666666)
        )
    ),

    SPACE_WHITE(
        id = "space_white",
        title = "White Space",
        description = "Clean white space doodles",
        wallpaperName = "chat_wallpaper_space_white",
        legacyIds = listOf("terminal"),
        darkPalette = ChatThemePalette(
            Color(0xFF050505),
            Color(0xFF151515),
            Color(0xFF333333),
            Color(0xFFECECEC),
            Color.White,
            Color(0xFFB5B5B5)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF9F9F9),
            Color.White,
            Color(0xFF353535),
            Color(0xFF202020),
            Color.White,
            Color(0xFF666666)
        )
    ),

    NIGHT_COSMOS(
        id = "night_cosmos",
        title = "Night Cosmos",
        description = "Planets, constellations and shooting stars",
        wallpaperName = "chat_wallpaper_night_cosmos",
        legacyIds = listOf("brackets"),
        darkPalette = ChatThemePalette(
            Color(0xFF071018),
            Color(0xFF121A23),
            Color(0xFF2F526C),
            Color(0xFFF2F3F5),
            Color.White,
            Color(0xFFB9C3CB)
        ),
        lightPalette = ChatThemePalette(
            Color(0xFFF0F4F6),
            Color.White,
            Color(0xFF456B83),
            Color(0xFF202830),
            Color.White,
            Color(0xFF65717A)
        )
    );

    fun palette(darkTheme: Boolean): ChatThemePalette =
        if (darkTheme) darkPalette else lightPalette

    val backgroundColor: Color
        @Composable get() = palette(isSystemInDarkTheme()).background

    val incomingBubbleColor: Color
        @Composable get() = palette(isSystemInDarkTheme()).incomingBubble

    val outgoingBubbleColor: Color
        @Composable get() = palette(isSystemInDarkTheme()).outgoingBubble

    val incomingTextColor: Color
        @Composable get() = palette(isSystemInDarkTheme()).incomingText

    val outgoingTextColor: Color
        @Composable get() = palette(isSystemInDarkTheme()).outgoingText

    val secondaryTextColor: Color
        @Composable get() = palette(isSystemInDarkTheme()).secondaryText

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
