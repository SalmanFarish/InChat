package com.example.inchat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.FirebaseServerClock
import kotlinx.coroutines.delay

enum class MessageDeliveryStatus {
    NONE,
    WAITING_FOR_CONNECTION,
    SENDING,
    SENT,
    DELIVERED,
    SEEN_JUST_NOW,
    SEEN
}

@Composable
fun rememberDeliveryStatusClock(
    otherUserReadTimestamp: Long
): Long {

    var clock by
    remember {
        mutableLongStateOf(
            FirebaseServerClock.now()
        )
    }

    LaunchedEffect(
        otherUserReadTimestamp
    ) {

        if (
            otherUserReadTimestamp <= 0L
        ) {

            clock =
                FirebaseServerClock.now()

            return@LaunchedEffect
        }

        while (true) {

            clock =
                System.currentTimeMillis()

            delay(
                15_000L
            )
        }
    }

    return clock
}

fun getMessageDeliveryStatus(
    message: Message,
    otherUserDeliveredTimestamp: Long,
    otherUserReadTimestamp: Long,
    pendingMessageIds: Set<String>,
    isConnected: Boolean,
    currentTimeMillis: Long
): MessageDeliveryStatus {

    if (message.id.isBlank()) {
        return MessageDeliveryStatus.NONE
    }

    if (pendingMessageIds.contains(message.id)) {
        return if (isConnected) {
            MessageDeliveryStatus.SENDING
        } else {
            MessageDeliveryStatus.WAITING_FOR_CONNECTION
        }
    }

    if (
        otherUserReadTimestamp > 0L &&
        message.timestamp > 0L &&
        otherUserReadTimestamp >= message.timestamp
    ) {
        return MessageDeliveryStatus.SEEN
    }

    if (
        otherUserDeliveredTimestamp > 0L &&
        message.timestamp > 0L &&
        otherUserDeliveredTimestamp >= message.timestamp
    ) {
        return MessageDeliveryStatus.DELIVERED
    }

    return MessageDeliveryStatus.SENT
}

fun deliveryStatusText(
    status: MessageDeliveryStatus
): String {

    return when (
        status
    ) {

        MessageDeliveryStatus.NONE ->
            ""

        MessageDeliveryStatus.WAITING_FOR_CONNECTION ->
            "Waiting for connection…"

        MessageDeliveryStatus.SENDING ->
            "Sending…"

        MessageDeliveryStatus.SENT ->
            "Sent"

        MessageDeliveryStatus.DELIVERED ->
            "Delivered"

        MessageDeliveryStatus.SEEN_JUST_NOW,
        MessageDeliveryStatus.SEEN ->
            "Read"
    }
}

@Composable
fun MessageDeliveryIndicator(
    status: MessageDeliveryStatus,
    tint: androidx.compose.ui.graphics.Color
) {
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (fadeIn(tween(170)) + scaleIn(initialScale = 0.72f)) togetherWith
                fadeOut(tween(110))
        },
        label = "message-delivery-state"
    ) { current ->
        when (current) {
            MessageDeliveryStatus.SENT -> Icon(
                imageVector = Icons.Filled.Done,
                contentDescription = "Sent",
                modifier = Modifier.size(14.dp),
                tint = tint
            )
            MessageDeliveryStatus.DELIVERED -> Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Delivered",
                modifier = Modifier.size(14.dp),
                tint = tint
            )
            MessageDeliveryStatus.SEEN_JUST_NOW,
            MessageDeliveryStatus.SEEN -> Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Read",
                modifier = Modifier.size(14.dp),
                tint = tint
            )
            MessageDeliveryStatus.SENDING -> DeliveryPulse(tint)
            MessageDeliveryStatus.WAITING_FOR_CONNECTION ->
                Text(text = "!", fontSize = 11.sp, color = tint, maxLines = 1)
            MessageDeliveryStatus.NONE -> Unit
        }
    }
}

@Composable
internal fun DeliveryPulse(tint: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "delivery-pulse")
    val alpha = transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "delivery-pulse-alpha"
    ).value

    Text(
        text = "•••",
        fontSize = 9.sp,
        color = tint.copy(alpha = alpha),
        maxLines = 1
    )
}
