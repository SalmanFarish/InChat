package com.example.inchat.crypto

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OlmDeliveryAckProcessorTest {
    @Test
    fun matchingAcknowledgementRemovesRelayAckThenExactLocalRow() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "message-1", listOf(prepared()))
        val row = store.rows.values.single()
        val acknowledgement = row.toAcknowledgement()
        val transport = FakeAckTransport(listOf(acknowledgement))

        val result = OlmDeliveryAckProcessor(queue, transport, "alice", "sender-device", recordAcknowledgement = { _, _ -> }).processPending()

        assertEquals(1, result.removed)
        assertEquals(0, result.unmatched)
        assertTrue(store.rows.isEmpty())
        assertEquals(listOf(row.deliveryId), transport.removedDeliveryIds)
    }

    @Test
    fun remoteAckDeletionFailurePreservesLocalCiphertextRow() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "message-2", listOf(prepared()))
        val row = store.rows.values.single()
        val transport = FakeAckTransport(listOf(row.toAcknowledgement()), failRemoval = true)

        try {
            OlmDeliveryAckProcessor(queue, transport, "alice", "sender-device", recordAcknowledgement = { _, _ -> }).processPending()
            throw AssertionError("Expected remote acknowledgement deletion to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("temporary"))
        }

        assertEquals(row, store.rows[row.deliveryId])
        assertTrue(transport.removedDeliveryIds.isEmpty())
    }

    @Test
    fun failedAcknowledgementDoesNotBlockLaterValidAcknowledgement() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "first-message", listOf(prepared()))
        queue.enqueue("alice", "sender-device", "second-message", listOf(prepared()))
        val rows = store.rows.values.toList()
        assertEquals(2, rows.size)
        val first = rows[0]
        val second = rows[1]
        val transport = FakeAckTransport(
            acknowledgements = rows.map { it.toAcknowledgement() },
            failRemovalDeliveryIds = setOf(first.deliveryId),
        )

        val failure = runCatching {
            OlmDeliveryAckProcessor(
                queue = queue,
                transport = transport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                recordAcknowledgement = { _, _ -> },
            ).processPending()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("temporary"))
        // The failed first ACK remains retryable, but the later ACK was fully
        // reconciled and its corresponding ciphertext row was safely removed.
        assertEquals(first, store.rows[first.deliveryId])
        assertNull(store.rows[second.deliveryId])
        assertTrue(transport.hasPendingAcknowledgements())
        assertEquals(listOf(second.deliveryId), transport.removedDeliveryIds)
    }

    @Test
    fun independentAcknowledgementsAreReconciledConcurrently() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "parallel-ack-1", listOf(prepared()))
        queue.enqueue("alice", "sender-device", "parallel-ack-2", listOf(prepared()))
        val rows = store.rows.values.toList()
        assertEquals(2, rows.size)

        val bothDeletesStarted = CompletableDeferred<Unit>()
        val deleteCount = AtomicInteger()
        val receiptCount = AtomicInteger()
        val transport = FakeAckTransport(
            acknowledgements = rows.map { it.toAcknowledgement() },
            beforeRemoval = {
                if (deleteCount.incrementAndGet() == 2) {
                    bothDeletesStarted.complete(Unit)
                }
                // Sequential ACK reconciliation cannot release its first delete
                // until a second independent delete has started.
                withTimeout(2_000L) { bothDeletesStarted.await() }
            },
        )

        val result = withTimeout(5_000L) {
            OlmDeliveryAckProcessor(
                queue = queue,
                transport = transport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                recordAcknowledgement = { _, _ -> receiptCount.incrementAndGet() },
            ).processPending()
        }

        assertEquals(2, result.removed)
        assertEquals(0, result.unmatched)
        assertEquals(2, deleteCount.get())
        assertEquals(2, receiptCount.get())
        assertEquals(rows.map { it.deliveryId }.sorted(), transport.removedDeliveryIds.sorted())
        assertTrue(store.rows.isEmpty())
        assertFalse(transport.hasPendingAcknowledgements())
    }

    @Test
    fun acknowledgementDeletionThenProcessDeathRecoversByResendingSameDeliveryId() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue("alice", "sender-device", "ack-interruption", listOf(prepared()))
        val firstClaim = queue.claimNext("alice", "sender-device", 1_000L)!!
        val acknowledgement = firstClaim.toAcknowledgement()
        val transport = FakeAckTransport(
            acknowledgements = listOf(acknowledgement),
            failAfterRemovalOnce = true,
        )

        var interruptionObserved = false
        try {
            OlmDeliveryAckProcessor(queue, transport, "alice", "sender-device", recordAcknowledgement = { _, _ -> }).processPending()
        } catch (cancelled: CancellationException) {
            interruptionObserved = true
        }

        assertTrue(interruptionObserved)
        assertFalse(transport.hasPendingAcknowledgements())
        assertEquals(firstClaim, store.rows[firstClaim.deliveryId])
        assertEquals(listOf(firstClaim.deliveryId), transport.removedDeliveryIds)

        assertNull(queue.claimNext("alice", "sender-device", 1_099L))
        val recoveredClaim = queue.claimNext("alice", "sender-device", 1_100L)
        assertTrue(recoveredClaim != null)
        assertEquals(firstClaim.deliveryId, recoveredClaim?.deliveryId)
        assertEquals(firstClaim.messageId, recoveredClaim?.messageId)
        assertEquals(2, recoveredClaim?.attemptCount)

        // The receiver recognizes the resent message in its durable receipt ledger
        // and publishes the acknowledgement again under the same delivery ID.
        transport.addAcknowledgement(acknowledgement)
        val recoveredAck = OlmDeliveryAckProcessor(
            queue,
            transport,
            "alice",
            "sender-device",
        
            recordAcknowledgement = { _, _ -> }).processPending()

        assertEquals(1, recoveredAck.removed)
        assertEquals(0, recoveredAck.unmatched)
        assertTrue(store.rows.isEmpty())
        assertFalse(transport.hasPendingAcknowledgements())
        assertEquals(
            listOf(firstClaim.deliveryId, firstClaim.deliveryId),
            transport.removedDeliveryIds,
        )
    }

    @Test
    fun overlappingProcessorInstancesSerializeAckCommitAndDoNotDoubleRecord() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "concurrent-ack", listOf(prepared()))
        val row = store.rows.values.single()
        val transport = FakeAckTransport(listOf(row.toAcknowledgement()))
        val receiptWriteEntered = CompletableDeferred<Unit>()
        val allowReceiptWrite = CompletableDeferred<Unit>()
        var receiptWrites = 0

        val first = async {
            OlmDeliveryAckProcessor(
                queue, transport, "alice", "sender-device",
                recordAcknowledgement = { _, _ ->
                    receiptWrites++
                    receiptWriteEntered.complete(Unit)
                    allowReceiptWrite.await()
                },
            ).processPending()
        }

        receiptWriteEntered.await()
        val secondStarted = CompletableDeferred<Unit>()
        val second = async {
            secondStarted.complete(Unit)
            OlmDeliveryAckProcessor(
                queue, transport, "alice", "sender-device",
                recordAcknowledgement = { _, _ -> receiptWrites++ },
            ).processPending()
        }
        secondStarted.await()
        yield()

        // The second worker must wait before even reading the same relay ack.
        assertEquals(1, transport.readCalls)
        allowReceiptWrite.complete(Unit)

        val firstResult = first.await()
        val secondResult = second.await()
        assertEquals(1, firstResult.removed)
        assertEquals(0, secondResult.removed)
        assertEquals(0, secondResult.unmatched)
        assertEquals(1, receiptWrites)
        assertTrue(store.rows.isEmpty())
        assertEquals(listOf(row.deliveryId), transport.removedDeliveryIds)
    }

    @Test
    fun mismatchedAcknowledgementDoesNotDeleteRemoteAckOrLocalRow() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "message-3", listOf(prepared()))
        val row = store.rows.values.single()
        val acknowledgement = row.toAcknowledgement()
        val transport = FakeAckTransport(
            listOf(
                acknowledgement.copy(recipientUserId = "mallory"),
                acknowledgement.copy(senderUserId = "mallory"),
                acknowledgement.copy(senderDeviceId = "other-sender-device"),
            ),
        )

        val result = OlmDeliveryAckProcessor(queue, transport, "alice", "sender-device", recordAcknowledgement = { _, _ -> }).processPending()

        assertEquals(0, result.removed)
        assertEquals(3, result.unmatched)
        assertEquals(row, store.rows[row.deliveryId])
        assertTrue(transport.removedDeliveryIds.isEmpty())
    }

    @Test
    fun duplicateAcknowledgementsAreIdempotent() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "message-4", listOf(prepared()))
        val row = store.rows.values.single()
        val acknowledgement = row.toAcknowledgement()
        val transport = FakeAckTransport(listOf(acknowledgement, acknowledgement))

        val result = OlmDeliveryAckProcessor(queue, transport, "alice", "sender-device", recordAcknowledgement = { _, _ -> }).processPending()

        assertEquals(1, result.removed)
        assertEquals(1, result.unmatched)
        assertTrue(store.rows.isEmpty())
        assertEquals(listOf(row.deliveryId), transport.removedDeliveryIds)
    }

    @Test
    fun receiptPersistenceFailureKeepsLocalCiphertextForAcknowledgementRecovery() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "receipt-write-failure", listOf(prepared()))
        val row = store.rows.values.single()
        val transport = FakeAckTransport(listOf(row.toAcknowledgement()))

        var persistenceFailureObserved = false
        try {
            OlmDeliveryAckProcessor(
                queue = queue,
                transport = transport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                recordAcknowledgement = { _, _ ->
                    throw IllegalStateException("temporary Room receipt write failure")
                },
            ).processPending()
        } catch (expected: IllegalStateException) {
            persistenceFailureObserved = expected.message.orEmpty().contains("receipt write failure")
        }

        assertTrue(persistenceFailureObserved)
        assertFalse(transport.hasPendingAcknowledgements())
        assertEquals(listOf(row.deliveryId), transport.removedDeliveryIds)
        assertEquals(
            "The ciphertext row must remain until its durable ACK receipt is committed",
            row,
            store.rows[row.deliveryId],
        )
    }

    private fun QueuedOlmDeviceDelivery.toAcknowledgement() = OlmDeliveryAcknowledgement(
        deliveryId = deliveryId,
        recipientUserId = recipientUserId,
        recipientDeviceId = recipientDeviceId,
        messageId = messageId,
        senderUserId = requireNotNull(ownerUserId),
        senderDeviceId = requireNotNull(senderDeviceId),
    )

    private fun prepared(): PreparedOlmDelivery {
        val deviceId = "recipient-phone"
        val envelope = JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", "sender-device")
            .put("recipientDeviceId", deviceId)
            .put("sessionId", "session-$deviceId")
            .put("ciphertext", Base64.encodeToString(byteArrayOf(7, 8, 9), Base64.URL_SAFE or Base64.NO_WRAP))
            .toString()
        return PreparedOlmDelivery("bob", deviceId, "session-$deviceId", envelope)
    }

    private class InMemoryQueueStore : OlmDeliveryQueueStore {
        val rows = linkedMapOf<String, QueuedOlmDeviceDelivery>()

        override fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int {
            var inserted = 0
            deliveries.forEach { if (!rows.containsKey(it.deliveryId)) {
                rows[it.deliveryId] = it
                inserted++
            } }
            return inserted
        }

        override fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean =
            rows[acknowledgement.deliveryId]?.let { row ->
                row.messageId == acknowledgement.messageId &&
                    row.recipientUserId == acknowledgement.recipientUserId &&
                    row.recipientDeviceId == acknowledgement.recipientDeviceId &&
                    row.ownerUserId == acknowledgement.senderUserId &&
                    row.senderDeviceId == acknowledgement.senderDeviceId
            } ?: false

        override fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean {
            if (!hasMatchingDelivery(acknowledgement)) return false
            rows.remove(acknowledgement.deliveryId)
            return true
        }

        override fun claimNextDue(
            ownerUserId: String,
            senderDeviceId: String,
            nowMillis: Long,
            leaseDurationMillis: Long,
            maxAttempts: Int,
        ): QueuedOlmDeviceDelivery? {
            val candidate = rows.values
                .filter {
                    it.ownerUserId == ownerUserId && it.senderDeviceId == senderDeviceId &&
                        ((it.status == OlmDeliveryStatus.QUEUED && it.nextAttemptAtMillis <= nowMillis) ||
                        (it.status == OlmDeliveryStatus.IN_FLIGHT && it.leaseUntilMillis <= nowMillis))
                }
                .minByOrNull { it.nextAttemptAtMillis }
                ?: return null
            val claimed = candidate.copy(
                status = OlmDeliveryStatus.IN_FLIGHT,
                attemptCount = candidate.attemptCount + 1,
                leaseUntilMillis = nowMillis + leaseDurationMillis,
            )
            rows[candidate.deliveryId] = claimed
            return claimed
        }


        override fun retryFailed(
            deliveryId: String,
            messageId: String,
            ownerUserId: String,
            senderDeviceId: String,
            nowMillis: Long,
        ): Boolean {
            val row = rows[deliveryId] ?: return false
            if (row.messageId != messageId || row.ownerUserId != ownerUserId ||
                row.senderDeviceId != senderDeviceId || row.status != OlmDeliveryStatus.FAILED
            ) return false
            rows[deliveryId] = row.copy(
                status = OlmDeliveryStatus.QUEUED,
                attemptCount = 0,
                nextAttemptAtMillis = nowMillis,
                leaseUntilMillis = 0L,
                lastError = null,
            )
            return true
        }

        override fun updateInFlight(
            deliveryId: String,
            messageId: String,
            ownerUserId: String,
            senderDeviceId: String,
            expectedAttemptCount: Int,
            expectedLeaseUntilMillis: Long,
            nowMillis: Long,
            update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
        ): Boolean {
            val row = rows[deliveryId] ?: return false
            if (row.messageId != messageId || row.ownerUserId != ownerUserId ||
                row.senderDeviceId != senderDeviceId || row.status != OlmDeliveryStatus.IN_FLIGHT ||
                row.attemptCount != expectedAttemptCount ||
                row.leaseUntilMillis != expectedLeaseUntilMillis ||
                row.leaseUntilMillis <= nowMillis
            ) return false
            rows[deliveryId] = update(row)
            return true
        }
    }

    private class FakeAckTransport(
        acknowledgements: List<OlmDeliveryAcknowledgement>,
        private val failRemoval: Boolean = false,
        private val failAfterRemovalOnce: Boolean = false,
        private val failRemovalDeliveryIds: Set<String> = emptySet(),
        private val beforeRemoval: suspend (String) -> Unit = {},
    ) : OlmDeliveryAckTransport {
        private val acknowledgements = acknowledgements.toMutableList()
        val removedDeliveryIds = mutableListOf<String>()
        var readCalls = 0
            private set
        private var interruptionSent = false

        fun addAcknowledgement(acknowledgement: OlmDeliveryAcknowledgement) {
            acknowledgements += acknowledgement
        }

        fun hasPendingAcknowledgements(): Boolean = acknowledgements.isNotEmpty()

        override suspend fun readMyDeliveryAcknowledgements(
            senderDeviceId: String,
        ): List<OlmDeliveryAcknowledgement> {
            readCalls++
            return acknowledgements.toList()
        }

        override suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String) {
            beforeRemoval(deliveryId)
            if (failRemoval || deliveryId in failRemovalDeliveryIds) {
                throw IllegalStateException("temporary ack deletion failure")
            }
            val removed = acknowledgements.removeAll { it.deliveryId == deliveryId }
            if (removed) removedDeliveryIds += deliveryId
            if (removed && failAfterRemovalOnce && !interruptionSent) {
                interruptionSent = true
                throw CancellationException("process stopped after relay acknowledgement deletion")
            }
        }
    }
}
