package com.example.inchat.crypto

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupMessagePayloadTest {
    @Test
    fun roundTripsAndBindsTheAuthenticatedOlmRoute() {
        val payload = validPayload()
        val encoded = OlmGroupMessagePayloadCodec.encode(payload)
        val decoded = decode(encoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun requiresBothSenderAndRecipientInCurrentMembership() {
        val payload = validPayload()
        OlmGroupMessagePayloadCodec.requireCurrentMembers(
            payload,
            setOf(payload.senderUserId, payload.recipientUserId),
        )
        assertRejected {
            OlmGroupMessagePayloadCodec.requireCurrentMembers(
                payload,
                setOf(payload.recipientUserId, "another-member"),
            )
        }
    }

    @Test
    fun rejectsWrongMessageIdGroupEpochAndDeviceRoute() {
        val encoded = OlmGroupMessagePayloadCodec.encode(validPayload())
        assertRejected {
            OlmGroupMessagePayloadCodec.decodeAndValidate(
                encoded, "different-message", "group-1", 9L,
                "sender-account", "sender-device", "recipient-account", "recipient-device",
            )
        }
        assertRejected {
            OlmGroupMessagePayloadCodec.decodeAndValidate(
                encoded, "message-1", "group-1", 9L,
                "sender-account", "sender-device", "recipient-account", "recipient-device",
            )
        }
        assertRejected {
            OlmGroupMessagePayloadCodec.decodeAndValidate(
                encoded, "message-1", "group-1", 7L,
                "sender-account", "sender-device", "recipient-account", "other-device",
            )
        }
    }

    @Test
    fun rejectsUnknownFieldsAndNonCanonicalJson() {
        val encoded = OlmGroupMessagePayloadCodec.encode(validPayload())
        assertRejected { decode(encoded.dropLast(1) + ",\"extra\":true}") }
        assertRejected { decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\": 1")) }
    }

    @Test
    fun rejectsFractionalOrOutOfRangeMessageIndexes() {
        val encoded = OlmGroupMessagePayloadCodec.encode(validPayload())
        assertRejected {
            decode(encoded.replace("\"messageIndex\":12", "\"messageIndex\":12.5"))
        }
        assertRejected {
            OlmGroupMessagePayloadCodec.encode(validPayload().copy(messageIndex = 0x1_0000_0000L))
        }
        assertRejected {
            OlmGroupMessagePayloadCodec.encode(validPayload().copy(messageIndex = -1L))
        }
    }

    @Test
    fun rejectsNonCanonicalCiphertextAndSelfRouting() {
        val payload = validPayload()
        val encoded = OlmGroupMessagePayloadCodec.encode(payload)
        assertRejected { decode(encoded.replace(payload.ciphertext, payload.ciphertext + "=")) }
        assertRejected {
            OlmGroupMessagePayloadCodec.encode(
                payload.copy(recipientUserId = payload.senderUserId, recipientDeviceId = payload.senderDeviceId),
            )
        }
    }

    @Test
    fun rejectsInvalidMembershipAndTimestamp() {
        val payload = validPayload()
        assertRejected { OlmGroupMessagePayloadCodec.encode(payload.copy(membershipEpoch = 0L)) }
        assertRejected { OlmGroupMessagePayloadCodec.encode(payload.copy(sentAtMillis = -1L)) }
    }

    private fun decode(encoded: String) =
        OlmGroupMessagePayloadCodec.decodeAndValidate(
            plaintext = encoded,
            expectedMessageId = "message-1",
            expectedGroupId = "group-1",
            expectedMembershipEpoch = 7L,
            expectedSenderUserId = "sender-account",
            expectedSenderDeviceId = "sender-device",
            expectedRecipientUserId = "recipient-account",
            expectedRecipientDeviceId = "recipient-device",
        )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected invalid group-message payload to be rejected", rejected)
    }

    private fun validPayload() = OlmGroupMessagePayload(
        messageId = "message-1",
        groupId = "group-1",
        membershipEpoch = 7L,
        senderUserId = "sender-account",
        senderDeviceId = "sender-device",
        recipientUserId = "recipient-account",
        recipientDeviceId = "recipient-device",
        megolmSessionId = "1234567890abcdef",
        messageIndex = 12L,
        ciphertext = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(96) { it.toByte() }),
        sentAtMillis = 123456L,
    )
}
