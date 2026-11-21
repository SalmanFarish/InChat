package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OlmInboundDeliveryLedgerTest {
    @Test
    fun pendingAndProcessedReceiptsSurviveDatabaseReopenAndDeduplicate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-receipts-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val envelope = envelope("receiver-device", "sender-device", 7)
        try {
            val ledger = OlmInboundDeliveryLedger(first.olmInboundReceiptDao(), "recipient", "receiver-device")
            assertEquals(OlmInboundRegistration.NEW, ledger.register("alice", "message-123", envelope, 1_000))
            assertEquals(OlmInboundRegistration.PENDING_RETRY, ledger.register("alice", "message-123", envelope, 2_000))
            assertTrue(ledger.markProcessed("alice", "message-123", "sender-device"))
        } finally { first.close() }

        val second = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(second.olmInboundReceiptDao(), "recipient", "receiver-device")
            assertEquals(
                OlmInboundRegistration.DUPLICATE_PROCESSED,
                ledger.register("alice", "message-123", envelope, 3_000),
            )
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun finalizedAndRejectedReceiptsRemainTerminalAcrossDuplicateDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-terminal-receipts-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = db.olmInboundReceiptDao()
            val ledger = OlmInboundDeliveryLedger(dao, "recipient", "receiver-device")
            val finalizedEnvelope = envelope("receiver-device", "sender-device", 51)
            val rejectedEnvelope = envelope("receiver-device", "sender-device", 52)

            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "terminal-finalized", finalizedEnvelope, 100L),
            )
            assertTrue(ledger.markProcessed("alice", "terminal-finalized", "sender-device"))
            val finalizedId = olmInboundReceiptId(
                "recipient",
                "alice", "sender-device", "terminal-finalized", "receiver-device",
            )
            assertEquals(1, dao.markFinalized(finalizedId))

            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "terminal-rejected", rejectedEnvelope, 200L),
            )
            val rejectedId = olmInboundReceiptId(
                "recipient",
                "alice", "sender-device", "terminal-rejected", "receiver-device",
            )
            assertEquals(1, dao.markRejected(rejectedId))

            val finalizedBefore = requireNotNull(dao.getByReceiptId(finalizedId))
            val rejectedBefore = requireNotNull(dao.getByReceiptId(rejectedId))
            assertEquals(
                OlmInboundRegistration.DUPLICATE_PROCESSED,
                ledger.register("alice", "terminal-finalized", finalizedEnvelope, 999L),
            )
            assertEquals(
                OlmInboundRegistration.DUPLICATE_REJECTED,
                ledger.register("alice", "terminal-rejected", rejectedEnvelope, 999L),
            )
            assertEquals(
                OlmInboundRegistration.CONFLICT,
                ledger.register(
                    "alice",
                    "terminal-finalized",
                    envelope("receiver-device", "sender-device", 53),
                    1_000L,
                ),
            )

            assertEquals(finalizedBefore, dao.getByReceiptId(finalizedId))
            assertEquals(rejectedBefore, dao.getByReceiptId(rejectedId))
            assertEquals(0, dao.getPending("recipient", 10).size)
            assertEquals(0, dao.getWaitingForDevice("recipient", 10).size)
            assertEquals(0, dao.getAwaitingFinalization("recipient", 10).size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun duplicateRegistrationRejectsCorruptedStoredReceiptWithoutReplacingIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-corrupt-existing-receipt-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val encoded = envelope("receiver-device", "sender-device", 41)
            val receiptId = olmInboundReceiptId(
                "recipient",
                "alice", "sender-device", "corrupt-existing", "receiver-device",
            )
            val corrupted = OlmInboundReceiptEntity(
                receiptId = receiptId,
                recipientUserId = "recipient",
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                messageId = "corrupt-existing",
                recipientDeviceId = "receiver-device",
                envelope = encoded,
                envelopeSha256 = "0".repeat(64),
                status = OlmInboundReceiptStatus.PENDING.name,
                receivedAtMillis = 100L,
                processedAtMillis = null,
            )
            assertTrue(db.olmInboundReceiptDao().insertIfAbsent(corrupted) != -1L)
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            assertEquals(
                OlmInboundRegistration.CONFLICT,
                ledger.register("alice", "corrupt-existing", encoded, 101L),
            )
            assertEquals(corrupted, db.olmInboundReceiptDao().getByReceiptId(receiptId))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun sharedInstallationDeviceKeepsReceiptQueuesScopedToRecipientAccount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-account-scope-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = db.olmInboundReceiptDao()
            val accountA = OlmInboundDeliveryLedger(dao, "account-a", "receiver-device")
            val accountB = OlmInboundDeliveryLedger(dao, "account-b", "receiver-device")
            val sharedEnvelope = envelope("receiver-device", "sender-device", 61)

            assertEquals(
                OlmInboundRegistration.NEW,
                accountA.register("alice", "same-message-id", sharedEnvelope, 100L),
            )
            assertEquals(
                OlmInboundRegistration.NEW,
                accountB.register("alice", "same-message-id", sharedEnvelope, 101L),
            )
            assertEquals(listOf("account-a"), dao.getPending("account-a", 10).map { it.recipientUserId })
            assertEquals(listOf("account-b"), dao.getPending("account-b", 10).map { it.recipientUserId })

            assertTrue(accountA.markProcessed("alice", "same-message-id", "sender-device"))
            assertTrue(dao.getPending("account-a", 10).isEmpty())
            assertEquals(1, dao.getPending("account-b", 10).size)
            assertEquals(1, dao.getAwaitingFinalization("account-a", 10).size)
            assertTrue(dao.getAwaitingFinalization("account-b", 10).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun pendingReceiptQueryReturnsOldestUnprocessedReceiptsOnlyAndHonorsLimit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-pending-query-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val firstEnvelope = envelope("receiver-device", "sender-device", 31)
            val secondEnvelope = envelope("receiver-device", "sender-device", 32)
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "first-pending", firstEnvelope, 100L),
            )
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "second-pending", secondEnvelope, 200L),
            )
            assertTrue(ledger.markProcessed("alice", "first-pending", "sender-device"))

            val pending = db.olmInboundReceiptDao().getPending("recipient", 10)
            assertEquals(listOf("second-pending"), pending.map { it.messageId })
            assertTrue(pending.all { it.status == OlmInboundReceiptStatus.PENDING.name })
            assertTrue(db.olmInboundReceiptDao().getPending("recipient", 0).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun concurrentDuplicateRegistrationProducesOneNewAndOnePendingRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-concurrent-duplicate-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val messageId = "concurrent-message"
        val encoded = envelope("receiver-device", "sender-device", 11)
        try {
            val firstLedger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val secondLedger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = listOf(firstLedger, secondLedger).mapIndexed { index, ledger ->
                    pool.submit(Callable {
                        start.await()
                        ledger.register("alice", messageId, encoded, 1_000L + index)
                    })
                }
                start.countDown()
                val results = futures.map { it.get(10, TimeUnit.SECONDS) }

                assertEquals(1, results.count { it == OlmInboundRegistration.NEW })
                assertEquals(1, results.count { it == OlmInboundRegistration.PENDING_RETRY })

                val receiptId = MessageDigest.getInstance("SHA-256")
                    .digest(
                        listOf("recipient", "alice", "sender-device", messageId, "receiver-device")
                            .joinToString(0.toChar().toString())
                            .toByteArray(),
                    )
                    .joinToString("") { "%02x".format(it) }
                val stored = db.olmInboundReceiptDao().getByReceiptId(receiptId)
                assertEquals(encoded, stored?.envelope)
                assertEquals(OlmInboundReceiptStatus.PENDING.name, stored?.status)
            } finally {
                pool.shutdownNow()
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun concurrentConflictingRegistrationKeepsTheFirstEnvelopeImmutable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-concurrent-conflict-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val messageId = "concurrent-conflict"
        val firstEnvelope = envelope("receiver-device", "sender-device", 21)
        val secondEnvelope = envelope("receiver-device", "sender-device", 22)
        try {
            val firstLedger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val secondLedger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = listOf(
                    Callable {
                        start.await()
                        firstLedger.register("alice", messageId, firstEnvelope, 2_000L)
                    },
                    Callable {
                        start.await()
                        secondLedger.register("alice", messageId, secondEnvelope, 2_001L)
                    },
                ).map { pool.submit(it) }
                start.countDown()
                val results = futures.map { it.get(10, TimeUnit.SECONDS) }

                assertEquals(1, results.count { it == OlmInboundRegistration.NEW })
                assertEquals(1, results.count { it == OlmInboundRegistration.CONFLICT })

                val receiptId = MessageDigest.getInstance("SHA-256")
                    .digest(
                        listOf("recipient", "alice", "sender-device", messageId, "receiver-device")
                            .joinToString(0.toChar().toString())
                            .toByteArray(),
                    )
                    .joinToString("") { "%02x".format(it) }
                val stored = db.olmInboundReceiptDao().getByReceiptId(receiptId)
                assertTrue(stored?.envelope == firstEnvelope || stored?.envelope == secondEnvelope)
                val conflictingEnvelope =
                    if (stored?.envelope == firstEnvelope) secondEnvelope else firstEnvelope
                assertEquals(
                    OlmInboundRegistration.CONFLICT,
                    firstLedger.register("alice", messageId, conflictingEnvelope, 2_002L),
                )
                assertEquals(OlmInboundReceiptStatus.PENDING.name, stored?.status)
            } finally {
                pool.shutdownNow()
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun reusedMessageIdWithDifferentCiphertextIsRejectedAsConflict() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-conflict-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "message-456", envelope("receiver-device", "sender-device", 1), 10),
            )
            assertEquals(
                OlmInboundRegistration.CONFLICT,
                ledger.register("alice", "message-456", envelope("receiver-device", "sender-device", 2), 11),
            )
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun envelopeForAnotherDeviceIsRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-wrong-device-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            try {
                ledger.register("alice", "message-789", envelope("other-device", "sender-device", 1), 10)
                throw AssertionError("Expected wrong-recipient envelope to be rejected")
            } catch (_: IllegalArgumentException) { /* Expected. */ }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun envelope(recipient: String, sender: String, ciphertextByte: Int): String =
        JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", sender)
            .put("recipientDeviceId", recipient)
            .put("sessionId", "session-$sender")
            .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, ciphertextByte.toByte()), Base64.URL_SAFE or Base64.NO_WRAP))
            .toString()
}
