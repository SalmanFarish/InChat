package com.example.inchat.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
    chatSwipeOffsetPx: Float = 0f
) {
    val density =
        LocalDensity.current

    val timestampRevealDistancePx =
        with(density) {
            72.dp.toPx()
        }

    val timestampProgress =
        (
            chatSwipeOffsetPx /
                    timestampRevealDistancePx
            )
            .coerceIn(
                0f,
                1f
            )

    val replyTriggerDistancePx =
        with(density) {
            30.dp.toPx()
        }

    val replyTravelDistancePx =
        with(density) {
            56.dp.toPx()
        }

    var replySwipeOffsetPx by
    remember(message.id) {
        mutableFloatStateOf(0f)
    }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                /*
                 * This detector owns only the message's
                 * center-directed swipe. The timestamp detector
                 * is on LazyColumn, so both can coexist.
                 */
                .pointerInput(
                    message.id,
                    isMe
                ) {
                    var replyTriggered =
                        false

                    detectHorizontalDragGestures(
                        onDragStart = {
                            replySwipeOffsetPx =
                                0f

                            replyTriggered =
                                false
                        },
                        onHorizontalDrag = {
                                change,
                                dragAmount ->

                            val movingTowardCenter =
                                if (isMe) {
                                    dragAmount < 0f
                                } else {
                                    dragAmount > 0f
                                }

                            if (
                                movingTowardCenter &&
                                !replyTriggered
                            ) {
                                replySwipeOffsetPx =
                                    (
                                        replySwipeOffsetPx +
                                                kotlin.math.abs(
                                                    dragAmount
                                                )
                                        )
                                        .coerceIn(
                                            0f,
                                            replyTravelDistancePx
                                        )

                                /*
                                 * Claim the horizontal movement so a
                                 * sent-message left swipe is treated as
                                 * reply, not as the global timestamp swipe.
                                 */
                                change.consume()

                                if (
                                    replySwipeOffsetPx >=
                                    replyTriggerDistancePx
                                ) {
                                    replyTriggered =
                                        true

                                    onReply()
                                }
                            }
                        },
                        onDragEnd = {
                            replySwipeOffsetPx =
                                0f

                            replyTriggered =
                                false
                        },
                        onDragCancel = {
                            replySwipeOffsetPx =
                                0f

                            replyTriggered =
                                false
                        }
                    )
                }
    ) {
        /*
         * Keep enough room for the timestamp at full reveal,
         * while still allowing large bubbles on normal screens.
         */
        val maxBubbleWidth =
            (
                maxWidth -
                        76.dp
                )
                .coerceAtLeast(
                    68.dp
                )
                .coerceAtMost(
                    310.dp
                )

        /*
         * The timestamp is revealed immediately beside the
         * bubble's trailing edge. This keeps times readable and
         * prevents the bubble or time from being clipped.
         */
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX =
                            if (isMe) {
                                -replySwipeOffsetPx
                            } else {
                                replySwipeOffsetPx
                            }
                    }
                    .zIndex(
                        1f
                    ),
            horizontalArrangement =
                if (isMe) {
                    Arrangement.End
                } else {
                    Arrangement.Start
                },
            verticalAlignment =
                Alignment.Top
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(
                            min =
                                68.dp,
                            max =
                                maxBubbleWidth
                        )
                        .animateContentSize()
            ) {
                Column(
                    modifier =
                        Modifier
                            .combinedClickable(
                                interactionSource =
                                    null,
                                indication =
                                    null,
                                onClick = {},
                                onLongClick =
                                    onLongClick
                            )
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
                            .padding(
                                horizontal =
                                    14.dp,
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
                                Modifier.height(
                                    4.dp
                                )
                        )
                    }

                    Text(
                        text =
                            message.text,
                        style =
                            MaterialTheme
                                .typography
                                .bodyLarge
                                .copy(
                                    lineHeight =
                                        22.sp
                                ),
                        color =
                            if (isMe) {
                                MaterialTheme
                                    .colorScheme
                                    .onPrimary
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                            }
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
                                Modifier.height(
                                    3.dp
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
                            if (
                                message.edited
                            ) {
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
                                if (
                                    message.edited
                                ) {
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

            /*
             * The slot grows with the page-level swipe. Because it is
             * after the bubble, outgoing messages move inward when the
             * timestamp appears while incoming messages stay left aligned.
             */
            Box(
                modifier =
                    Modifier
                        .width(
                            70.dp *
                                    timestampProgress
                        )
                        .padding(
                            start =
                                6.dp
                        )
                        .alpha(
                            timestampProgress
                        ),
                contentAlignment =
                    Alignment.Center
            ) {
                Text(
                    text =
                        formatMessageTime(
                            message.timestamp
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium,
                    fontWeight =
                        FontWeight.Medium,
                    textAlign =
                        TextAlign.Center,
                    maxLines =
                        1,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        /*
         * Reply affordance sits on the outside edge of the message
         * and becomes visible before the trigger point.
         */
        Surface(
            modifier =
                Modifier
                    .align(
                        if (isMe) {
                            Alignment.CenterEnd
                        } else {
                            Alignment.CenterStart
                        }
                    )
                    .padding(
                        horizontal =
                            4.dp
                    )
                    .size(
                        34.dp
                    )
                    .graphicsLayer {
                        val progress =
                            (
                                replySwipeOffsetPx /
                                        replyTravelDistancePx
                                )
                                .coerceIn(
                                    0f,
                                    1f
                                )

                        alpha =
                            progress

                        scaleX =
                            0.78f +
                                    progress *
                                            0.22f

                        scaleY =
                            0.78f +
                                    progress *
                                            0.22f
                    }
                    .zIndex(
                        0.5f
                    ),
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
                Icon(
                    imageVector =
                        Icons.AutoMirrored.Filled.Reply,
                    contentDescription =
                        "Reply",
                    modifier =
                        Modifier.size(
                            19.dp
                        ),
                    tint =
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
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
                            Modifier.clickable(
                                interactionSource =
                                    null,
                                indication =
                                    null,
                                onClick = {
                                    onReactionClick(
                                        reaction
                                    )
                                }
                            ),
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
