package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OlmAuthenticatedMessagePayloadTest {
    private val payload = OlmAuthenticatedMessagePayload(
        messageId = "message-123",
        senderUserId = "alice",
        senderDeviceId = "alice-device",
        recipientUserId = "bob",
        recipientDeviceId = "bob-device",
        sentAtMillis = 1_800_000_000_000L,
        text = "authenticated hello",
    )

    @Test
    fun roundTripsAndAuthenticatesEveryRoutingIdentity() {
        val decoded = OlmAuthenticatedMessagePayloadCodec.decodeAndValidate(
            plaintext = OlmAuthenticatedMessagePayloadCodec.encode(payload),
            expectedMessageId = payload.messageId,
            expectedSenderUserId = payload.senderUserId,
            expectedSenderDeviceId = payload.senderDeviceId,
            expectedRecipientUserId = payload.recipientUserId,
            expectedRecipientDeviceId = payload.recipientDeviceId,
        )
        assertEquals(payload, decoded)
    }

    @Test
    fun rejectsMessageIdMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            decode(expectedMessageId = "different-message")
        }
    }

    @Test
    fun rejectsSenderAccountMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            decode(expectedSenderUserId = "mallory")
        }
    }

    @Test
    fun rejectsSenderDeviceMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            decode(expectedSenderDeviceId = "other-device")
        }
    }

    @Test
    fun rejectsRecipientAccountMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            decode(expectedRecipientUserId = "mallory")
        }
    }

    @Test
    fun rejectsRecipientDeviceMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            decode(expectedRecipientDeviceId = "other-device")
        }
    }

    @Test
    fun rejectsUnknownOrUnversionedFields() {
        val encoded = OlmAuthenticatedMessagePayloadCodec.encode(payload)
        val withUnknownField = encoded.dropLast(1) + ",\"extra\":true}"
        assertThrows(IllegalArgumentException::class.java) {
            OlmAuthenticatedMessagePayloadCodec.decodeAndValidate(
                withUnknownField,
                payload.messageId,
                payload.senderUserId,
                payload.senderDeviceId,
                payload.recipientUserId,
                payload.recipientDeviceId,
            )
        }
    }

    @Test
    fun rejectsFractionalTimestampEvenWhenMathematicallyWhole() {
        val encoded = OlmAuthenticatedMessagePayloadCodec.encode(payload)
            .replace("\"sentAtMillis\":1800000000000", "\"sentAtMillis\":1800000000000.0")
        assertThrows(IllegalArgumentException::class.java) {
            OlmAuthenticatedMessagePayloadCodec.decodeAndValidate(
                encoded, payload.messageId, payload.senderUserId, payload.senderDeviceId,
                payload.recipientUserId, payload.recipientDeviceId,
            )
        }
    }

    @Test
    fun rejectsNegativeTimestampAndEmptyTextBeforeEncryption() {
        assertThrows(IllegalArgumentException::class.java) {
            OlmAuthenticatedMessagePayloadCodec.encode(payload.copy(sentAtMillis = -1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OlmAuthenticatedMessagePayloadCodec.encode(payload.copy(text = ""))
        }
    }

    @Test
    fun rejectsInvalidIdentityCharactersAndOversizedText() {
        assertThrows(IllegalArgumentException::class.java) {
            OlmAuthenticatedMessagePayloadCodec.encode(payload.copy(senderUserId = "alice/other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OlmAuthenticatedMessagePayloadCodec.encode(payload.copy(text = "x".repeat(65 * 1024)))
        }
    }

    private fun decode(
        expectedMessageId: String = payload.messageId,
        expectedSenderUserId: String = payload.senderUserId,
        expectedSenderDeviceId: String = payload.senderDeviceId,
        expectedRecipientUserId: String = payload.recipientUserId,
        expectedRecipientDeviceId: String = payload.recipientDeviceId,
    ) = OlmAuthenticatedMessagePayloadCodec.decodeAndValidate(
        plaintext = OlmAuthenticatedMessagePayloadCodec.encode(payload),
        expectedMessageId = expectedMessageId,
        expectedSenderUserId = expectedSenderUserId,
        expectedSenderDeviceId = expectedSenderDeviceId,
        expectedRecipientUserId = expectedRecipientUserId,
        expectedRecipientDeviceId = expectedRecipientDeviceId,
    )
}
