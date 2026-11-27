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
            val ledger = OlmInboundDeliveryLedger(dao, "recipient", "recipient-device")
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
            assertEquals(0, dao.getPending("recipient", 20).size)
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
    fun processorBDoesNotSelectOrFinalizeAccountAReceiptsOnSharedDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "olm-pending-account-scope.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.olmInboundReceiptDao()
            val accountA = OlmInboundDeliveryLedger(dao, "account-a", "recipient-device")
            val encoded = envelope("sender-device", "recipient-device", 1)
            assertEquals(
                OlmInboundRegistration.NEW,
                accountA.register("alice", "same-installation-message", encoded, 100L),
            )
            var directoryReads = 0
            var handoffs = 0
            var finalizations = 0
            val processorForB = OlmPendingReceiptProcessor(
                receiptDao = dao,
                remoteDevicesForUser = {
                    directoryReads++
                    error("Account B must not resolve senders for Account A receipts")
                },
                trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
                handoff = OlmInboundReceiptHandoff { _, _, _, _, _ ->
                    handoffs++
                    error("Account B must not decrypt Account A receipts")
                },
                finalizer = OlmCommittedReceiptFinalizer { _, _ ->
                    finalizations++
                    error("Account B must not finalize Account A receipts")
                },
                authenticatedLocalUserId = { "account-b" },
            )

            val result = processorForB.processPending(limit = 10)

            assertEquals(0, result.pendingScanned)
            assertEquals(0, result.deferredScanned)
            assertEquals(0, result.finalizationChecked)
            assertEquals(0, directoryReads)
            assertEquals(0, handoffs)
            assertEquals(0, finalizations)
            assertEquals(1, dao.getPending("account-a", 10).size)
            assertTrue(dao.getPending("account-b", 10).isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun failedFirstBatchRotatesBehindUntouchedReceiptsOnNextPass() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "olm-pending-fair-retry-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.olmInboundReceiptDao()
            val ledger = OlmInboundDeliveryLedger(dao, "recipient", "recipient-device")
            val trustedDevice = device("sender-device", "sender-identity")
            repeat(51) { index ->
                val messageId = if (index == 50) "later-key-receipt" else "blocked-message-$index"
                assertEquals(
                    OlmInboundRegistration.NEW,
                    ledger.register(
                        "alice",
                        messageId,
                        envelope("sender-device", "recipient-device", 1),
                        100L + index,
                    ),
                )
            }

            var handoffCalls = 0
            val processor = OlmPendingReceiptProcessor(
                receiptDao = dao,
                remoteDevicesForUser = { listOf(trustedDevice) },
                trustVerifier = OlmDeviceTrustVerifier { userId, deviceId, identityKey ->
                    userId == "alice" &&
                        deviceId == trustedDevice.deviceId &&
                        identityKey == trustedDevice.ed25519IdentityKey
                },
                handoff = OlmInboundReceiptHandoff { receiptId, _, _, _, _ ->
                    handoffCalls++
                    val messageId = requireNotNull(dao.getByReceiptId(receiptId)).messageId
                    if (messageId != "later-key-receipt") {
                        error("Simulated missing Megolm dependency")
                    }
                    assertEquals(1, dao.markProcessed(receiptId, 20_000L))
                    assertEquals(1, dao.markFinalized(receiptId))
                    OlmInboundHandoffResult.COMMITTED
                },
                finalizer = OlmCommittedReceiptFinalizer { receiptId, _ ->
                    dao.markFinalized(receiptId) == 1
                },
                authenticatedLocalUserId = { "recipient" },
                clock = { 10_000L },
            )

            val firstPass = processor.processPending(limit = 50)
            assertEquals(50, firstPass.pendingScanned)
            assertEquals(50, firstPass.failed)
            assertEquals(50, handoffCalls)

            // The untouched receipt must rotate ahead of the 50 failed attempts.
            val secondPass = processor.processPending(limit = 50)
            assertEquals(1, secondPass.pendingScanned)
            assertEquals(1, secondPass.committed)
            assertEquals(0, secondPass.failed)
            assertEquals(51, handoffCalls)
            assertEquals(
                OlmInboundReceiptStatus.FINALIZED.name,
                dao.getByReceiptId(
                    receiptId("alice", "sender-device", "later-key-receipt", "recipient-device"),
                )?.status,
            )
            assertEquals(50, dao.getPending("recipient", 100).size)
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
                status: String = OlmInboundReceiptStatus.PENDING.name,
            ) = OlmInboundReceiptEntity(
                receiptId = rowReceiptId,
                recipientUserId = "recipient",
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                messageId = messageId,
                recipientDeviceId = "recipient-device",
                envelope = encoded,
                envelopeSha256 = digest,
                status = status,
                receivedAtMillis = 100L,
                processedAtMillis = processedAtMillis,
            )

            val corruptedRows = listOf(
                row("bad-digest", digest = "0".repeat(64)),
                row("bad-identity", rowReceiptId = "f".repeat(64)),
                row("bad-pending-timestamp", processedAtMillis = 99L),
                row(
                    "bad-deferred-digest",
                    digest = "1".repeat(64),
                    status = OlmInboundReceiptStatus.WAITING_FOR_DEVICE.name,
                ),
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
            assertEquals(1, result.deferredScanned)
            assertEquals(4, result.rejected)
            assertEquals(0, result.failed)
            assertEquals(0, directoryReads)
            assertEquals(0, handoffCalls)
            assertEquals(0, dao.getPending("recipient", 10).size)
            assertEquals(0, dao.getWaitingForDevice("recipient", 10).size)
            corruptedRows.forEach { corrupt ->
                assertEquals(
                    OlmInboundReceiptStatus.REJECTED.name,
                    dao.getByReceiptId(corrupt.receiptId)?.status,
                )
                assertEquals(
                    "Quarantine must restore the REJECTED row's null processed timestamp invariant",
                    null,
                    dao.getByReceiptId(corrupt.receiptId)?.processedAtMillis,
                )
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun deferredReceiptRestoresForRetryAndSurvivesTransientHandoffFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "olm-pending-deferred-retry-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.olmInboundReceiptDao()
            val ledger = OlmInboundDeliveryLedger(dao, "recipient", "recipient-device")
            val trustedDevice = device("sender-device", "sender-identity")
            val encoded = envelope("sender-device", "recipient-device", 1)
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "deferred-retry", encoded, 100L),
            )
            val id = receiptId("alice", "sender-device", "deferred-retry", "recipient-device")
            assertEquals(1, dao.markWaitingForDevice(id))

            var handoffAttempts = 0
            var trustAvailable = false
            val processor = OlmPendingReceiptProcessor(
                receiptDao = dao,
                remoteDevicesForUser = { listOf(trustedDevice) },
                trustVerifier = OlmDeviceTrustVerifier { userId, deviceId, identityKey ->
                    trustAvailable &&
                        userId == "alice" &&
                        deviceId == trustedDevice.deviceId &&
                        identityKey == trustedDevice.ed25519IdentityKey
                },
                handoff = OlmInboundReceiptHandoff { receiptId, _, _, _, _ ->
                    handoffAttempts++
                    if (handoffAttempts == 1) error("Transient native handoff failure")
                    assertEquals(1, dao.markProcessed(receiptId, 200L))
                    assertEquals(1, dao.markFinalized(receiptId))
                    OlmInboundHandoffResult.COMMITTED
                },
                finalizer = OlmCommittedReceiptFinalizer { receiptId, _ ->
                    dao.markFinalized(receiptId) == 1
                },
                authenticatedLocalUserId = { "recipient" },
            )

            val stillUntrusted = processor.processPending(limit = 10)
            assertEquals(1, stillUntrusted.deferredScanned)
            assertEquals(1, stillUntrusted.waitingForTrust)
            assertEquals(0, handoffAttempts)
            assertEquals(
                OlmInboundReceiptStatus.WAITING_FOR_DEVICE.name,
                dao.getByReceiptId(id)?.status,
            )

            trustAvailable = true
            val transientFailure = processor.processPending(limit = 10)
            assertEquals(1, transientFailure.deferredScanned)
            assertEquals(1, transientFailure.failed)
            assertEquals(1, handoffAttempts)
            assertEquals(
                "A failed resumed receipt must be left pending for the next durable retry",
                OlmInboundReceiptStatus.PENDING.name,
                dao.getByReceiptId(id)?.status,
            )

            val recovered = processor.processPending(limit = 10)
            assertEquals(1, recovered.pendingScanned)
            assertEquals(1, recovered.committed)
            assertEquals(0, recovered.failed)
            assertEquals(2, handoffAttempts)
            assertEquals(
                OlmInboundReceiptStatus.FINALIZED.name,
                dao.getByReceiptId(id)?.status,
            )
            assertEquals(0, dao.getPending("recipient", 10).size)
            assertEquals(0, dao.getWaitingForDevice("recipient", 10).size)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun processedReceiptRetriesNativeFinalizationWithoutRepeatingHandoff() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "olm-pending-finalize-retry-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.olmInboundReceiptDao()
            val ledger = OlmInboundDeliveryLedger(dao, "recipient", "recipient-device")
            val encoded = envelope("sender-device", "recipient-device", 1)
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "committed-finalize-retry", encoded, 100L),
            )
            assertTrue(ledger.markProcessed("alice", "committed-finalize-retry", "sender-device"))
            val id = receiptId(
                "alice", "sender-device", "committed-finalize-retry", "recipient-device",
            )

            var finalizationAttempts = 0
            var handoffCalls = 0
            val processor = OlmPendingReceiptProcessor(
                receiptDao = dao,
                remoteDevicesForUser = { error("Committed receipts must not resolve sender devices") },
                trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
                handoff = OlmInboundReceiptHandoff { _, _, _, _, _ ->
                    handoffCalls++
                    error("Committed receipts must never repeat the decrypt/commit handoff")
                },
                finalizer = OlmCommittedReceiptFinalizer { receiptId, _ ->
                    finalizationAttempts++
                    if (finalizationAttempts == 1) false
                    else dao.markFinalized(receiptId) == 1
                },
                authenticatedLocalUserId = { "recipient" },
            )

            val first = processor.processPending(limit = 10)
            assertEquals(1, first.finalizationChecked)
            assertEquals(0, first.finalized)
            assertEquals(1, first.failed)
            assertEquals(0, first.pendingScanned)
            assertEquals(0, handoffCalls)
            assertEquals(
                OlmInboundReceiptStatus.PROCESSED.name,
                dao.getByReceiptId(id)?.status,
            )

            val second = processor.processPending(limit = 10)
            assertEquals(1, second.finalizationChecked)
            assertEquals(1, second.finalized)
            assertEquals(0, second.failed)
            assertEquals(0, handoffCalls)
            assertEquals(
                OlmInboundReceiptStatus.FINALIZED.name,
                dao.getByReceiptId(id)?.status,
            )

            val third = processor.processPending(limit = 10)
            assertEquals(0, third.finalizationChecked)
            assertEquals(2, finalizationAttempts)
            assertEquals(0, handoffCalls)
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
            .digest("recipient\u0000$sender\u0000$device\u0000$message\u0000$recipient".toByteArray())
            .joinToString("") { "%02x".format(it) }
}
