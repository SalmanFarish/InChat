package com.example.inchat.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.ChatRepository

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableMessageBubble(
    message: Message,
    isMe: Boolean,
    deliveryStatus: MessageDeliveryStatus,
    currentUserId: String,
    onReply: () -> Unit,
    onLongClick: () -> Unit,
    onQuotedReplyClick: (String) -> Unit,
    chatSwipeOffsetPx: Float = 0f,
    replySwipeOffsetPx: Float = 0f
) {
    val density =
        LocalDensity.current

    val timestampRevealDistancePx =
        with(density) {
            56.dp.toPx()
        }

    val revealProgress =
        (
                chatSwipeOffsetPx /
                        timestampRevealDistancePx
                )
            .coerceIn(
                0f,
                1f
            )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize()
    ) {
        Text(
            text =
                formatMessageTime(
                    message.timestamp
                ),
            style =
                MaterialTheme
                    .typography
                    .labelSmall,
            fontWeight =
                FontWeight.Medium,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
            modifier =
                Modifier
                    .align(
                        Alignment.CenterEnd
                    )
                    .padding(
                        end =
                            2.dp
                    )
                    .graphicsLayer {
                        alpha =
                            revealProgress

                        translationX =
                            4.dp.toPx() -
                                    (
                                            revealProgress *
                                                    6.dp.toPx()
                                            )
                    }
        )

        Surface(
            modifier =
                Modifier
                    .align(
                        Alignment.CenterStart
                    )
                    .size(
                        32.dp
                    )
                    .graphicsLayer {
                        alpha =
                            (
                                    replySwipeOffsetPx /
                                            (56.dp.toPx())
                                    )
                                .coerceIn(
                                    0f,
                                    1f
                                )

                        scaleX =
                            0.7f +
                                    (
                                            (
                                                    replySwipeOffsetPx /
                                                            (56.dp.toPx())
                                                    )
                                                .coerceIn(
                                                    0f,
                                                    1f
                                                ) *
                                                    0.3f
                                            )

                        scaleY =
                            0.7f +
                                    (
                                            (
                                                    replySwipeOffsetPx /
                                                            (56.dp.toPx())
                                                    )
                                                .coerceIn(
                                                    0f,
                                                    1f
                                                ) *
                                                    0.3f
                                            )
                    },
            shape =
                CircleShape,
            color =
                MaterialTheme
                    .colorScheme
                    .primaryContainer
        ) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(),
                contentAlignment =
                    Alignment.Center
            ) {
                Text(
                    text =
                        "↩",
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX =
                            replySwipeOffset.value -
                                    (
                                            revealProgress *
                                                    12.dp.toPx()
                                            )
                    },
            horizontalArrangement =
                if (isMe) {
                    Arrangement.End
                } else {
                    Arrangement.Start
                }
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(
                            min =
                                72.dp,
                            max =
                                310.dp
                        )
                        .wrapContentWidth()
                        .animateContentSize()
            ) {
                Column(
                    modifier =
                        Modifier
                            .clip(
                                RoundedCornerShape(
                                    topStart =
                                        18.dp,
                                    topEnd =
                                        18.dp,
                                    bottomStart =
                                        if (isMe) {
                                            18.dp
                                        } else {
                                            5.dp
                                        },
                                    bottomEnd =
                                        if (isMe) {
                                            5.dp
                                        } else {
                                            18.dp
                                        }
                                )
                            )
                            .background(
                                if (isMe) {
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                } else {
                                    MaterialTheme
                                        .colorScheme
                                        .surfaceVariant
                                }
                            )
                            .combinedClickable(
                                onClick = {},
                                onLongClick =
                                    onLongClick
                            )
                            .padding(
                                horizontal =
                                    13.dp,
                                vertical =
                                    10.dp
                            )
                ) {
                    message.replyTo?.let { reply ->
                        ReplyMessagePreview(
                            reply =
                                reply,
                            isMe =
                                isMe,
                            onClick = {
                                onQuotedReplyClick(
                                    reply.messageId
                                )
                            }
                        )

                        Spacer(
                            modifier =
                                Modifier.padding(
                                    vertical =
                                        3.5.dp
                                )
                        )
                    }

                    Text(
                        text =
                            message.text,
                        color =
                            if (isMe) {
                                MaterialTheme
                                    .colorScheme
                                    .onPrimary
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                            },
                        fontSize =
                            16.sp,
                        lineHeight =
                            21.sp,
                        letterSpacing =
                            (-0.05).sp
                    )

                    if (
                        message.edited ||
                                (
                                        isMe &&
                                                deliveryStatus !=
                                                MessageDeliveryStatus.NONE
                                        )
                    ) {
                        Spacer(
                            modifier =
                                Modifier.padding(
                                    vertical =
                                        2.5.dp
                                )
                        )

                        Row(
                            modifier =
                                Modifier.wrapContentWidth(),
                            horizontalArrangement =
                                Arrangement.End,
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            if (message.edited) {
                                Text(
                                    text =
                                        "edited",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall,
                                    color =
                                        if (isMe) {
                                            MaterialTheme
                                                .colorScheme
                                                .onPrimary
                                                .copy(
                                                    alpha =
                                                        0.72f
                                                )
                                        } else {
                                            MaterialTheme
                                                .colorScheme
                                                .onSurfaceVariant
                                                .copy(
                                                    alpha =
                                                        0.72f
                                                )
                                        }
                                )
                            }

                            if (
                                isMe &&
                                        deliveryStatus !=
                                        MessageDeliveryStatus.NONE
                            ) {
                                if (message.edited) {
                                    Spacer(
                                        modifier =
                                            Modifier.width(
                                                5.dp
                                            )
                                    )
                                }

                                Text(
                                    text =
                                        deliveryStatusText(
                                            deliveryStatus
                                        ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall,
                                    fontWeight =
                                        FontWeight.SemiBold,
                                    color =
                                        if (
                                            deliveryStatus ==
                                            MessageDeliveryStatus
                                                .WAITING_FOR_CONNECTION
                                        ) {
                                            MaterialTheme
                                                .colorScheme
                                                .error
                                                .copy(
                                                    alpha =
                                                        0.85f
                                                )
                                        } else {
                                            MaterialTheme
                                                .colorScheme
                                                .onPrimary
                                                .copy(
                                                    alpha =
                                                        0.72f
                                                )
                                        }
                                )
                            }
                        }
                    }
                }

                if (
                    message.reactions.isNotEmpty()
                ) {
                    ReactionSummary(
                        reactions =
                            message.reactions,
                        currentUserId =
                            currentUserId,
                        onReactionClick = {},
                        modifier =
                            Modifier
                                .padding(
                                    top =
                                        2.dp
                                )
                                .align(
                                    if (isMe) {
                                        Alignment.End
                                    } else {
                                        Alignment.Start
                                    }
                                )
                    )
                }
            }
        }
    }
}

@Composable
fun ReactionSummary(
    reactions: Map<String, String>,
    currentUserId: String,
    onReactionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val groupedReactions =
        reactions
            .values
            .groupingBy {
                it
            }
            .eachCount()

    val userReaction =
        reactions[
            currentUserId
        ]

    Surface(
        modifier =
            modifier.animateContentSize(),
        shape =
            RoundedCornerShape(
                14.dp
            ),
        color =
            MaterialTheme
                .colorScheme
                .surface,
        tonalElevation =
            2.dp
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal =
                        7.dp,
                    vertical =
                        3.dp
                ),
            horizontalArrangement =
                Arrangement.spacedBy(
                    4.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            ChatRepository
                .SUPPORTED_REACTIONS
                .filter {
                    groupedReactions
                        .containsKey(
                            it
                        )
                }
                .forEach { reaction ->
                    val count =
                        groupedReactions[
                            reaction
                        ] ?: 0

                    Text(
                        text =
                            "$reaction$count",
                        fontSize =
                            13.sp,
                        fontWeight =
                            if (
                                userReaction ==
                                reaction
                            ) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                        modifier =
                            Modifier.clickable {
                                onReactionClick(
                                    reaction
                                )
                            },
                        color =
                            if (
                                userReaction ==
                                reaction
                            ) {
                                MaterialTheme
                                    .colorScheme
                                    .primary
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurface
                            }
                    )
                }
        }
    }
}
