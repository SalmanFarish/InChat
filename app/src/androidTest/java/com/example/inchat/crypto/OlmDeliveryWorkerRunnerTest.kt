package com.example.inchat.crypto

import android.util.Base64
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
        assertTrue(result.retryRequired)
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
    ) : OlmOutgoingDeliveryTransport {
        override suspend fun upload(
            delivery: QueuedOlmDeviceDelivery,
            senderDeviceId: String,
        ): FirebaseOlmUploadResult {
            events += "upload:" + delivery.messageId
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
            acknowledgements.removeAll { it.deliveryId == deliveryId }
        }
    }
}
