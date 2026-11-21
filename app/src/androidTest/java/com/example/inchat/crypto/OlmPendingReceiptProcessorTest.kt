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
                    assertEquals(1, dao.markProcessed(receiptId, 600L))
                    assertEquals(1, dao.markFinalized(receiptId))
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
            assertEquals(0, result.deferredScanned)
            assertEquals(2, result.committed)
            assertEquals(0, result.alreadyCommitted)
            assertEquals(1, result.waitingForTrust)
            assertEquals(1, result.missingRemoteDevice)
            assertEquals(1, result.rejected)
            assertEquals(0, result.failed)
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
            assertEquals(0, dao.getPending(20).size)
            assertEquals(
                OlmInboundReceiptStatus.WAITING_FOR_DEVICE.name,
                dao.getByReceiptId(
                    receiptId("alice", "untrusted-sender-device", "untrusted", "recipient-device"),
                )?.status,
            )
            assertEquals(
                OlmInboundReceiptStatus.WAITING_FOR_DEVICE.name,
                dao.getByReceiptId(
                    receiptId("alice", "revoked-sender-device", "missing-device", "recipient-device"),
                )?.status,
            )
            assertEquals(
                OlmInboundReceiptStatus.REJECTED.name,
                dao.getByReceiptId(
                    receiptId("alice", "trusted-sender-device", "unknown-type", "recipient-device"),
                )?.status,
            )
            val deferredResult = processor.processPending(limit = 20)
            assertEquals(2, deferredResult.deferredScanned)
            assertEquals(1, deferredResult.waitingForTrust)
            assertEquals(1, deferredResult.missingRemoteDevice)
            assertEquals(0, deferredResult.committed)
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


    @Test
    fun corruptDurableReceiptsAreQuarantinedBeforeDirectoryOrRatchetAccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "olm-pending-corrupt-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.olmInboundReceiptDao()
            val encoded = envelope("sender-device", "recipient-device", 1)
            fun row(
                messageId: String,
                rowReceiptId: String = receiptId(
                    "alice", "sender-device", messageId, "recipient-device",
                ),
                digest: String = sha256(encoded),
                processedAtMillis: Long? = null,
            ) = OlmInboundReceiptEntity(
                receiptId = rowReceiptId,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                messageId = messageId,
                recipientDeviceId = "recipient-device",
                envelope = encoded,
                envelopeSha256 = digest,
                status = OlmInboundReceiptStatus.PENDING.name,
                receivedAtMillis = 100L,
                processedAtMillis = processedAtMillis,
            )

            val corruptedRows = listOf(
                row("bad-digest", digest = "0".repeat(64)),
                row("bad-identity", rowReceiptId = "f".repeat(64)),
                row("bad-pending-timestamp", processedAtMillis = 99L),
            )
            corruptedRows.forEach { assertTrue(dao.insertIfAbsent(it) != -1L) }

            var directoryReads = 0
            var handoffCalls = 0
            val processor = OlmPendingReceiptProcessor(
                receiptDao = dao,
                remoteDevicesForUser = {
                    directoryReads++
                    error("Corrupt receipt must be rejected before device-directory access")
                },
                trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
                handoff = OlmInboundReceiptHandoff { _, _, _, _, _ ->
                    handoffCalls++
                    error("Corrupt receipt must never reach the native handoff")
                },
                finalizer = OlmCommittedReceiptFinalizer { _, _ -> true },
                authenticatedLocalUserId = { "recipient" },
            )

            val result = processor.processPending(limit = 10)

            assertEquals(3, result.pendingScanned)
            assertEquals(3, result.rejected)
            assertEquals(0, result.failed)
            assertEquals(0, directoryReads)
            assertEquals(0, handoffCalls)
            assertEquals(0, dao.getPending(10).size)
            corruptedRows.forEach { corrupt ->
                assertEquals(
                    OlmInboundReceiptStatus.REJECTED.name,
                    dao.getByReceiptId(corrupt.receiptId)?.status,
                )
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
