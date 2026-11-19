package com.example.inchat.crypto

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmDeliveryWorkerRunnerTest {
    @Test
    fun processesExactAcknowledgementsBeforeUploadingOnlyTheActiveAccountDevice() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "already-received", listOf(prepared("sender-device", "phone")))
        queue.enqueue("alice", "sender-device", "still-pending", listOf(prepared("sender-device", "tablet")))
        queue.enqueue("mallory", "other-device", "other-account", listOf(prepared("other-device", "other-phone")))

        val acknowledgedRow = store.rows.values.first { it.messageId == "already-received" }
        val events = mutableListOf<String>()
        val transport = FakeTransport(
            events = events,
            acknowledgements = mutableListOf(acknowledgedRow.toAcknowledgement()),
        )

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            clock = { 10_000L },
        ).run()

        assertEquals(1, result.acknowledged)
        assertEquals(1, result.uploaded)
        assertFalse(result.retryRequired)
        assertEquals(
            listOf(
                "read-acks",
                "delete-ack:" + acknowledgedRow.deliveryId,
                "upload:still-pending",
            ),
            events,
        )
        assertFalse(store.rows.containsKey(acknowledgedRow.deliveryId))
        assertTrue(store.rows.values.any { it.messageId == "other-account" })
        assertTrue(store.rows.values.any { it.messageId == "still-pending" })
    }

    @Test
    fun acknowledgementDeletionFailurePreservesRowsAndBlocksFurtherUploads() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "already-received", listOf(prepared("sender-device", "phone")))
        queue.enqueue("alice", "sender-device", "still-pending", listOf(prepared("sender-device", "tablet")))

        val acknowledgedRow = store.rows.values.first { it.messageId == "already-received" }
        val acknowledgement = acknowledgedRow.toAcknowledgement()
        val remoteAcknowledgements = mutableListOf(acknowledgement)
        val events = mutableListOf<String>()
        val transport = FakeTransport(
            events = events,
            acknowledgements = remoteAcknowledgements,
            failAcknowledgementRemoval = true,
        )

        var deletionFailureObserved = false
        try {
            OlmDeliveryWorkerRunner(
                queue = queue,
                transport = transport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                clock = { 10_000L },
            ).run()
        } catch (expected: IllegalStateException) {
            deletionFailureObserved = expected.message.orEmpty().contains("acknowledgement deletion")
        }

        assertTrue(deletionFailureObserved)
        assertEquals(listOf("read-acks", "delete-ack:" + acknowledgedRow.deliveryId), events)
        assertEquals(listOf(acknowledgement), remoteAcknowledgements)
        assertEquals(2, store.rows.size)
        assertEquals(OlmDeliveryStatus.QUEUED, store.rows.getValue(acknowledgedRow.deliveryId).status)
        assertTrue(store.rows.values.any { it.messageId == "still-pending" })
        assertFalse(events.any { it.startsWith("upload:") })
    }

    @Test
    fun interruptedUploadIsReclaimedAfterLeaseExpiryWithTheSameDeliveryId() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue("alice", "sender-device", "interrupted-upload", listOf(prepared("sender-device", "phone")))

        // Simulate process death after the queue claim. The row is still IN_FLIGHT,
        // but the remote write's completion was never observed by this process.
        val interruptedClaim = queue.claimNext("alice", "sender-device", 1_000L)!!
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, interruptedClaim.status)
        assertEquals(1, interruptedClaim.attemptCount)

        // The relay accepted the ciphertext, but the process died before it could
        // observe the write completion or process a recipient acknowledgement.
        val uploadedDeliveryIds = mutableListOf<String>()
        assertEquals(
            FirebaseOlmUploadResult.STORED,
            FakeTransport(
                events = mutableListOf(),
                acknowledgements = mutableListOf(),
                uploadedDeliveryIds = uploadedDeliveryIds,
            ).upload(interruptedClaim, "sender-device"),
        )

        val beforeLeaseExpiry = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(mutableListOf(), mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            clock = { 1_099L },
        ).run()
        assertEquals(0, beforeLeaseExpiry.uploaded)
        assertFalse(beforeLeaseExpiry.retryRequired)

        val recovered = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(
                events = mutableListOf(),
                acknowledgements = mutableListOf(),
                uploadedDeliveryIds = uploadedDeliveryIds,
            ),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            clock = { 1_100L },
        ).run()

        assertEquals(1, recovered.uploaded)
        assertFalse(recovered.retryRequired)
        assertEquals(
            listOf(interruptedClaim.deliveryId, interruptedClaim.deliveryId),
            uploadedDeliveryIds,
        )
        val stored = store.rows.getValue(interruptedClaim.deliveryId)
        assertEquals(2, stored.attemptCount)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, stored.status)
    }

    @Test
    fun cancellationDuringUploadKeepsLeaseAndReclaimsSameDeliveryAfterExpiry() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue(
            "alice",
            "sender-device",
            "cancelled-upload",
            listOf(prepared("sender-device", "phone")),
        )
        val original = store.rows.values.single()

        val cancellingTransport = object : OlmOutgoingDeliveryTransport {
            override suspend fun upload(
                delivery: QueuedOlmDeviceDelivery,
                senderDeviceId: String,
            ): FirebaseOlmUploadResult {
                assertEquals(original.deliveryId, delivery.deliveryId)
                assertEquals(OlmDeliveryStatus.IN_FLIGHT, delivery.status)
                throw CancellationException("worker stopped")
            }

            override suspend fun readMyDeliveryAcknowledgements(
                senderDeviceId: String,
            ): List<OlmDeliveryAcknowledgement> = emptyList()

            override suspend fun removeMyDeliveryAcknowledgement(
                senderDeviceId: String,
                deliveryId: String,
            ) = Unit
        }

        var cancellationPropagated = false
        try {
            OlmDeliveryWorkerRunner(
                queue = queue,
                transport = cancellingTransport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                clock = { 1_000L },
            ).run()
        } catch (cancelled: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        val interrupted = store.rows.getValue(original.deliveryId)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, interrupted.status)
        assertEquals(1, interrupted.attemptCount)

        val beforeLeaseExpiry = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(mutableListOf(), mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            clock = { 1_099L },
        ).run()
        assertEquals(0, beforeLeaseExpiry.uploaded)
        assertFalse(beforeLeaseExpiry.retryRequired)

        val uploadedIds = mutableListOf<String>()
        val recovered = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(
                events = mutableListOf(),
                acknowledgements = mutableListOf(),
                uploadedDeliveryIds = uploadedIds,
            ),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            clock = { 1_100L },
        ).run()

        assertEquals(1, recovered.uploaded)
        assertEquals(listOf(original.deliveryId), uploadedIds)
        assertEquals(2, store.rows.getValue(original.deliveryId).attemptCount)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, store.rows.getValue(original.deliveryId).status)
    }

    @Test
    fun fullBatchRequestsAnotherPassAndNextPassDrainsOnlyRemainingScopedRows() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "batch-first", listOf(prepared("sender-device", "phone-1")))
        queue.enqueue("alice", "sender-device", "batch-second", listOf(prepared("sender-device", "phone-2")))
        queue.enqueue("alice", "sender-device", "batch-third", listOf(prepared("sender-device", "phone-3")))
        queue.enqueue("alice", "retired-device", "retired-device-row", listOf(prepared("retired-device", "phone-4")))

        val firstPassEvents = mutableListOf<String>()
        val firstPass = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(firstPassEvents, mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            clock = { 10_000L },
            maxDeliveriesPerRun = 2,
        ).run()

        assertEquals(2, firstPass.uploaded)
        assertTrue(firstPass.retryRequired)
        assertEquals(
            listOf("read-acks", "upload:batch-first", "upload:batch-second"),
            firstPassEvents,
        )
        assertEquals(
            OlmDeliveryStatus.QUEUED,
            store.rows.values.single { it.messageId == "batch-third" }.status,
        )
        assertTrue(store.rows.values.any { it.messageId == "retired-device-row" })

        val secondPassEvents = mutableListOf<String>()
        val secondPass = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(secondPassEvents, mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            clock = { 10_001L },
            maxDeliveriesPerRun = 2,
        ).run()

        assertEquals(1, secondPass.uploaded)
        assertFalse(secondPass.retryRequired)
        assertEquals(
            listOf("read-acks", "upload:batch-third"),
            secondPassEvents,
        )
        assertTrue(store.rows.values.any { it.messageId == "retired-device-row" })
        assertEquals(
            3,
            store.rows.values.count { it.ownerUserId == "alice" && it.senderDeviceId == "sender-device" },
        )
    }

    @Test
    fun failedUploadPersistsRetryStateInTheScopedOutbox() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "retry-me", listOf(prepared("sender-device", "phone")))
        val transport = FakeTransport(mutableListOf(), mutableListOf(), failUploads = true)

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            clock = { 10_000L },
        ).run()

        val row = store.rows.values.single()
        assertTrue(result.retryRequired)
        assertEquals(OlmDeliveryStatus.QUEUED, row.status)
        assertEquals("relay_upload_failed", row.lastError)
        assertEquals(12_000L, row.nextAttemptAtMillis)
    }

    private fun prepared(senderDeviceId: String, recipientDeviceId: String): PreparedOlmDelivery {
        val envelope = JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", senderDeviceId)
            .put("recipientDeviceId", recipientDeviceId)
            .put("sessionId", "session-" + recipientDeviceId)
            .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.URL_SAFE or Base64.NO_WRAP))
            .toString()
        return PreparedOlmDelivery("bob", recipientDeviceId, "session-" + recipientDeviceId, envelope)
    }

    private fun QueuedOlmDeviceDelivery.toAcknowledgement() = OlmDeliveryAcknowledgement(
        deliveryId = deliveryId,
        recipientUserId = recipientUserId,
        recipientDeviceId = recipientDeviceId,
        messageId = messageId,
        senderUserId = requireNotNull(ownerUserId),
        senderDeviceId = requireNotNull(senderDeviceId),
    )

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
            val candidate = rows.values.filter {
                it.ownerUserId == ownerUserId && it.senderDeviceId == senderDeviceId &&
                    ((it.status == OlmDeliveryStatus.QUEUED && it.nextAttemptAtMillis <= nowMillis) ||
                        (it.status == OlmDeliveryStatus.IN_FLIGHT && it.leaseUntilMillis <= nowMillis))
            }.minByOrNull { it.nextAttemptAtMillis } ?: return null
            if (candidate.attemptCount >= maxAttempts) {
                rows[candidate.deliveryId] = candidate.copy(
                    status = OlmDeliveryStatus.FAILED,
                    leaseUntilMillis = 0,
                    lastError = "attempt_limit_reached",
                )
                return null
            }
            return candidate.copy(
                status = OlmDeliveryStatus.IN_FLIGHT,
                attemptCount = candidate.attemptCount + 1,
                leaseUntilMillis = nowMillis + leaseDurationMillis,
            ).also { rows[it.deliveryId] = it }
        }

        override fun updateInFlight(
            deliveryId: String,
            messageId: String,
            ownerUserId: String,
            senderDeviceId: String,
            update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
        ): Boolean {
            val row = rows[deliveryId] ?: return false
            if (row.messageId != messageId || row.ownerUserId != ownerUserId ||
                row.senderDeviceId != senderDeviceId || row.status != OlmDeliveryStatus.IN_FLIGHT
            ) return false
            rows[deliveryId] = update(row)
            return true
        }
    }

    private class FakeTransport(
        private val events: MutableList<String>,
        private val acknowledgements: MutableList<OlmDeliveryAcknowledgement>,
        private val failUploads: Boolean = false,
        private val uploadedDeliveryIds: MutableList<String> = mutableListOf(),
        private val failAcknowledgementRemoval: Boolean = false,
    ) : OlmOutgoingDeliveryTransport {
        override suspend fun upload(
            delivery: QueuedOlmDeviceDelivery,
            senderDeviceId: String,
        ): FirebaseOlmUploadResult {
            events += "upload:" + delivery.messageId
            uploadedDeliveryIds += delivery.deliveryId
            check(delivery.senderDeviceId == senderDeviceId)
            if (failUploads) throw IllegalStateException("temporary relay failure")
            return FirebaseOlmUploadResult.STORED
        }

        override suspend fun readMyDeliveryAcknowledgements(
            senderDeviceId: String,
        ): List<OlmDeliveryAcknowledgement> {
            events += "read-acks"
            return acknowledgements.toList()
        }

        override suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String) {
            events += "delete-ack:" + deliveryId
            if (failAcknowledgementRemoval) {
                throw IllegalStateException("temporary acknowledgement deletion failure")
            }
            acknowledgements.removeAll { it.deliveryId == deliveryId }
        }
    }
}
