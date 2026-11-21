package com.example.inchat.crypto

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupSessionKeyDistributionPayloadTest {
    @Test
    fun roundTripsStrictGroupKeyDistributionAndBindsAllRoutingFields() {
        val payload = validPayload()
        val encoded = OlmGroupSessionKeyDistributionPayloadCodec.encode(payload)

        val decoded = OlmGroupSessionKeyDistributionPayloadCodec.decodeAndValidate(
            plaintext = encoded,
            expectedDistributionId = payload.distributionId,
            expectedGroupId = payload.groupId,
            expectedMembershipEpoch = payload.membershipEpoch,
            expectedSenderUserId = payload.senderUserId,
            expectedSenderDeviceId = payload.senderDeviceId,
            expectedRecipientUserId = payload.recipientUserId,
            expectedRecipientDeviceId = payload.recipientDeviceId,
        )

        assertEquals(payload, decoded)
    }

    @Test
    fun membershipValidationRequiresSenderAndRecipientInCurrentSnapshot() {
        val payload = validPayload()

        OlmGroupSessionKeyDistributionPayloadCodec.requireCurrentMembers(
            payload,
            setOf("sender-account", "recipient-account"),
        )

        assertRejected {
            OlmGroupSessionKeyDistributionPayloadCodec.requireCurrentMembers(
                payload,
                setOf("sender-account", "another-member"),
            )
        }
    }

    @Test
    fun rejectsUnknownFieldsAndNonCanonicalJson() {
        val encoded = OlmGroupSessionKeyDistributionPayloadCodec.encode(validPayload())

        assertRejected {
            decode(encoded.dropLast(1) + ",\"extra\":true}")
        }
        assertRejected {
            decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\": 1"))
        }
    }

    @Test
    fun rejectsFractionalEpochAndTimestamp() {
        val encoded = OlmGroupSessionKeyDistributionPayloadCodec.encode(validPayload())

        assertRejected {
            decode(encoded.replace("\"membershipEpoch\":7", "\"membershipEpoch\":7.5"))
        }
        assertRejected {
            decode(encoded.replace("\"issuedAtMillis\":123456", "\"issuedAtMillis\":123456.5"))
        }
    }

    @Test
    fun rejectsWrongGroupEpochAndRecipientDeviceBindings() {
        val encoded = OlmGroupSessionKeyDistributionPayloadCodec.encode(validPayload())

        assertRejected {
            OlmGroupSessionKeyDistributionPayloadCodec.decodeAndValidate(
                plaintext = encoded,
                expectedDistributionId = "distribution-1",
                expectedGroupId = "group-1",
                expectedMembershipEpoch = 8L,
                expectedSenderUserId = "sender-account",
                expectedSenderDeviceId = "sender-device",
                expectedRecipientUserId = "recipient-account",
                expectedRecipientDeviceId = "recipient-device",
            )
        }
        assertRejected {
            OlmGroupSessionKeyDistributionPayloadCodec.decodeAndValidate(
                plaintext = encoded,
                expectedDistributionId = "distribution-1",
                expectedGroupId = "group-1",
                expectedMembershipEpoch = 7L,
                expectedSenderUserId = "sender-account",
                expectedSenderDeviceId = "sender-device",
                expectedRecipientUserId = "recipient-account",
                expectedRecipientDeviceId = "different-device",
            )
        }
    }

    @Test
    fun rejectsMalformedAndNonCanonicalSessionKeyEncoding() {
        val encoded = OlmGroupSessionKeyDistributionPayloadCodec.encode(validPayload())

        assertRejected {
            decode(encoded.replace(validPayload().sessionKey, "not_base64!"))
        }
        assertRejected {
            decode(encoded.replace(validPayload().sessionKey, validPayload().sessionKey + "="))
        }
    }


    @Test
    fun rejectsSessionKeysOutsideNativeByteLengthBounds() {
        val payload = validPayload()
        val tooShort = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(31) { it.toByte() })
        val tooLong = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(1025) { it.toByte() })

        assertRejected {
            OlmGroupSessionKeyDistributionPayloadCodec.encode(
                payload.copy(sessionKey = tooShort),
            )
        }
        assertRejected {
            OlmGroupSessionKeyDistributionPayloadCodec.encode(
                payload.copy(sessionKey = tooLong),
            )
        }
    }

    @Test
    fun rejectsSelfDeliveryAndInvalidEpochOrGroupIdentifiers() {
        val payload = validPayload()

        assertRejected {
            OlmGroupSessionKeyDistributionPayloadCodec.encode(
                payload.copy(
                    recipientUserId = payload.senderUserId,
                    recipientDeviceId = payload.senderDeviceId,
                ),
            )
        }
        assertRejected {
            OlmGroupSessionKeyDistributionPayloadCodec.encode(payload.copy(membershipEpoch = 0L))
        }
        assertRejected {
            OlmGroupSessionKeyDistributionPayloadCodec.encode(payload.copy(groupId = "bad/group"))
        }
    }

    @Test
    fun onlyCurrentGroupMembersMayReceiveAKeyDistribution() {
        val payload = validPayload()
        var rejected = false
        try {
            OlmGroupSessionKeyDistributionPayloadCodec.requireCurrentMembers(
                payload,
                setOf("recipient-account", "other-member"),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(payload.senderUserId in setOf("recipient-account", "other-member"))
    }

    private fun decode(encoded: String) =
        OlmGroupSessionKeyDistributionPayloadCodec.decodeAndValidate(
            plaintext = encoded,
            expectedDistributionId = "distribution-1",
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
        assertTrue("Expected invalid group key payload to be rejected", rejected)
    }

    private fun validPayload() = OlmGroupSessionKeyDistributionPayload(
        distributionId = "distribution-1",
        groupId = "group-1",
        membershipEpoch = 7L,
        senderUserId = "sender-account",
        senderDeviceId = "sender-device",
        recipientUserId = "recipient-account",
        recipientDeviceId = "recipient-device",
        megolmSessionId = "1234567890abcdef",
        sessionKey = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(229) { index -> index.toByte() }),
        issuedAtMillis = 123456L,
    )
}
