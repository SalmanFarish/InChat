package com.example.inchat.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import com.example.inchat.crypto.EncryptedMessageDeliveryStatus
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.ui.theme.GoogleSansFlex

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
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    showSenderName: Boolean = false,
    onClick: () -> Unit = {},
    isSelected: Boolean = false,
    encryptedDeliveryStatus: EncryptedMessageDeliveryStatus? = null,
) {
    val density =
        LocalDensity.current


    val replyTriggerDistancePx =
        with(density) {
            54.dp.toPx()
        }

    val replyTravelDistancePx =
        with(density) {
            76.dp.toPx()
        }

    var replySwipeOffsetPx by remember(message.id) {
        mutableFloatStateOf(0f)
    }
    var replyArmed by remember(message.id) {
        mutableStateOf(false)
    }
    var replyResetJob by remember(message.id) {
        mutableStateOf<Job?>(null)
    }
    val replyHaptics = LocalHapticFeedback.current
    val replyResetScope = rememberCoroutineScope()

    fun settleReplySwipe() {
        val releaseOffset = replySwipeOffsetPx
        replyResetJob?.cancel()
        replyResetJob = replyResetScope.launch {
            animate(
                initialValue = releaseOffset,
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ ->
                replySwipeOffsetPx = value
            }
        }
    }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = if (isFirstInGroup) 3.dp else 0.dp)
                .animateContentSize()
                /*
                 * This detector handles only the message's
                 * center-directed reply swipe.
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
                            replyResetJob?.cancel()
                            replySwipeOffsetPx = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            val movingTowardCenter =
                                if (isMe) dragAmount < 0f else dragAmount > 0f

                            if (movingTowardCenter && !replyTriggered) {
                                change.consume()

                                // Slight resistance makes the gesture deliberate
                                // and keeps the icon reveal smooth on fast swipes.
                                val nextOffset = (
                                    replySwipeOffsetPx +
                                        kotlin.math.abs(dragAmount) * 0.92f
                                    ).coerceIn(0f, replyTravelDistancePx)

                                replySwipeOffsetPx = nextOffset

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

                            settleReplySwipe()
                        },
                        onDragCancel = {
                            replyArmed = false
                            settleReplySwipe()
                        }
                    )
                }
    ) {
        /* Keep message bubbles comfortably sized on normal screens. */
        val maxBubbleWidth =
            (maxWidth * 0.80f)
                .coerceAtLeast(68.dp)
                .coerceAtMost(340.dp)

        /*
         * The timestamp is revealed immediately beside the
         * bubble's trailing edge. This keeps times readable and
         * prevents the bubble or time from being clipped.
         */
        Box(
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
                    .zIndex(1f)
        ) {
            Column(
                modifier =
                    Modifier
                        .align(if (isMe) Alignment.CenterEnd else Alignment.CenterStart)
                        .widthIn(max = maxBubbleWidth)
                        .animateContentSize()
            ) {
                // shadcn MessageHeader: group identity stays outside the bubble.
                if (showSenderName && message.senderNickname.isNotBlank()) {
                    Text(
                        text = message.senderNickname,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = GoogleSansFlex,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            letterSpacing = 0.05.sp
                        ),
                        fontWeight = FontWeight.Medium,
                        color = chatTheme.statusAccentColor,
                        maxLines = 1
                    )
                }

                // Keep all message corners consistently rounded across group boundaries.
                val bubbleShape = RoundedCornerShape(20.dp)

                Column(
                    modifier =
                        Modifier
                            // Align the bubble independently from the timestamp footer.
                            // Short messages can have a wider footer than text; without
                            // this, outgoing bubbles inherit the footer's left edge.
                            .align(if (isMe) Alignment.End else Alignment.Start)
                            .combinedClickable(
                                interactionSource = null,
                                indication = null,
                                onClick = onClick,
                                onDoubleClick = { onReactionClick("❤️") },
                                onLongClick = onLongClick
                            )
                            .clip(bubbleShape)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                                } else if (isMe) {
                                    chatTheme.outgoingBubbleColor
                                } else {
                                    chatTheme.incomingBubbleColor
                                }
                            )
                            .border(
                                width = if (isSelected) 1.6.dp else 0.7.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else if (isMe) {
                                    chatTheme.outgoingBorderColor
                                } else {
                                    chatTheme.incomingBorderColor
                                },
                                shape = bubbleShape
                            )
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    message.replyTo?.let { reply ->
                        ReplyMessagePreview(
                            reply = reply,
                            isMe = isMe,
                            chatTheme = chatTheme,
                            onClick = { onQuotedReplyClick(reply.messageId) }
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = GoogleSansFlex,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            letterSpacing = 0.sp
                        ),
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }

                // shadcn BubbleReactions: attach the reaction pill to the bubble edge.
                if (message.reactions.isNotEmpty()) {
                    ReactionSummary(
                        reactions = message.reactions,
                        currentUserId = currentUserId,
                        onReactionClick = { reaction ->
                            if (message.reactions[currentUserId] == reaction) {
                                onReactionClick(reaction)
                            }
                        },
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .offset(y = (-4).dp)
                            .align(if (isMe) Alignment.End else Alignment.Start)
                    )
                }

                // shadcn MessageFooter: keep time and delivery state visible below the bubble.
                val metadataStyle = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = GoogleSansFlex,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 0.1.sp
                )

                Row(
                    modifier = Modifier
                        .align(if (isMe) Alignment.End else Alignment.Start)
                        .padding(top = if (message.reactions.isNotEmpty()) 0.dp else 3.dp)
                        .padding(horizontal = 4.dp),
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

                    if (isMe && encryptedDeliveryStatus != null) {
                        Spacer(Modifier.width(7.dp))
                        EncryptedMessageDeliveryIndicator(
                            status = encryptedDeliveryStatus,
                            tint = if (
                                encryptedDeliveryStatus == EncryptedMessageDeliveryStatus.DELIVERED
                            ) {
                                chatTheme.statusAccentColor
                            } else {
                                chatTheme.secondaryTextColor.copy(alpha = 0.92f)
                            }
                        )
                    } else if (
                        isMe &&
                        deliveryStatus != MessageDeliveryStatus.NONE
                    ) {
                        Spacer(Modifier.width(7.dp))
                        MessageDeliveryIndicator(
                            status = deliveryStatus,
                            tint = if (
                                deliveryStatus == MessageDeliveryStatus.SEEN_JUST_NOW ||
                                deliveryStatus == MessageDeliveryStatus.SEEN
                            ) {
                                chatTheme.statusAccentColor
                            } else {
                                chatTheme.secondaryTextColor.copy(alpha = 0.92f)
                            }
                        )
                    }
                }
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
                18.dp
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
                                FontWeight.Medium
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
