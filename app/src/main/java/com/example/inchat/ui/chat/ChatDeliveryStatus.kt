package com.example.inchat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Message
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MessageDeliveryStatus {
    NONE,
    WAITING_FOR_CONNECTION,
    SENDING,
    SENT,
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
            System.currentTimeMillis()
        )
    }

    LaunchedEffect(
        otherUserReadTimestamp
    ) {

        clock =
            System.currentTimeMillis()

        if (
            otherUserReadTimestamp <= 0L
        ) {

            return@LaunchedEffect
        }

        val elapsed =
            System.currentTimeMillis() -
                    otherUserReadTimestamp

        val remaining =
            (
                    60_000L -
                            elapsed
                    )
                .coerceAtLeast(
                    0L
                )

        if (
            remaining > 0L
        ) {

            delay(
                remaining + 100L
            )

            clock =
                System.currentTimeMillis()
        }
    }

    return clock
}

fun getMessageDeliveryStatus(
    message: Message,
    otherUserReadTimestamp: Long,
    pendingMessageIds: Set<String>,
    isConnected: Boolean,
    currentTimeMillis: Long
): MessageDeliveryStatus {

    if (
        message.id.isBlank()
    ) {

        return MessageDeliveryStatus.NONE
    }

    /*
     * Firebase persistence has not completed the write yet.
     *
     * Offline:
     *     Waiting for connection…
     *
     * Online but still being written:
     *     Sending…
     */
    if (
        pendingMessageIds.contains(
            message.id
        )
    ) {

        return if (
            isConnected
        ) {

            MessageDeliveryStatus.SENDING

        } else {

            MessageDeliveryStatus.WAITING_FOR_CONNECTION
        }
    }

    /*
     * Once the other participant has read beyond this
     * message's timestamp, the message is considered seen.
     */
    if (
        otherUserReadTimestamp > 0L &&
        message.timestamp > 0L &&
        otherUserReadTimestamp >=
        message.timestamp
    ) {

        val difference =
            currentTimeMillis -
                    otherUserReadTimestamp

        return if (
            difference <= 60_000L
        ) {

            MessageDeliveryStatus.SEEN_JUST_NOW

        } else {

            MessageDeliveryStatus.SEEN
        }
    }

    /*
     * The message has successfully left the pending queue,
     * but the other participant has not read it yet.
     */
    return MessageDeliveryStatus.SENT
}

fun formatSeenReceipt(
    readTimestamp: Long,
    currentTimeMillis: Long
): String {
    if (readTimestamp <= 0L) return "Seen"

    val elapsed =
        (currentTimeMillis - readTimestamp).coerceAtLeast(0L)

    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour

    return when {
        elapsed < minute ->
            "Seen just now"
        elapsed < hour ->
            "Seen " + (elapsed / minute) + " min ago"
        elapsed < day ->
            "Seen " + (elapsed / hour) + " hr ago"
        else ->
            "Seen " +
                SimpleDateFormat(
                    "h:mm a",
                    Locale.getDefault()
                ).format(Date(readTimestamp))
    }
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

        MessageDeliveryStatus.SEEN_JUST_NOW ->
            "Seen just now"

        MessageDeliveryStatus.SEEN ->
            "Seen"
    }
}

@Composable
fun MessageDeliveryIndicator(
    status: MessageDeliveryStatus,
    tint: androidx.compose.ui.graphics.Color
) {
    val indicatorText =
        when (status) {
            MessageDeliveryStatus.WAITING_FOR_CONNECTION ->
                "!"

            MessageDeliveryStatus.SENDING ->
                "…"

            MessageDeliveryStatus.SENT ->
                "✓"

            MessageDeliveryStatus.SEEN_JUST_NOW,
            MessageDeliveryStatus.SEEN ->
                "✓✓"

            MessageDeliveryStatus.NONE ->
                ""
        }

    androidx.compose.material3.Text(
        text = indicatorText,
        fontSize = 12.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = tint,
        modifier = androidx.compose.ui.Modifier,
        maxLines = 1
    )
}
