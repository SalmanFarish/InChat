package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class OlmPendingReceiptProcessorTest {
    @Test
    fun pendingReceiptsUseVerifiedDeviceTrustAndExplicitOlmWireType() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "olm-pending-processor-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.olmInboundReceiptDao()
            val ledger = OlmInboundDeliveryLedger(dao, "recipient-device")
            val trustedDevice = device("trusted-sender-device", "trusted-identity")
            val untrustedDevice = device("untrusted-sender-device", "untrusted-identity")

            val committedBeforeCrash = envelope("trusted-sender-device", "recipient-device", 1)
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "committed-before-crash", committedBeforeCrash, 50L),
            )
            assertTrue(ledger.markProcessed("alice", "committed-before-crash", "trusted-sender-device"))

            val preKeyMessage = envelope("trusted-sender-device", "recipient-device", 0)
            val normalMessage = envelope("trusted-sender-device", "recipient-device", 1)
            val untrustedMessage = envelope("untrusted-sender-device", "recipient-device", 0)
            val unknownWireType = envelope("trusted-sender-device", "recipient-device", 2)
            val missingDeviceMessage = envelope("revoked-sender-device", "recipient-device", 1)

            assertEquals(OlmInboundRegistration.NEW, ledger.register("alice", "pre-key", preKeyMessage, 100L))
            assertEquals(OlmInboundRegistration.NEW, ledger.register("alice", "normal", normalMessage, 200L))
            assertEquals(OlmInboundRegistration.NEW, ledger.register("alice", "untrusted", untrustedMessage, 300L))
            assertEquals(OlmInboundRegistration.NEW, ledger.register("alice", "unknown-type", unknownWireType, 400L))
            assertEquals(OlmInboundRegistration.NEW, ledger.register("alice", "missing-device", missingDeviceMessage, 500L))

            val directoryReads = AtomicInteger()
            val handoffCalls = mutableListOf<Pair<String, OlmInboundCiphertextKind>>()
            val processor = OlmPendingReceiptProcessor(
                receiptDao = dao,
                remoteDevicesForUser = {
                    directoryReads.incrementAndGet()
                    listOf(trustedDevice, untrustedDevice)
                },
                trustVerifier = OlmDeviceTrustVerifier { userId, deviceId, identityKey ->
                    userId == "alice" &&
                        deviceId == trustedDevice.deviceId &&
                        identityKey == trustedDevice.ed25519IdentityKey
                },
                handoff = OlmInboundReceiptHandoff { receiptId, remoteDevice, senderUid, kind, localUid ->
                    assertEquals("alice", senderUid)
                    assertEquals("recipient", localUid)
                    assertEquals(trustedDevice.deviceId, remoteDevice.deviceId)
                    handoffCalls += receiptId to kind
                    OlmInboundHandoffResult.COMMITTED
                },
                finalizer = OlmCommittedReceiptFinalizer { receiptId, _ ->
                    dao.markFinalized(receiptId) == 1
                },
                authenticatedLocalUserId = { "recipient" },
            )

            val result = processor.processPending(limit = 20)

            assertEquals(1, result.finalizationChecked)
            assertEquals(1, result.finalized)
            assertEquals(5, result.pendingScanned)
            assertEquals(2, result.committed)
            assertEquals(0, result.alreadyCommitted)
            assertEquals(1, result.waitingForTrust)
            assertEquals(1, result.missingRemoteDevice)
            assertEquals(1, result.failed)
            assertEquals(1, directoryReads.get())
            assertEquals(
                listOf(
                    "pre-key" to OlmInboundCiphertextKind.PRE_KEY,
                    "normal" to OlmInboundCiphertextKind.NORMAL,
                ),
                handoffCalls.map { (receiptId, kind) ->
                    dao.getByReceiptId(receiptId)!!.messageId to kind
                },
            )
            assertEquals(
                OlmInboundReceiptStatus.FINALIZED.name,
                dao.getByReceiptId(
                    receiptId("alice", "trusted-sender-device", "committed-before-crash", "recipient-device"),
                )?.status,
            )
            assertEquals(5, dao.getPending(20).size)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun wireTypeClassifierRejectsUnknownAndTruncatedMessages() {
        assertEquals(
            OlmInboundCiphertextKind.PRE_KEY,
            OlmInboundCiphertextKind.fromEnvelope(
                EncryptedOlmEnvelope("sender-device", "recipient-device", "session", byteArrayOf(0, 1)),
            ),
        )
        assertEquals(
            OlmInboundCiphertextKind.NORMAL,
            OlmInboundCiphertextKind.fromEnvelope(
                EncryptedOlmEnvelope("sender-device", "recipient-device", "session", byteArrayOf(1, 1)),
            ),
        )
        listOf(byteArrayOf(2, 1), byteArrayOf(), byteArrayOf(0)).forEach { wire ->
            try {
                OlmInboundCiphertextKind.fromEnvelope(
                    EncryptedOlmEnvelope("sender-device", "recipient-device", "session", wire),
                )
                throw AssertionError("Unknown or truncated Olm message type should be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    private fun device(deviceId: String, identityKey: String) = PublicDeviceKeyBundle(
        deviceId = deviceId,
        ed25519IdentityKey = identityKey,
        curve25519IdentityKey = "curve-key-$deviceId",
        curve25519IdentityKeySignature = "signature-$deviceId",
        fallbackKey = SignedOlmPreKey("fallback", "fallback-id", "fallback-key", "fallback-signature"),
        oneTimeKeys = emptyList(),
    )

    private fun envelope(sender: String, recipient: String, wireType: Int): String =
        EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = sender,
                recipientDeviceId = recipient,
                sessionId = "session-$sender",
                ciphertext = byteArrayOf(wireType.toByte(), 1),
            ),
        )

    private fun receiptId(sender: String, device: String, message: String, recipient: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$sender\u0000$device\u0000$message\u0000$recipient".toByteArray())
            .joinToString("") { "%02x".format(it) }
}
