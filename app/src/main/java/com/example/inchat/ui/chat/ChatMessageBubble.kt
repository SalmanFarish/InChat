package com.example.inchat.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.ChatRepository

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableMessageBubble(
    message: Message,
    isMe: Boolean,
    deliveryStatus: MessageDeliveryStatus,
    readTimestamp: Long = 0L,
    currentTimeMillis: Long = 0L,
    currentUserId: String,
    chatTheme: ChatTheme,
    onReply: () -> Unit,
    onLongClick: () -> Unit,
    onQuotedReplyClick: (String) -> Unit,
    onReactionClick: (String) -> Unit,
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
            54.dp.toPx()
        }

    val replyTravelDistancePx =
        with(density) {
            76.dp.toPx()
        }

    val replySwipeOffset = remember(message.id) {
        Animatable(0f)
    }
    var replyArmed by remember(message.id) {
        mutableStateOf(false)
    }
    val replyHaptics = LocalHapticFeedback.current
    val replyResetScope = rememberCoroutineScope()

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
                    isMe,
                    replyTriggerDistancePx,
                    replyTravelDistancePx
                ) {
                    var replyTriggered = false

                    detectHorizontalDragGestures(
                        onDragStart = {
                            replyTriggered = false
                            replyArmed = false
                            replyResetScope.launch {
                                replySwipeOffset.snapTo(0f)
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            val movingTowardCenter =
                                if (isMe) dragAmount < 0f else dragAmount > 0f

                            if (movingTowardCenter && !replyTriggered) {
                                change.consume()

                                // Slight resistance makes the gesture deliberate
                                // and keeps the icon reveal smooth on fast swipes.
                                val nextOffset = (
                                    replySwipeOffset.value +
                                        kotlin.math.abs(dragAmount) * 0.92f
                                    ).coerceIn(0f, replyTravelDistancePx)

                                replySwipeOffset.snapTo(nextOffset)

                                val reachedReplyThreshold =
                                    nextOffset >= replyTriggerDistancePx

                                if (reachedReplyThreshold && !replyArmed) {
                                    replyArmed = true
                                    replyHaptics.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove
                                    )
                                } else if (!reachedReplyThreshold && replyArmed) {
                                    replyArmed = false
                                }
                            }
                        },
                        onDragEnd = {
                            val shouldReply = replyArmed && !replyTriggered
                            replyTriggered = shouldReply
                            replyArmed = false

                            if (shouldReply) {
                                onReply()
                            }

                            replyResetScope.launch {
                                replySwipeOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        },
                        onDragCancel = {
                            replyArmed = false
                            replyResetScope.launch {
                                replySwipeOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
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
                                -replySwipeOffset.value
                            } else {
                                replySwipeOffset.value
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
                            max =
                                maxBubbleWidth
                        )
                        .animateContentSize()
            ) {
                val bubbleShape =
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isMe) 18.dp else 5.dp,
                        bottomEnd = if (isMe) 5.dp else 18.dp
                    )

                Column(
                    modifier =
                        Modifier
                            .combinedClickable(
                                interactionSource =
                                    null,
                                indication =
                                    null,
                                onClick = {},
                                onDoubleClick = {
                                    onReactionClick(
                                        "❤️"
                                    )
                                },
                                onLongClick =
                                    onLongClick
                            )
                            .clip(bubbleShape)
                            .background(
                                if (isMe) {
                                    chatTheme.outgoingBubbleColor
                                } else {
                                    chatTheme.incomingBubbleColor
                                }
                            )
                            .border(
                                width = 0.7.dp,
                                color = if (isMe) {
                                    chatTheme.outgoingBorderColor
                                } else {
                                    chatTheme.incomingBorderColor
                                },
                                shape = bubbleShape
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
                            chatTheme =
                                chatTheme,
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
                                chatTheme.outgoingTextColor
                            } else {
                                chatTheme.incomingTextColor
                            }
                    )

                    val metadataStyle =
                        MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 0.35.sp
                        )

                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp, end = 1.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.edited) {
                            Text(
                                text = "EDITED",
                                style = metadataStyle.copy(letterSpacing = 0.5.sp),
                                fontWeight = FontWeight.Medium,
                                color = chatTheme.secondaryTextColor.copy(alpha = 0.78f),
                                maxLines = 1
                            )

                            Spacer(Modifier.width(6.dp))
                        }

                        Text(
                            text = formatMessageTime(message.timestamp),
                            style = metadataStyle,
                            fontWeight = FontWeight.Medium,
                            color = chatTheme.secondaryTextColor.copy(
                                alpha = if (isMe) 0.92f else 0.78f
                            ),
                            maxLines = 1
                        )

                        if (
                            isMe &&
                            deliveryStatus != MessageDeliveryStatus.NONE
                        ) {
                            val receiptText = when (deliveryStatus) {
                                MessageDeliveryStatus.SEEN_JUST_NOW,
                                MessageDeliveryStatus.SEEN -> "READ"
                                MessageDeliveryStatus.SENT -> "SENT"
                                MessageDeliveryStatus.DELIVERED -> "DELIVERED"
                                MessageDeliveryStatus.SENDING -> "SENDING…"
                                MessageDeliveryStatus.WAITING_FOR_CONNECTION -> "SAVING…"
                                MessageDeliveryStatus.NONE -> ""
                            }

                            if (receiptText.isNotBlank()) {
                                Spacer(Modifier.width(7.dp))

                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .background(
                                            color = chatTheme.statusAccentColor,
                                            shape = CircleShape
                                        )
                                )

                                Spacer(Modifier.width(4.dp))

                                Text(
                                    text = receiptText,
                                    style = metadataStyle.copy(letterSpacing = 0.45.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = chatTheme.statusAccentColor,
                                    maxLines = 1
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
                        onReactionClick = {
                            reaction ->
                            if (
                                message.reactions[currentUserId] ==
                                reaction
                            ) {
                                onReactionClick(
                                    reaction
                                )
                            }
                        },
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
                        MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 0.35.sp
                        ),
                    fontWeight =
                        FontWeight.Medium,
                    textAlign =
                        TextAlign.Center,
                    maxLines =
                        1,
                    color =
                        chatTheme.secondaryTextColor
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
                        38.dp
                    )
                    .graphicsLayer {
                        val progress =
                            (
                                replySwipeOffset.value /
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
                if (replyArmed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
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
                        if (replyArmed) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
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
                            if (count > 1) {
                                "$reaction$count"
                            } else {
                                reaction
                            },
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
