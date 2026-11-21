package com.example.inchat.crypto

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class OlmDeliveryQueueTest {
    @Test
    fun enqueueIsIdempotentAndCreatesIndependentPerDeviceRows() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        val prepared = listOf(prepared("phone"), prepared("tablet"))

        assertEquals(2, queue.enqueue("alice", "sender-device", "message-1", prepared))
        assertEquals(0, queue.enqueue("alice", "sender-device", "message-1", prepared))
        assertEquals(2, store.rows.size)
        assertEquals(2, store.rows.values.map { it.deliveryId }.distinct().size)
    }

    @Test
    fun rejectsEnvelopeAddressedToDifferentDevice() {
        val queue = OlmDeliveryQueue(InMemoryQueueStore())
        val wrongTarget = prepared("phone").copy(
            recipientDeviceId = "tablet",
        )
        try {
            queue.enqueue("alice", "sender-device", "message-1", listOf(wrongTarget))
            throw AssertionError("Expected mismatched recipient to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun claimAckAndDuplicateAckAreSafe() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "message-1", listOf(prepared("phone")))

        val claimed = queue.claimNext("alice", "sender-device", 10_000)!!
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, claimed.status)
        assertEquals(1, claimed.attemptCount)
        assertTrue(queue.acknowledge(claimed, 10_001L))
        assertFalse(queue.acknowledge(claimed, 10_001L))
        assertNull(queue.claimNext("alice", "sender-device", 20_000))
    }

    @Test
    fun acknowledgementMustMatchEveryDeliveryIdentityFieldBeforeRemoval() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "message-1", listOf(prepared("phone")))
        val row = store.rows.values.single()
        val acknowledgement = OlmDeliveryAcknowledgement(
            deliveryId = row.deliveryId,
            recipientUserId = row.recipientUserId,
            recipientDeviceId = row.recipientDeviceId,
            messageId = row.messageId,
            senderUserId = requireNotNull(row.ownerUserId),
            senderDeviceId = requireNotNull(row.senderDeviceId),
        )

        assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(messageId = "other-message")))
        assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(recipientUserId = "mallory")))
        assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(recipientDeviceId = "other-device")))
        assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(deliveryId = "f".repeat(64))))
        assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(senderUserId = "mallory")))
        assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(senderDeviceId = "other-sender-device")))
        assertFalse(queue.removeAcknowledgedDelivery(acknowledgement.copy(recipientUserId = "mallory")))
        assertEquals(1, store.rows.size)

        assertTrue(queue.hasMatchingDelivery(acknowledgement))
        assertTrue(queue.removeAcknowledgedDelivery(acknowledgement))
        assertFalse(queue.removeAcknowledgedDelivery(acknowledgement))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun retryUsesBackoffAndStopsAfterConfiguredAttempts() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, maxAttempts = 2)
        queue.enqueue("alice", "sender-device", "message-1", listOf(prepared("phone")))

        val first = queue.claimNext("alice", "sender-device", 10_000)!!
        assertTrue(queue.retry(first, 10_000, "network"))
        assertNull(queue.claimNext("alice", "sender-device", 11_999))
        val second = queue.claimNext("alice", "sender-device", 12_000)!!
        assertEquals(2, second.attemptCount)
        assertTrue(queue.retry(second, 12_000, "network"))

        assertEquals(OlmDeliveryStatus.FAILED, store.rows.getValue(first.deliveryId).status)
        assertNull(queue.claimNext("alice", "sender-device", Long.MAX_VALUE))
    }

    @Test
    fun expiredLeaseCanBeReclaimedAfterProcessInterruption() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100)
        queue.enqueue("alice", "sender-device", "message-1", listOf(prepared("phone")))

        val first = queue.claimNext("alice", "sender-device", 1_000)!!
        assertNull(queue.claimNext("alice", "sender-device", 1_050))
        val reclaimed = queue.claimNext("alice", "sender-device", 1_100)!!
        assertEquals(first.deliveryId, reclaimed.deliveryId)
        assertEquals(2, reclaimed.attemptCount)
    }

    @Test
    fun expiredClaimCannotChangeAReclaimedAttempt() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue("alice", "sender-device", "stale-lease", listOf(prepared("phone")))

        val staleClaim = queue.claimNext("alice", "sender-device", 1_000L)!!
        val activeClaim = queue.claimNext("alice", "sender-device", 1_100L)!!
        assertEquals(1, staleClaim.attemptCount)
        assertEquals(2, activeClaim.attemptCount)

        // Simulate a stale worker whose clock moved backwards after the newer
        // worker acquired the row. The persisted attempt generation must fence it.
        assertFalse(queue.retry(staleClaim, 1_050L, "stale_worker_failure"))
        assertFalse(queue.acknowledge(staleClaim, 1_050L))

        val unchanged = store.rows.getValue(activeClaim.deliveryId)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, unchanged.status)
        assertEquals(2, unchanged.attemptCount)
        assertEquals(activeClaim.leaseUntilMillis, unchanged.leaseUntilMillis)
        assertNull(unchanged.lastError)

        assertTrue(queue.retry(activeClaim, 1_100L, "current_worker_failure"))
        assertEquals(OlmDeliveryStatus.QUEUED, store.rows.getValue(activeClaim.deliveryId).status)
    }

    @Test
    fun rejectsAckForWrongMessageId() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "message-1", listOf(prepared("phone")))
        val claimed = queue.claimNext("alice", "sender-device", 1_000)!!
        assertFalse(queue.acknowledge(claimed.copy(messageId = "different-message"), 1_001L))
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, store.rows.getValue(claimed.deliveryId).status)
    }

    @Test
    fun explicitFailedRetryReusesTheExactCiphertextAndDeliveryIdentity() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, maxAttempts = 1)
        queue.enqueue("alice", "sender-device", "manual-retry", listOf(prepared("phone")))
        val firstClaim = queue.claimNext("alice", "sender-device", 10_000L)!!
        assertTrue(queue.retry(firstClaim, 10_000L, "terminal_failure"))
        val failed = store.rows.getValue(firstClaim.deliveryId)
        assertEquals(OlmDeliveryStatus.FAILED, failed.status)
        assertFalse(queue.retryFailed(firstClaim, 10_001L))

        assertTrue(queue.retryFailed(failed, 20_000L))
        val queued = store.rows.getValue(firstClaim.deliveryId)
        assertEquals(OlmDeliveryStatus.QUEUED, queued.status)
        assertEquals(0, queued.attemptCount)
        assertEquals(20_000L, queued.nextAttemptAtMillis)
        assertEquals(0L, queued.leaseUntilMillis)
        assertNull(queued.lastError)
        assertEquals(firstClaim.deliveryId, queued.deliveryId)
        assertEquals(firstClaim.envelope, queued.envelope)
        val retried = queue.claimNext("alice", "sender-device", 20_000L)!!
        assertEquals(firstClaim.deliveryId, retried.deliveryId)
        assertEquals(firstClaim.envelope, retried.envelope)
        assertEquals(1, retried.attemptCount)
    }

    private fun prepared(deviceId: String): PreparedOlmDelivery {
        val encoded = JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", "sender-device")
            .put("recipientDeviceId", deviceId)
            .put("sessionId", "session-$deviceId")
            .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.URL_SAFE or Base64.NO_WRAP))
            .toString()
        return PreparedOlmDelivery("bob", deviceId, "session-$deviceId", encoded)
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

        override fun claimNextDue(ownerUserId: String, senderDeviceId: String, nowMillis: Long, leaseDurationMillis: Long, maxAttempts: Int): QueuedOlmDeviceDelivery? {
            val candidate = rows.values
                .filter {
                    it.ownerUserId == ownerUserId && it.senderDeviceId == senderDeviceId &&
                        ((it.status == OlmDeliveryStatus.QUEUED && it.nextAttemptAtMillis <= nowMillis) ||
                        (it.status == OlmDeliveryStatus.UPLOADED && it.nextAttemptAtMillis <= nowMillis) ||
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
}
