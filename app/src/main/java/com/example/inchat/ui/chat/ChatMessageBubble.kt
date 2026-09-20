package com.example.inchat.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.ChatRepository
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight

@OptIn(
    ExperimentalFoundationApi::class
)
@Composable
fun SwipeableMessageBubble(
    message: Message,
    isMe: Boolean,
    deliveryStatus: MessageDeliveryStatus,
    currentUserId: String,
    onReply: () -> Unit,
    onLongClick: () -> Unit,
    onQuotedReplyClick: (String) -> Unit
) {

    var swipeOffset by
    remember(
        message.id
    ) {

        mutableFloatStateOf(
            0f
        )
    }

    val animatedSwipeOffset by
    animateFloatAsState(

        targetValue =
            swipeOffset,

        animationSpec =
            spring(),

        label =
            "messageSwipe"
    )

    val replyThreshold =
        72f

    val maximumSwipe =
        96f

    Row(

        modifier =
            Modifier.fillMaxWidth(),

        horizontalArrangement =
            if (
                isMe
            ) {

                Arrangement.End

            } else {

                Arrangement.Start
            }
    ) {

        Box(

            modifier =
                Modifier
                    .widthIn(
                        max =
                            310.dp
                    )
                    .animateContentSize()
        ) {

            if (
                animatedSwipeOffset > 8f
            ) {

                Box(

                    modifier =
                        Modifier.matchParentSize(),

                    contentAlignment =
                        Alignment.CenterStart
                ) {

                    androidx.compose.material3.Icon(

                        imageVector =
                            Icons.AutoMirrored
                                .Filled
                                .Reply,

                        contentDescription =
                            "Reply",

                        tint =
                            MaterialTheme
                                .colorScheme
                                .primary,

                        modifier =
                            Modifier.graphicsLayer {

                                alpha =
                                    (
                                            animatedSwipeOffset /
                                                    replyThreshold
                                            )
                                        .coerceIn(
                                            0f,
                                            1f
                                        )

                                scaleX =
                                    0.8f +
                                            (
                                                    animatedSwipeOffset /
                                                            maximumSwipe
                                                    )
                                                .coerceIn(
                                                    0f,
                                                    1f
                                                ) *
                                            0.2f

                                scaleY =
                                    scaleX
                            }
                    )
                }
            }

            Column(

                modifier =
                    Modifier
                        .offset {

                            IntOffset(

                                x =
                                    animatedSwipeOffset
                                        .roundToInt(),

                                y =
                                    0
                            )
                        }
                        .clip(

                            RoundedCornerShape(

                                topStart =
                                    18.dp,

                                topEnd =
                                    18.dp,

                                bottomStart =
                                    if (
                                        isMe
                                    ) {
                                        18.dp
                                    } else {
                                        5.dp
                                    },

                                bottomEnd =
                                    if (
                                        isMe
                                    ) {
                                        5.dp
                                    } else {
                                        18.dp
                                    }
                            )
                        )
                        .background(

                            if (
                                isMe
                            ) {

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
                        .pointerInput(
                            message.id
                        ) {

                            detectHorizontalDragGestures(

                                onHorizontalDrag = {
                                        _,
                                        dragAmount ->

                                    if (
                                        dragAmount > 0f ||
                                        swipeOffset > 0f
                                    ) {

                                        swipeOffset =
                                            (
                                                    swipeOffset +
                                                            dragAmount
                                                    )
                                                .coerceIn(
                                                    0f,
                                                    maximumSwipe
                                                )
                                    }
                                },

                                onDragEnd = {

                                    if (
                                        swipeOffset >=
                                        replyThreshold
                                    ) {

                                        onReply()
                                    }

                                    swipeOffset =
                                        0f
                                },

                                onDragCancel = {

                                    swipeOffset =
                                        0f
                                }
                            )
                        }
                        .padding(
                            horizontal =
                                13.dp,

                            vertical =
                                10.dp
                        )
            ) {

                message.replyTo
                    ?.let { reply ->

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
                        if (
                            isMe
                        ) {

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

                Spacer(
                    modifier =
                        Modifier.padding(
                            vertical =
                                2.5.dp
                        )
                )

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.End,

                    verticalAlignment =
                        Alignment.CenterVertically
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

                        color =
                            if (
                                isMe
                            ) {

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

                    if (
                        message.edited
                    ) {

                        Spacer(
                            modifier =
                                Modifier.width(
                                    5.dp
                                )
                        )

                        Text(

                            text =
                                "edited",

                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,

                            color =
                                if (
                                    isMe
                                ) {

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

                        Spacer(
                            modifier =
                                Modifier.width(
                                    6.dp
                                )
                        )

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
                            .align(
                                if (
                                    isMe
                                ) {

                                    Alignment.BottomEnd

                                } else {

                                    Alignment.BottomStart
                                }
                            )
                            .offset(
                                y =
                                    19.dp
                            )
                )
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
            modifier
                .animateContentSize(),

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