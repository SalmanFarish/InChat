package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
    fun overlappingInboxReceiversDoNotRaceTheSameRemoteDelivery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-overlapping-receive-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val item = FirebaseOlmInboxItem(
                "a".repeat(64), "alice", "sender-device", "overlap-message", envelope(1),
            )
            val transport = FakeTransport(mutableListOf(item))
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val callbackEntered = CompletableDeferred<Unit>()
            val releaseCallback = CompletableDeferred<Unit>()
            val first = FirebaseOlmInboxReceiver(
                transport, ledger, "receiver-device",
                onAcknowledgementStored = {
                    callbackEntered.complete(Unit)
                    releaseCallback.await()
                },
            )
            val second = FirebaseOlmInboxReceiver(
                transport, ledger, "receiver-device",
            )

            val firstRun = async { first.receivePending() }
            callbackEntered.await()
            val secondStarted = CompletableDeferred<Unit>()
            val secondRun = async {
                secondStarted.complete(Unit)
                second.receivePending()
            }
            secondStarted.await()
            yield()

            // The second worker cannot read the still-uncommitted relay item.
            assertEquals(1, transport.readCalls)
            assertEquals(listOf(item), transport.items)

            releaseCallback.complete(Unit)
            val firstResult = firstRun.await()
            val secondResult = secondRun.await()

            assertEquals(1, firstResult.stored)
            assertEquals(0, secondResult.stored)
            assertEquals(2, transport.readCalls)
            assertEquals(listOf(item.deliveryId), transport.acknowledgementAttempts)
            assertEquals(listOf(item.deliveryId), transport.acknowledgedDeliveryIds)
            assertEquals(listOf(item.deliveryId), transport.removedDeliveryIds)
            assertTrue(transport.items.isEmpty())
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
                failRemovalForDeliveryId = first.deliveryId,
            )
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "recipient", "receiver-device")
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device", clock = { 500L })

            assertTrue(runCatching { receiver.receivePending() }.isFailure)
            assertEquals("Only the delivery with the injected removal failure should remain", listOf(first), transport.items)
            assertEquals(
                listOf(first.deliveryId, second.deliveryId).sorted(),
                transport.acknowledgedDeliveryIds.sorted(),
            )
            assertEquals(listOf(second.deliveryId), transport.removedDeliveryIds)

            val retry = receiver.receivePending()
            assertEquals(0, retry.stored)
            assertEquals(1, retry.alreadyPending)
            assertEquals(listOf(first.deliveryId, second.deliveryId).sorted(), transport.acknowledgedDeliveryIds.sorted())
            assertEquals(listOf(second.deliveryId, first.deliveryId), transport.removedDeliveryIds)
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

    @Test
    fun independentInboxItemsAreAcknowledgedConcurrentlyAfterDurableStaging() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-concurrent-acknowledgements-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val first = FirebaseOlmInboxItem(
                "3".repeat(64), "alice", "sender-device", "message-concurrent-1", envelope(1),
            )
            val second = FirebaseOlmInboxItem(
                "4".repeat(64), "alice", "sender-device", "message-concurrent-2", envelope(2),
            )
            val ledger = OlmInboundDeliveryLedger(
                db.olmInboundReceiptDao(),
                "recipient",
                "receiver-device",
            )
            val bothAcknowledgementsStarted = CompletableDeferred<Unit>()
            val acknowledgementCount = AtomicInteger()
            val durableBeforeAcknowledgementCount = AtomicInteger()
            val transport = FakeTransport(
                items = mutableListOf(first, second),
                beforeAcknowledgement = { item ->
                    // An ACK must never begin until this exact ciphertext has a
                    // durable Room receipt. PENDING_RETRY proves registration
                    // completed before the network handoff started.
                    assertEquals(
                        OlmInboundRegistration.PENDING_RETRY,
                        ledger.register(
                            senderUserId = item.senderUid,
                            messageId = item.messageId,
                            encodedEnvelope = item.envelope,
                            receivedAtMillis = 600L,
                        ),
                    )
                    durableBeforeAcknowledgementCount.incrementAndGet()
                    if (acknowledgementCount.incrementAndGet() == 2) {
                        bothAcknowledgementsStarted.complete(Unit)
                    }
                    // A sequential receiver would time out here because its first
                    // network ACK could not finish until a second ACK also started.
                    withTimeout(2_000L) { bothAcknowledgementsStarted.await() }
                },
            )
            val receiver = FirebaseOlmInboxReceiver(
                transport = transport,
                ledger = ledger,
                deviceId = "receiver-device",
                clock = { 600L },
            )

            val result = withTimeout(5_000L) { receiver.receivePending() }

            assertEquals(2, result.stored)
            assertEquals(0, result.conflicts)
            assertEquals(0, result.alreadyPending)
            assertEquals(2, acknowledgementCount.get())
            assertEquals(2, durableBeforeAcknowledgementCount.get())
            assertEquals(
                listOf(first.deliveryId, second.deliveryId).sorted(),
                transport.acknowledgedDeliveryIds.sorted(),
            )
            assertEquals(
                listOf(first.deliveryId, second.deliveryId).sorted(),
                transport.removedDeliveryIds.sorted(),
            )
            assertTrue(transport.items.isEmpty())
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", first.messageId, first.envelope, 601L),
            )
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", second.messageId, second.envelope, 602L),
            )
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun largeInboxSnapshotUsesFixedWorkerPoolAndStagesEveryItem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-bounded-worker-pool-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val items = (0 until 24).map { index ->
                FirebaseOlmInboxItem(
                    deliveryId = index.toString(16).padStart(64, '0'),
                    senderUid = "alice",
                    senderDeviceId = "sender-device",
                    messageId = "bounded-inbox-$index",
                    envelope = envelope(index),
                )
            }
            val transport = FakeTransport(items.toMutableList())
            val ledger = OlmInboundDeliveryLedger(
                db.olmInboundReceiptDao(),
                "recipient",
                "receiver-device",
            )
            val activeCallbacks = AtomicInteger()
            val peakCallbacks = AtomicInteger()
            val completedCallbacks = AtomicInteger()
            val allWorkersAtHandoff = CompletableDeferred<Unit>()
            val receiver = FirebaseOlmInboxReceiver(
                transport = transport,
                ledger = ledger,
                deviceId = "receiver-device",
                clock = { 700L },
                onAcknowledgementStored = {
                    val active = activeCallbacks.incrementAndGet()
                    peakCallbacks.updateAndGet { previous -> maxOf(previous, active) }
                    try {
                        // Hold the first eight callbacks until every worker has
                        // reached the handoff point; this proves the concurrency
                        // cap without relying on scheduler timing or yield().
                        if (active == 8) allWorkersAtHandoff.complete(Unit)
                        withTimeout(5_000L) { allWorkersAtHandoff.await() }
                        completedCallbacks.incrementAndGet()
                    } finally {
                        activeCallbacks.decrementAndGet()
                    }
                },
            )

            val result = withTimeout(20_000L) { receiver.receivePending() }

            assertEquals(24, result.stored)
            assertEquals(0, result.conflicts)
            assertEquals(0, result.alreadyPending)
            assertEquals(8, peakCallbacks.get())
            assertEquals(24, completedCallbacks.get())
            assertEquals(24, transport.acknowledgementAttempts.size)
            assertEquals(24, transport.acknowledgedDeliveryIds.size)
            assertEquals(24, transport.removedDeliveryIds.size)
            assertTrue(transport.items.isEmpty())
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
        private var failRemovalForDeliveryId: String? = null,
        private val beforeAcknowledgement: suspend (FirebaseOlmInboxItem) -> Unit = {},
    ) : OlmInboxTransport {
        val acknowledgementAttempts = mutableListOf<String>()
        var readCalls = 0
            private set
        val acknowledgedDeliveryIds = mutableListOf<String>()
        val removedDeliveryIds = mutableListOf<String>()

        override suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> {
            readCalls++
            return items.toList()
        }

        override suspend fun acknowledgeStoredDelivery(deviceId: String, item: FirebaseOlmInboxItem) {
            acknowledgementAttempts += item.deliveryId
            beforeAcknowledgement(item)
            if (failFirstAcknowledgement) {
                failFirstAcknowledgement = false
                throw IllegalStateException("temporary acknowledgement write failure")
            }
            if (item.deliveryId !in acknowledgedDeliveryIds) {
                acknowledgedDeliveryIds += item.deliveryId
            }
        }

        override suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) {
            if (deliveryId == failRemovalForDeliveryId) {
                failRemovalForDeliveryId = null
                throw IllegalStateException("temporary acknowledgement failure for delivery $deliveryId")
            }
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
