package com.example.inchat.ui.chat

import com.example.inchat.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDeliveryStatusTest {
    private fun message(timestamp: Long) = Message(
        id = "message-$timestamp",
        senderId = "me",
        timestamp = timestamp
    )

    @Test
    fun messagesBeforeDeliveryCursorAreDelivered() {
        assertEquals(MessageDeliveryStatus.DELIVERED,
            getMessageDeliveryStatus(message(1_000L), 2_000L, 0L, emptySet(), true, 3_000L))
    }

    @Test
    fun messagesBeforeReadCursorAreReadEvenWhenDeliveryCursorIsOlder() {
        assertEquals(MessageDeliveryStatus.SEEN,
            getMessageDeliveryStatus(message(1_000L), 900L, 2_000L, emptySet(), true, 3_000L))
    }

    @Test
    fun messagesAfterBothCursorsRemainSent() {
        assertEquals(MessageDeliveryStatus.SENT,
            getMessageDeliveryStatus(message(3_000L), 2_000L, 2_000L, emptySet(), true, 4_000L))
    }

    @Test
    fun pendingMessageRemainsSendingUntilFirebaseConfirmsIt() {
        assertEquals(MessageDeliveryStatus.SENDING,
            getMessageDeliveryStatus(message(1_000L), 2_000L, 2_000L, setOf("message-1000"), true, 3_000L))
    }

    @Test
    fun pendingMessageWaitsWhenDatabaseIsDisconnected() {
        assertEquals(MessageDeliveryStatus.WAITING_FOR_CONNECTION,
            getMessageDeliveryStatus(message(1_000L), 0L, 0L, setOf("message-1000"), false, 3_000L))
    }
}
