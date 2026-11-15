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

        assertEquals(2, queue.enqueue("message-1", prepared))
        assertEquals(0, queue.enqueue("message-1", prepared))
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
            queue.enqueue("message-1", listOf(wrongTarget))
            throw AssertionError("Expected mismatched recipient to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun claimAckAndDuplicateAckAreSafe() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("message-1", listOf(prepared("phone")))

        val claimed = queue.claimNext(10_000)!!
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, claimed.status)
        assertEquals(1, claimed.attemptCount)
        assertTrue(queue.acknowledge(claimed.deliveryId, claimed.messageId))
        assertFalse(queue.acknowledge(claimed.deliveryId, claimed.messageId))
        assertNull(queue.claimNext(20_000))
    }

    @Test
    fun retryUsesBackoffAndStopsAfterConfiguredAttempts() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, maxAttempts = 2)
        queue.enqueue("message-1", listOf(prepared("phone")))

        val first = queue.claimNext(10_000)!!
        assertTrue(queue.retry(first.deliveryId, first.messageId, 10_000, "network"))
        assertNull(queue.claimNext(11_999))
        val second = queue.claimNext(12_000)!!
        assertEquals(2, second.attemptCount)
        assertTrue(queue.retry(second.deliveryId, second.messageId, 12_000, "network"))

        assertEquals(OlmDeliveryStatus.FAILED, store.rows.getValue(first.deliveryId).status)
        assertNull(queue.claimNext(Long.MAX_VALUE))
    }

    @Test
    fun expiredLeaseCanBeReclaimedAfterProcessInterruption() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100)
        queue.enqueue("message-1", listOf(prepared("phone")))

        val first = queue.claimNext(1_000)!!
        assertNull(queue.claimNext(1_050))
        val reclaimed = queue.claimNext(1_100)!!
        assertEquals(first.deliveryId, reclaimed.deliveryId)
        assertEquals(2, reclaimed.attemptCount)
    }

    @Test
    fun rejectsAckForWrongMessageId() {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("message-1", listOf(prepared("phone")))
        val claimed = queue.claimNext(1_000)!!
        assertFalse(queue.acknowledge(claimed.deliveryId, "different-message"))
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, store.rows.getValue(claimed.deliveryId).status)
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

        override fun claimNextDue(nowMillis: Long, leaseDurationMillis: Long): QueuedOlmDeviceDelivery? {
            val candidate = rows.values
                .filter {
                    (it.status == OlmDeliveryStatus.QUEUED && it.nextAttemptAtMillis <= nowMillis) ||
                        (it.status == OlmDeliveryStatus.IN_FLIGHT && it.leaseUntilMillis <= nowMillis)
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

        override fun updateInFlight(
            deliveryId: String,
            messageId: String,
            update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
        ): Boolean {
            val row = rows[deliveryId] ?: return false
            if (row.messageId != messageId || row.status != OlmDeliveryStatus.IN_FLIGHT) return false
            rows[deliveryId] = update(row)
            return true
        }
    }
}
