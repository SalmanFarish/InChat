package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupSessionKeyOutboxCoordinatorTest {
    @Test
    fun persistsEveryPerDeviceKeyEnvelopeAndSchedulesExistingWorker() {
        val store = FakeStore()
        val scheduled = mutableListOf<Pair<String, String>>()
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(store),
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { owner, device -> scheduled += owner to device },
        )

        val inserted = coordinator.enqueue(membership(), distribution())

        assertEquals(2, inserted)
        assertEquals(
            setOf("bob" to "bob-phone", "alice" to "alice-tablet"),
            store.rows.map { it.recipientUserId to it.recipientDeviceId }.toSet(),
        )
        assertEquals(listOf("alice" to "alice-phone"), scheduled)
        assertTrue(store.rows.all {
            it.ownerUserId == "alice" &&
                it.senderDeviceId == "alice-phone" &&
                it.messageId == "group-key-7"
        })
    }

    @Test
    fun refusesKeyDistributionThatOmitsCurrentMemberBeforeQueueWrite() {
        val store = FakeStore()
        var scheduled = false
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(store),
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { _, _ -> scheduled = true },
        )
        val incomplete = distribution().copy(
            deliveries = listOf(delivery("bob", "bob-phone", "session-bob")),
        )

        var rejected = false
        try {
            coordinator.enqueue(membership(), incomplete)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(store.rows.isEmpty())
        assertTrue(!scheduled)
    }

    private fun membership() = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = 7L,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private fun distribution() = PreparedOlmGroupKeyDistribution(
        distributionId = "group-key-7",
        groupId = "group-1",
        membershipEpoch = 7L,
        megolmSessionId = "1234567890abcdef",
        deliveries = listOf(
            delivery("alice", "alice-tablet", "session-tablet"),
            delivery("bob", "bob-phone", "session-bob"),
        ),
        skippedUntrustedDeviceCount = 0,
    )

    private fun delivery(userId: String, deviceId: String, sessionId: String) =
        PreparedOlmGroupKeyDelivery(
            recipientUserId = userId,
            recipientDeviceId = deviceId,
            sessionId = sessionId,
            envelope = EncryptedOlmEnvelopeCodec.encode(
                EncryptedOlmEnvelope(
                    senderDeviceId = "alice-phone",
                    recipientDeviceId = deviceId,
                    sessionId = sessionId,
                    ciphertext = ByteArray(40) { (it + 2).toByte() },
                ),
            ),
        )

    private class FakeStore : OlmDeliveryQueueStore {
        val rows = mutableListOf<QueuedOlmDeviceDelivery>()

        override fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int {
            val inserted = deliveries.filter { candidate ->
                rows.none { it.deliveryId == candidate.deliveryId }
            }
            rows += inserted
            return inserted.size
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
