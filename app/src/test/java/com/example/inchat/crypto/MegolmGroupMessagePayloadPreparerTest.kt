package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class MegolmGroupMessagePayloadPreparerTest {
    @Test
    fun preparesOneRecipientBoundEnvelopeFromEncryptedRatchetOutput() {
        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-1",
            epoch = 4L,
            members = mapOf(
                "sender-account" to "admin",
                "recipient-account" to "member",
            ),
        )
        val ciphertext = ByteArray(64) { it.toByte() }
        val preparer = MegolmGroupMessagePayloadPreparer(
            authenticatedUserId = { "sender-account" },
            localDeviceId = { "sender-device" },
        )

        val encoded = preparer.prepareForRecipient(
            membership = membership,
            recipientUserId = "recipient-account",
            recipientDeviceId = "recipient-device",
            messageId = "message-1",
            encryptedMessage = EncryptedMegolmMessage(
                sessionId = "1234567890abcdef",
                messageIndex = 5L,
                ciphertext = ciphertext,
            ),
            sentAtMillis = 1234L,
        )
        val decoded = OlmGroupMessagePayloadCodec.decodeAndValidate(
            plaintext = encoded,
            expectedMessageId = "message-1",
            expectedGroupId = "group-1",
            expectedMembershipEpoch = 4L,
            expectedSenderUserId = "sender-account",
            expectedSenderDeviceId = "sender-device",
            expectedRecipientUserId = "recipient-account",
            expectedRecipientDeviceId = "recipient-device",
        )

        assertEquals(5L, decoded.messageIndex)
        assertEquals(
            Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext),
            decoded.ciphertext,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesRecipientOutsideTheAuthoritativeMembershipSnapshot() {
        MegolmGroupMessagePayloadPreparer(
            authenticatedUserId = { "sender-account" },
            localDeviceId = { "sender-device" },
        ).prepareForRecipient(
            membership = GroupCryptoMembershipSnapshot(
                "group-1", 4L,
                mapOf("sender-account" to "admin", "member" to "member"),
            ),
            recipientUserId = "removed-member",
            recipientDeviceId = "recipient-device",
            messageId = "message-1",
            encryptedMessage = EncryptedMegolmMessage(
                "1234567890abcdef", 0L, ByteArray(64),
            ),
            sentAtMillis = 1234L,
        )
    }
}
