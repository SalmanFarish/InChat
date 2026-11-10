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
    val statusAccent: Color = Color(0xFF49FF66)
)

enum class ChatTheme(
    val id: String,
    val title: String,
    val description: String,
    val legacyIds: List<String>,
    private val colors: ChatThemePalette
) {
    BITCHAT_GREEN(
        id = "bitchat_green",
        title = "Terminal Green",
        description = "AMOLED black with minimal terminal-green text and accents",
        legacyIds = listOf(
            "cyberpunk_neon",
            "amoled_black",
            "pure_white",
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
            "terminal",
            "brackets"
        ),
        colors = ChatThemePalette(
            background = Color(0xFF000000),
            incomingBubble = Color(0xFF030A04),
            outgoingBubble = Color(0xFF041108),
            incomingText = Color(0xFFA5ECAF),
            outgoingText = Color(0xFFC9FFD0),
            secondaryText = Color(0xFF79A982),
            incomingBorder = Color.Transparent,
            outgoingBorder = Color.Transparent,
            statusAccent = Color(0xFF49FF66)
        )
    );

    // Old saved theme IDs resolve to this single current appearance.
    fun palette(): ChatThemePalette = colors

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
        fun fromId(
            id: String?,
            defaultTheme: ChatTheme = BITCHAT_GREEN
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
    Box(
        modifier = modifier.background(theme.backgroundColor),
        content = content
    )
}

@Composable
fun ChatThemePreview(
    theme: ChatTheme,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = theme.palette()

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
    val palette = theme.palette()

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
    val palette = theme.palette()

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
