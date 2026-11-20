package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupMessageOutboxCoordinatorTest {
    @Test
    fun persistsEveryPreparedGroupDeviceEnvelopeAndSchedulesExistingWorker() {
        val store = FakeStore()
        val scheduled = mutableListOf<Pair<String, String>>()
        val coordinator = OlmGroupMessageOutboxCoordinator(
            queue = OlmDeliveryQueue(store),
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { owner, device -> scheduled += owner to device },
        )
        val inserted = coordinator.enqueue(
            currentMembership = membership(7L),
            distribution = distribution(7L),
        )

        assertEquals(2, inserted)
        assertEquals(2, store.rows.size)
        assertEquals(setOf("bob" to "bob-phone", "alice" to "alice-tablet"),
            store.rows.map { it.recipientUserId to it.recipientDeviceId }.toSet())
        assertEquals(listOf("alice" to "alice-phone"), scheduled)
        assertTrue(store.rows.all { it.ownerUserId == "alice" && it.senderDeviceId == "alice-phone" })
    }

    @Test
    fun refusesStaleMembershipBeforeWritingAnyGroupDelivery() {
        val store = FakeStore()
        var scheduled = false
        val coordinator = OlmGroupMessageOutboxCoordinator(
            queue = OlmDeliveryQueue(store),
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { _, _ -> scheduled = true },
        )

        var rejected = false
        try {
            coordinator.enqueue(membership(8L), distribution(7L))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(store.rows.isEmpty())
        assertTrue(!scheduled)
    }

    private fun membership(epoch: Long) = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = epoch,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private fun distribution(epoch: Long) = PreparedOlmGroupMessageDistribution(
        messageId = "group-message-1",
        groupId = "group-1",
        membershipEpoch = epoch,
        deliveries = listOf(
            delivery("alice", "alice-tablet", "session-tablet"),
            delivery("bob", "bob-phone", "session-bob"),
        ),
        skippedUntrustedDeviceCount = 0,
    )

    private fun delivery(userId: String, deviceId: String, sessionId: String) =
        PreparedOlmGroupMessageDelivery(
            recipientUserId = userId,
            recipientDeviceId = deviceId,
            sessionId = sessionId,
            envelope = EncryptedOlmEnvelopeCodec.encode(
                EncryptedOlmEnvelope(
                    senderDeviceId = "alice-phone",
                    recipientDeviceId = deviceId,
                    sessionId = sessionId,
                    ciphertext = ByteArray(32) { it.toByte() },
                ),
            ),
        )

    private class FakeStore : OlmDeliveryQueueStore {
        val rows = mutableListOf<QueuedOlmDeviceDelivery>()

        override fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int {
            val newRows = deliveries.filter { candidate ->
                rows.none { it.deliveryId == candidate.deliveryId }
            }
            rows += newRows
            return newRows.size
        }

        override fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement) = false
        override fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement) = false
        override fun claimNextDue(
            ownerUserId: String,
            senderDeviceId: String,
            nowMillis: Long,
            leaseDurationMillis: Long,
            maxAttempts: Int,
        ): QueuedOlmDeviceDelivery? = null

        override fun updateInFlight(
            deliveryId: String,
            messageId: String,
            ownerUserId: String,
            senderDeviceId: String,
            expectedAttemptCount: Int,
            expectedLeaseUntilMillis: Long,
            nowMillis: Long,
            update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
        ) = false
    }
}
