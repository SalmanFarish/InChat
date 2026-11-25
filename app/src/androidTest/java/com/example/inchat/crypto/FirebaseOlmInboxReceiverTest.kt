package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseOlmInboxReceiverTest {
    @Test
    fun storesCiphertextBeforeRemovingRemoteDelivery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-receiver-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val encoded = envelope(1)
            val item = FirebaseOlmInboxItem("a".repeat(64), "alice", "sender-device", "message-1", encoded)
            val transport = FakeTransport(mutableListOf(item))
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device", clock = { 100L })

            val result = receiver.receivePending()

            assertEquals(1, result.stored)
            assertEquals(0, result.conflicts)
            assertEquals(listOf(item.deliveryId), transport.acknowledgedDeliveryIds)
            assertTrue(transport.items.isEmpty())
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", "message-1", encoded, 101L),
            )
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun processedDuplicateIsAcknowledgedAndRemovedWithoutReprocessing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-processed-duplicate-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val encoded = envelope(1)
            val item = FirebaseOlmInboxItem(
                "f".repeat(64), "alice", "sender-device", "already-processed-message", encoded,
            )
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", item.messageId, encoded, 300L),
            )
            assertTrue(ledger.markProcessed("alice", item.messageId, item.senderDeviceId))

            val transport = FakeTransport(mutableListOf(item))
            val receiver = FirebaseOlmInboxReceiver(
                transport,
                ledger,
                "receiver-device",
                clock = { 301L },
            )

            val result = receiver.receivePending()

            assertEquals(0, result.stored)
            assertEquals(0, result.alreadyPending)
            assertEquals(1, result.alreadyProcessed)
            assertEquals(0, result.conflicts)
            assertEquals(listOf(item.deliveryId), transport.acknowledgementAttempts)
            assertEquals(listOf(item.deliveryId), transport.acknowledgedDeliveryIds)
            assertEquals(listOf(item.deliveryId), transport.removedDeliveryIds)
            assertTrue(transport.items.isEmpty())
            assertEquals(
                OlmInboundRegistration.DUPLICATE_PROCESSED,
                ledger.register("alice", item.messageId, encoded, 302L),
            )
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun rejectedDuplicateIsAcknowledgedAndRemovedWithoutReenteringTheProcessingQueue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-rejected-duplicate-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val messageId = "rejected-duplicate-message"
            val encoded = envelope(1)
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", messageId, encoded, 200L),
            )
            val receiptId = java.security.MessageDigest.getInstance("SHA-256")
                .digest("recipient\u0000alice\u0000sender-device\u0000$messageId\u0000receiver-device".toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(1, db.olmInboundReceiptDao().markRejected(receiptId))

            val item = FirebaseOlmInboxItem(
                "9".repeat(64), "alice", "sender-device", messageId, encoded,
            )
            val transport = FakeTransport(mutableListOf(item))
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device")

            val result = receiver.receivePending()

            assertEquals(1, result.rejected)
            assertEquals(0, result.conflicts)
            assertEquals(listOf(item.deliveryId), transport.acknowledgedDeliveryIds)
            assertEquals(listOf(item.deliveryId), transport.removedDeliveryIds)
            assertTrue(transport.items.isEmpty())
            assertEquals(
                OlmInboundRegistration.DUPLICATE_REJECTED,
                ledger.register("alice", messageId, encoded, 201L),
            )
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun failedAcknowledgementWriteKeepsInboxCopyUntilRetrySucceeds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-ack-write-retry-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val item = FirebaseOlmInboxItem(
                "e".repeat(64), "alice", "sender-device", "message-ack-write", envelope(1),
            )
            val transport = FakeTransport(
                items = mutableListOf(item),
                failFirstAcknowledgement = true,
            )
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val receiver = FirebaseOlmInboxReceiver(
                transport,
                ledger,
                "receiver-device",
                clock = { 250L },
            )

            try {
                receiver.receivePending()
                throw AssertionError("Expected the first acknowledgement write to fail")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("acknowledgement write"))
            }

            assertEquals(1, transport.items.size)
            assertEquals(listOf(item.deliveryId), transport.acknowledgementAttempts)
            assertTrue(transport.acknowledgedDeliveryIds.isEmpty())
            assertTrue(transport.removedDeliveryIds.isEmpty())
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", item.messageId, item.envelope, 251L),
            )

            val retry = receiver.receivePending()

            assertEquals(1, retry.alreadyPending)
            assertEquals(0, retry.stored)
            assertEquals(listOf(item.deliveryId, item.deliveryId), transport.acknowledgementAttempts)
            assertEquals(listOf(item.deliveryId), transport.acknowledgedDeliveryIds)
            assertEquals(listOf(item.deliveryId), transport.removedDeliveryIds)
            assertTrue(transport.items.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun committedRemoteRemovalWithLostCompletionIsSafeToRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-remove-commit-recovery-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val item = FirebaseOlmInboxItem(
                "7".repeat(64), "alice", "sender-device", "message-remove-commit", envelope(1),
            )
            val transport = FakeTransport(
                items = mutableListOf(item),
                failFirstRemovalAfterCommit = true,
            )
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device", clock = { 400L })

            try {
                receiver.receivePending()
                throw AssertionError("Expected lost remote-removal completion")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("after commit"))
            }

            assertTrue("The remote item was removed despite the lost completion", transport.items.isEmpty())
            assertEquals(listOf(item.deliveryId), transport.acknowledgedDeliveryIds)
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", item.messageId, item.envelope, 401L),
            )

            val retry = receiver.receivePending()
            assertEquals(0, retry.stored)
            assertEquals(0, retry.alreadyPending)
            assertTrue(transport.items.isEmpty())
            assertEquals(1, transport.acknowledgedDeliveryIds.size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun laterInboxItemsRemainRecoverableWhenEarlierRemovalFails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-batch-removal-recovery-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val first = FirebaseOlmInboxItem(
                "1".repeat(64), "alice", "sender-device", "message-batch-1", envelope(1),
            )
            val second = FirebaseOlmInboxItem(
                "2".repeat(64), "alice", "sender-device", "message-batch-2", envelope(2),
            )
            val transport = FakeTransport(
                items = mutableListOf(first, second),
                failFirstRemoval = true,
            )
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device", clock = { 500L })

            assertTrue(runCatching { receiver.receivePending() }.isFailure)
            assertEquals("Only the failed first delivery should remain", listOf(first), transport.items)
            assertEquals(listOf(first.deliveryId, second.deliveryId), transport.acknowledgedDeliveryIds)

            val retry = receiver.receivePending()
            assertEquals(0, retry.stored)
            assertEquals(1, retry.alreadyPending)
            assertEquals(listOf(first.deliveryId, second.deliveryId), transport.acknowledgedDeliveryIds)
            assertTrue(transport.items.isEmpty())
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", first.messageId, first.envelope, 501L),
            )
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", second.messageId, second.envelope, 502L),
            )
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun conflictingCiphertextIsNotAcknowledged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-conflict-receiver-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            ledger.register("alice", "message-2", envelope(1), 100L)
            val item = FirebaseOlmInboxItem("b".repeat(64), "alice", "sender-device", "message-2", envelope(2))
            val transport = FakeTransport(mutableListOf(item))
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device", clock = { 101L })

            val result = receiver.receivePending()

            assertEquals(1, result.conflicts)
            assertTrue(transport.acknowledgedDeliveryIds.isEmpty())
            assertEquals(1, transport.items.size)
            assertTrue(transport.acknowledgedDeliveryIds.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }


    @Test
    fun senderDeviceMetadataMismatchIsNotAcknowledged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-sender-mismatch-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val item = FirebaseOlmInboxItem(
                "c".repeat(64), "alice", "different-device", "message-3", envelope(1),
            )
            val transport = FakeTransport(mutableListOf(item))
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device")

            try {
                receiver.receivePending()
                throw AssertionError("Expected sender metadata mismatch to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("sender device"))
            }

            assertEquals(1, transport.items.size)
            assertTrue(transport.acknowledgedDeliveryIds.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun retriesRemoteAcknowledgementAfterLocalReceiptWasStored() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-ack-retry-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val item = FirebaseOlmInboxItem("d".repeat(64), "alice", "sender-device", "message-4", envelope(1))
            val transport = FakeTransport(mutableListOf(item), failFirstRemoval = true)
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device", clock = { 200L })

            try {
                receiver.receivePending()
                throw AssertionError("Expected first remote acknowledgement to fail")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("temporary"))
            }

            assertEquals(1, transport.items.size)
            assertEquals(listOf(item.deliveryId), transport.acknowledgedDeliveryIds)
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", "message-4", envelope(1), 201L),
            )

            val retry = receiver.receivePending()

            assertEquals(1, retry.alreadyPending)
            assertEquals(listOf(item.deliveryId), transport.acknowledgedDeliveryIds)
            assertTrue(transport.items.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun acknowledgementWakeRunsAfterDurableAckAndBeforeInboxRemoval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-ack-wakeup-order-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val item = FirebaseOlmInboxItem(
                "8".repeat(64), "alice", "sender-device", "message-ack-wakeup", envelope(1),
            )
            val transport = FakeTransport(mutableListOf(item))
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            var wakeCount = 0
            val receiver = FirebaseOlmInboxReceiver(
                transport = transport,
                ledger = ledger,
                deviceId = "receiver-device",
                onAcknowledgementStored = { acknowledgedItem ->
                    wakeCount++
                    assertEquals(item.deliveryId, acknowledgedItem.deliveryId)
                    assertTrue(transport.acknowledgedDeliveryIds.contains(item.deliveryId))
                    assertTrue(transport.items.contains(item))
                    assertTrue(!transport.removedDeliveryIds.contains(item.deliveryId))
                },
            )

            val result = receiver.receivePending()

            assertEquals(1, result.stored)
            assertEquals(1, wakeCount)
            assertTrue(transport.items.isEmpty())
            assertEquals(listOf(item.deliveryId), transport.removedDeliveryIds)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun failedAcknowledgementWakeKeepsRelayCopyForWorkManagerRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-ack-wakeup-retry-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val item = FirebaseOlmInboxItem(
                "6".repeat(64), "alice", "sender-device", "message-ack-wakeup-retry", envelope(1),
            )
            val transport = FakeTransport(mutableListOf(item))
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            var wakeAttempts = 0
            val receiver = FirebaseOlmInboxReceiver(
                transport = transport,
                ledger = ledger,
                deviceId = "receiver-device",
                onAcknowledgementStored = {
                    wakeAttempts++
                    if (wakeAttempts == 1) throw IllegalStateException("temporary acknowledgement push failure")
                },
            )

            assertTrue(runCatching { receiver.receivePending() }.isFailure)
            assertEquals(1, transport.acknowledgementAttempts.size)
            assertEquals(1, wakeAttempts)
            assertTrue(transport.items.contains(item))
            assertTrue(transport.removedDeliveryIds.isEmpty())

            val retry = receiver.receivePending()
            assertEquals(1, retry.alreadyPending)
            assertEquals(2, wakeAttempts)
            assertTrue(transport.items.isEmpty())
            assertEquals(listOf(item.deliveryId), transport.removedDeliveryIds)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private class FakeTransport(
        val items: MutableList<FirebaseOlmInboxItem>,
        private var failFirstRemoval: Boolean = false,
        private var failFirstAcknowledgement: Boolean = false,
        private var failFirstRemovalAfterCommit: Boolean = false,
    ) : OlmInboxTransport {
        val acknowledgementAttempts = mutableListOf<String>()
        val acknowledgedDeliveryIds = mutableListOf<String>()
        val removedDeliveryIds = mutableListOf<String>()

        override suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> = items.toList()

        override suspend fun acknowledgeStoredDelivery(deviceId: String, item: FirebaseOlmInboxItem) {
            acknowledgementAttempts += item.deliveryId
            if (failFirstAcknowledgement) {
                failFirstAcknowledgement = false
                throw IllegalStateException("temporary acknowledgement write failure")
            }
            if (item.deliveryId !in acknowledgedDeliveryIds) {
                acknowledgedDeliveryIds += item.deliveryId
            }
        }

        override suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) {
            if (failFirstRemoval) {
                failFirstRemoval = false
                throw IllegalStateException("temporary acknowledgement failure")
            }
            removedDeliveryIds += deliveryId
            items.removeAll { it.deliveryId == deliveryId }
            if (failFirstRemovalAfterCommit) {
                failFirstRemovalAfterCommit = false
                throw IllegalStateException("temporary remote removal failure after commit")
            }
        }
    }

    private fun envelope(byte: Int): String = JSONObject()
        .put("encryptionVersion", 1)
        .put("algorithm", "olm")
        .put("senderDeviceId", "sender-device")
        .put("recipientDeviceId", "receiver-device")
        .put("sessionId", "session-sender")
        .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, byte.toByte()), Base64.URL_SAFE or Base64.NO_WRAP))
        .toString()
}
