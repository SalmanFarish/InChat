package com.example.inchat.crypto

import org.junit.Assert.assertTrue
import org.junit.Test

class OlmInboundReceiptIntegrityTest {
    private val encodedEnvelope = EncryptedOlmEnvelopeCodec.encode(
        EncryptedOlmEnvelope(
            senderDeviceId = "sender-device",
            recipientDeviceId = "receiver-device",
            sessionId = "session-1",
            ciphertext = byteArrayOf(1, 2, 3),
        ),
    )

    private fun receipt(
        receiptId: String = olmInboundReceiptId("alice", "sender-device", "message-1", "receiver-device"),
        envelope: String = encodedEnvelope,
        digest: String = sha256(envelope),
        receivedAtMillis: Long = 10L,
        senderDeviceId: String = "sender-device",
    ) = OlmInboundReceiptEntity(
        receiptId = receiptId,
        senderUserId = "alice",
        senderDeviceId = senderDeviceId,
        messageId = "message-1",
        recipientDeviceId = "receiver-device",
        envelope = envelope,
        envelopeSha256 = digest,
        status = OlmInboundReceiptStatus.PENDING.name,
        receivedAtMillis = receivedAtMillis,
        processedAtMillis = null,
    )

    @Test
    fun acceptsReceiptWhoseIdentityDigestAndRoutingAgree() {
        validateOlmInboundReceiptIntegrity(receipt())
    }

    @Test
    fun rejectsReceiptIdBoundToDifferentMetadata() {
        assertRejected {
            validateOlmInboundReceiptIntegrity(receipt(receiptId = "0".repeat(64)))
        }
    }

    @Test
    fun rejectsStoredEnvelopeDigestMismatch() {
        assertRejected {
            validateOlmInboundReceiptIntegrity(receipt(digest = "0".repeat(64)))
        }
    }

    @Test
    fun rejectsUnknownStatusAndInconsistentProcessedTimestamps() {
        assertRejected {
            validateOlmInboundReceiptIntegrity(receipt().copy(status = "UNKNOWN"))
        }
        assertRejected {
            validateOlmInboundReceiptIntegrity(
                receipt().copy(status = OlmInboundReceiptStatus.PROCESSED.name),
            )
        }
        assertRejected {
            validateOlmInboundReceiptIntegrity(
                receipt().copy(processedAtMillis = 20L),
            )
        }
        validateOlmInboundReceiptIntegrity(
            receipt().copy(
                status = OlmInboundReceiptStatus.PROCESSED.name,
                processedAtMillis = 20L,
            ),
        )
    }

    @Test
    fun rejectsEnvelopeRouteMismatchAndNegativeReceiveTime() {
        val wrongRoute = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = "other-sender",
                recipientDeviceId = "receiver-device",
                sessionId = "session-2",
                ciphertext = byteArrayOf(1, 2, 3),
            ),
        )
        assertRejected {
            validateOlmInboundReceiptIntegrity(
                receipt(envelope = wrongRoute, digest = sha256(wrongRoute)),
            )
        }
        assertRejected {
            validateOlmInboundReceiptIntegrity(receipt(receivedAtMillis = -1L))
        }
    }

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Corrupt durable receipt metadata must fail closed", rejected)
    }
}
