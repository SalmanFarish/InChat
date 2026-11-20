package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class EncryptedMessageDeliveryStatusTest {
    @Test
    fun mapsQueueProgressToWaitingSendingAndSent() {
        assertEquals(
            EncryptedMessageDeliveryStatus.WAITING,
            resolveEncryptedMessageDeliveryStatus(listOf(OlmDeliveryStatus.QUEUED.name), false),
        )
        assertEquals(
            EncryptedMessageDeliveryStatus.SENDING,
            resolveEncryptedMessageDeliveryStatus(listOf(OlmDeliveryStatus.IN_FLIGHT.name), false),
        )
        assertEquals(
            EncryptedMessageDeliveryStatus.SENT,
            resolveEncryptedMessageDeliveryStatus(listOf(OlmDeliveryStatus.UPLOADED.name), false),
        )
    }

    @Test
    fun recipientAcknowledgementMeansDeliveredAndOverridesOtherPendingDevices() {
        assertEquals(
            EncryptedMessageDeliveryStatus.DELIVERED,
            resolveEncryptedMessageDeliveryStatus(
                listOf(OlmDeliveryStatus.UPLOADED.name, OlmDeliveryStatus.QUEUED.name),
                recipientAcknowledged = true,
            ),
        )
    }

    @Test
    fun failedIsReportedOnlyWhenNoOtherDeliveryIsActive() {
        assertEquals(
            EncryptedMessageDeliveryStatus.FAILED,
            resolveEncryptedMessageDeliveryStatus(listOf(OlmDeliveryStatus.FAILED.name), false),
        )
        assertEquals(
            EncryptedMessageDeliveryStatus.WAITING,
            resolveEncryptedMessageDeliveryStatus(
                listOf(OlmDeliveryStatus.FAILED.name, OlmDeliveryStatus.QUEUED.name),
                false,
            ),
        )
    }

    @Test
    fun absentDeliveryStateIsNotPretendedToBeSent() {
        assertEquals(
            EncryptedMessageDeliveryStatus.NOT_TRACKED,
            resolveEncryptedMessageDeliveryStatus(emptyList(), false),
        )
    }
}
