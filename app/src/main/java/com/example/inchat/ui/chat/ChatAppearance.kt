package com.example.inchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ChatThemePalette(
    val background: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
    val incomingText: Color,
    val outgoingText: Color,
    val secondaryText: Color,
    val incomingBorder: Color = Color.Transparent,
    val outgoingBorder: Color = Color.Transparent,
    val statusAccent: Color = Color(0xFF39FF14)
)

enum class ChatTheme(
    val id: String,
    val title: String,
    val description: String,
    val legacyIds: List<String>,
    private val colors: ChatThemePalette
) {
    CYBERPUNK_NEON(
        id = "cyberpunk_neon",
        title = "Cyberpunk Neon",
        description = "Deep navy panels with cyan and magenta terminal accents",
        legacyIds = emptyList(),
        colors = ChatThemePalette(
            background = Color(0xFF080B19),
            incomingBubble = Color(0xFF10182B),
            outgoingBubble = Color(0xFF10263A),
            incomingText = Color(0xFFF1F3FA),
            outgoingText = Color(0xFFF4FAFF),
            secondaryText = Color(0xFF9BA9C3),
            incomingBorder = Color(0xFF00E5FF).copy(alpha = 0.28f),
            outgoingBorder = Color(0xFFFF00D4).copy(alpha = 0.25f),
            statusAccent = Color(0xFF00E5FF)
        )
    ),

    AMOLED_BLACK(
        id = "amoled_black",
        title = "AMOLED Black",
        description = "True black background for a clean, low-glare chat",
        legacyIds = listOf(
            "pure_black",
            "spectrum",
            "space_cats",
            "football",
            "star_dust",
            "cats",
            "night_cosmos",
            "dessert",
            "starfield",
            "signal",
            "sticker",
            "cosmos",
            "ascii_minimal",
            "dots",
            "wave",
            "grid",
            "brackets"
        ),
        colors = ChatThemePalette(
            background = Color(0xFF000000),
            incomingBubble = Color(0xFF171717),
            outgoingBubble = Color(0xFF292929),
            incomingText = Color(0xFFF5F5F5),
            outgoingText = Color(0xFFFFFFFF),
            secondaryText = Color(0xFFBDBDBD),
            statusAccent = Color(0xFF39FF14)
        )
    ),

    PURE_WHITE(
        id = "pure_white",
        title = "Pure White",
        description = "Bright white background with simple dark message bubbles",
        legacyIds = listOf("terminal"),
        colors = ChatThemePalette(
            background = Color(0xFFFFFFFF),
            incomingBubble = Color(0xFFF1F1F1),
            outgoingBubble = Color(0xFFE4E4E4),
            incomingText = Color(0xFF171717),
            outgoingText = Color(0xFF111111),
            secondaryText = Color(0xFF666666),
            statusAccent = Color(0xFF007A2F)
        )
    );

    // This palette is used as a per-device fallback only until a shared
    // theme is explicitly chosen and stored for the conversation.
    fun palette(darkTheme: Boolean): ChatThemePalette = colors

    val backgroundColor: Color
        @Composable get() = colors.background

    val incomingBubbleColor: Color
        @Composable get() = colors.incomingBubble

    val outgoingBubbleColor: Color
        @Composable get() = colors.outgoingBubble

    val incomingTextColor: Color
        @Composable get() = colors.incomingText

    val outgoingTextColor: Color
        @Composable get() = colors.outgoingText

    val secondaryTextColor: Color
        @Composable get() = colors.secondaryText

    val incomingBorderColor: Color
        get() = colors.incomingBorder

    val outgoingBorderColor: Color
        get() = colors.outgoingBorder

    val statusAccentColor: Color
        get() = colors.statusAccent

    companion object {
        fun defaultForSystemTheme(darkTheme: Boolean): ChatTheme =
            if (darkTheme) CYBERPUNK_NEON else PURE_WHITE

        fun fromId(
            id: String?,
            defaultTheme: ChatTheme = CYBERPUNK_NEON
        ): ChatTheme =
            entries.firstOrNull {
                it.id == id || it.legacyIds.contains(id)
            } ?: defaultTheme

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
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundModifier =
        if (theme == ChatTheme.CYBERPUNK_NEON) {
            Modifier.background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF080B19),
                        Color(0xFF0D0B22),
                        Color(0xFF080B19)
                    )
                )
            )
        } else {
            Modifier.background(theme.backgroundColor)
        }

    Box(
        modifier = modifier.then(backgroundModifier),
        content = content
    )
}

@Composable
fun ChatThemePreview(
    theme: ChatTheme,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = theme.palette(darkTheme = false)

    Surface(
        modifier = modifier.then(
            if (selected) {
                Modifier.border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(18.dp)
                )
            } else {
                Modifier
            }
        ),
        shape = RoundedCornerShape(18.dp),
        color = palette.background
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .width(110.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = palette.incomingBubble
                ) {
                    Text(
                        text = "Hello",
                        modifier = Modifier.padding(
                            horizontal = 10.dp,
                            vertical = 7.dp
                        ),
                        fontSize = 10.sp,
                        color = palette.incomingText
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.End)
                        .width(124.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = palette.outgoingBubble
                ) {
                    Text(
                        text = "Looks great",
                        modifier = Modifier.padding(
                            horizontal = 10.dp,
                            vertical = 7.dp
                        ),
                        fontSize = 10.sp,
                        color = palette.outgoingText
                    )
                }
            }

            if (selected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(9.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        modifier = Modifier
                            .padding(6.dp)
                            .size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun ChatThemeCard(
    theme: ChatTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = theme.palette(darkTheme = false)

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp,
            pressedElevation = 3.dp
        ),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(
                1.5.dp,
                MaterialTheme.colorScheme.primary
            )
        } else {
            null
        }
    ) {
        Column {
            ChatThemePreview(
                theme = theme,
                selected = selected,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.55f)
                    .padding(8.dp)
            )

            Column(
                modifier = Modifier.padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 2.dp,
                    bottom = 13.dp
                )
            ) {
                Text(
                    text = theme.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = theme.description,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
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
    val palette = theme.palette(darkTheme = false)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChatThemePreview(
            theme = theme,
            selected = selected,
            modifier = Modifier.size(width = 110.dp, height = 78.dp)
        )

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = theme.title,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = theme.description,
                fontSize = 13.sp,
                color = palette.secondaryText
            )
        }

        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
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
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                )
            }
        }
    }
}
