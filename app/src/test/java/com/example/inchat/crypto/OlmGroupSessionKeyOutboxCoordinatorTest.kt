package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class OlmGroupSessionKeyOutboxCoordinatorTest {
    @Test
    fun persistsEveryPerDeviceKeyEnvelopeAndSchedulesExistingWorker() {
        val store = FakeStore()
        val scheduled = mutableListOf<Pair<String, String>>()
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(store),
            metadataStore = FakeMetadataStore(),
            deliveryReceipts = FakeDeliveryReceipts(),
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
                it.messageId == "group-key-7" &&
                it.groupId == "group-1" &&
                it.membershipEpoch == 7L
        })
    }

    @Test
    fun groupOutboxScopeRequiresExactEpochAndBothCurrentMembers() {
        val delivery = QueuedOlmDeviceDelivery(
            messageId = "group-key-7",
            deliveryId = "a".repeat(64),
            recipientUserId = "bob",
            recipientDeviceId = "bob-phone",
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            groupId = "group-1",
            membershipEpoch = 7L,
            conversationId = "group-1",
            envelope = "ciphertext",
        )

        assertTrue(isGroupDeliveryScopeCurrent(delivery, "alice", membership()))
        assertTrue(!isGroupDeliveryScopeCurrent(delivery, "alice", membership().copy(epoch = 8L)))
        assertTrue(!isGroupDeliveryScopeCurrent(delivery, "mallory", membership()))
        assertTrue(!isGroupDeliveryScopeCurrent(
            delivery,
            "alice",
            membership().copy(members = mapOf("alice" to "admin", "mallory" to "member")),
        ))
        assertTrue(!isGroupDeliveryScopeCurrent(delivery.copy(membershipEpoch = null), "alice", membership()))
    }

    @Test
    fun refusesKeyDistributionThatOmitsCurrentMemberBeforeQueueWrite() {
        val store = FakeStore()
        var scheduled = false
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(store),
            metadataStore = FakeMetadataStore(),
            deliveryReceipts = FakeDeliveryReceipts(),
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { _, _ -> scheduled = true },
        )
        // The sender's other device is present, but the current remote
        // member (bob) is deliberately omitted from this prepared fan-out.
        val incomplete = distribution().copy(
            deliveries = listOf(delivery("alice", "alice-tablet", "session-tablet")),
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

    @Test
    fun metadataSurvivesOutboxInsertFailureAndCanBeResumed() {
        val queueStore = FakeStore().apply { failNextInsert = true }
        val metadata = FakeMetadataStore()
        var scheduled = false
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(queueStore),
            metadataStore = metadata,
            deliveryReceipts = FakeDeliveryReceipts(),
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { _, _ -> scheduled = true },
        )

        var interrupted = false
        try {
            coordinator.enqueue(membership(), distribution())
        } catch (_: IllegalStateException) {
            interrupted = true
        }
        assertTrue(interrupted)
        assertTrue(!scheduled)
        assertEquals(0, queueStore.rows.size)
        assertTrue(metadata.get("alice", "alice-phone", "group-key-7") != null)

        assertEquals(2, coordinator.resume(membership(), distribution()))
        assertEquals(2, queueStore.rows.size)
        assertTrue(scheduled)
    }

    @Test
    fun partialResumeAcceptsOnlyTargetsProvenMissingByDurableRecoveryState() {
        val queueStore = FakeStore()
        val metadata = FakeMetadataStore()
        val fullDistribution = distribution()
        val recoveryReader = object : OlmGroupKeyDistributionRecoveryStateReader {
            override fun read(metadata: OlmGroupKeyDistributionEntity) =
                OlmGroupKeyDistributionRecoveryState(
                    queuedTargets = setOf("alice" to "alice-tablet"),
                    acknowledgedTargets = emptySet(),
                    failedTargets = emptySet(),
                )
        }
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(queueStore),
            metadataStore = metadata,
            deliveryReceipts = FakeDeliveryReceipts(),
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { _, _ -> },
            recoveryStateReader = recoveryReader,
        )
        coordinator.enqueue(membership(), fullDistribution)
        queueStore.rows.clear()

        val partial = fullDistribution.copy(
            deliveries = listOf(delivery("bob", "bob-phone", "session-bob")),
        )
        assertEquals(1, coordinator.resume(membership(), partial))
        assertEquals(
            setOf("bob" to "bob-phone"),
            queueStore.rows.map { it.recipientUserId to it.recipientDeviceId }.toSet(),
        )
    }

    @Test
    fun partialResumeRejectsTargetsAlreadyQueuedOrAcknowledged() {
        val queueStore = FakeStore()
        val metadata = FakeMetadataStore()
        val fullDistribution = distribution()
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(queueStore),
            metadataStore = metadata,
            deliveryReceipts = FakeDeliveryReceipts(),
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { _, _ -> },
            recoveryStateReader = object : OlmGroupKeyDistributionRecoveryStateReader {
                override fun read(metadata: OlmGroupKeyDistributionEntity) =
                    OlmGroupKeyDistributionRecoveryState(
                        queuedTargets = setOf("alice" to "alice-tablet"),
                        acknowledgedTargets = setOf("bob" to "bob-phone"),
                        failedTargets = emptySet(),
                    )
            },
        )
        coordinator.enqueue(membership(), fullDistribution)
        queueStore.rows.clear()

        var rejected = false
        try {
            coordinator.resume(
                membership(),
                fullDistribution.copy(
                    deliveries = listOf(delivery("bob", "bob-phone", "session-bob")),
                ),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(queueStore.rows.isEmpty())
    }

    @Test
    fun resumeDoesNotRequeueRemoteDevicesAlreadyAcknowledgedBeforeRowRemoval() {
        val queueStore = FakeStore()
        val metadata = FakeMetadataStore()
        val receipts = FakeDeliveryReceipts(mapOf(("bob" to "group-key-7") to setOf("bob-phone")))
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(queueStore),
            metadataStore = metadata,
            deliveryReceipts = receipts,
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { _, _ -> },
        )
        coordinator.enqueue(membership(), distribution())
        queueStore.rows.clear()
        val inserted = coordinator.resume(membership(), distribution())
        assertEquals(1, inserted)
        assertEquals(setOf("alice" to "alice-tablet"),
            queueStore.rows.map { it.recipientUserId to it.recipientDeviceId }.toSet())
    }
    @Test
    fun resumeDoesNotRequeueSenderCompanionDevicesAlreadyAcknowledgedBeforeRowRemoval() {
        val queueStore = FakeStore()
        val metadata = FakeMetadataStore()
        val receipts = FakeDeliveryReceipts(
            mapOf(("alice" to "group-key-7") to setOf("alice-tablet")),
        )
        val coordinator = OlmGroupSessionKeyOutboxCoordinator(
            queue = OlmDeliveryQueue(queueStore),
            metadataStore = metadata,
            deliveryReceipts = receipts,
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            scheduleDelivery = { _, _ -> },
        )

        assertEquals(1, coordinator.enqueue(membership(), distribution()))
        assertEquals(
            setOf("bob" to "bob-phone"),
            queueStore.rows.map { it.recipientUserId to it.recipientDeviceId }.toSet(),
        )

        queueStore.rows.clear()
        assertEquals(1, coordinator.resume(membership(), distribution()))
        assertEquals(
            setOf("bob" to "bob-phone"),
            queueStore.rows.map { it.recipientUserId to it.recipientDeviceId }.toSet(),
        )
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

    private class FakeDeliveryReceipts(
        private val acknowledged: Map<Pair<String, String>, Set<String>> = emptyMap(),
    ) : OlmDeliveryReceiptDao {
        override fun insertIfAbsent(receipt: OlmDeliveryReceiptEntity) = -1L
        override fun getByDeliveryId(deliveryId: String): OlmDeliveryReceiptEntity? = null
        override fun observeDeliveredMessageIds(ownerUserId: String, senderDeviceId: String, recipientUserId: String): Flow<List<String>> = flowOf(emptyList())
        override fun getDeliveredMessageIds(ownerUserId: String, senderDeviceId: String, recipientUserId: String): List<String> = emptyList()
        override fun getAcknowledgedRecipientDeviceIds(ownerUserId: String, senderDeviceId: String, recipientUserId: String, messageId: String): List<String> = acknowledged[recipientUserId to messageId].orEmpty().toList()
        override fun getAcknowledgedTargetIdentitiesForDistribution(ownerUserId: String, senderDeviceId: String, distributionId: String): List<String> = emptyList()
        override fun observeAcknowledgementCount(
            ownerUserId: String,
            senderDeviceId: String,
        ): Flow<Int> = flowOf(0)

        override fun observeHasRecipientAcknowledgement(ownerUserId: String, senderDeviceId: String, recipientUserId: String, messageId: String): Flow<Boolean> = flowOf(false)
        override fun observeDeliveredMessageIdsForConversation(
            ownerUserId: String,
            recipientUserId: String,
            conversationId: String,
        ): Flow<List<String>> = flowOf(emptyList())
    }
    private class FakeMetadataStore : OlmGroupKeyDistributionMetadataStore {
        private val records = mutableMapOf<String, OlmGroupKeyDistributionEntity>()
        override fun persistIfAbsent(record: OlmGroupKeyDistributionRecord) {
            val entity = record.toEntity()
            val existing = records[entity.recordId]
            check(existing == null || existing == entity) { "conflicting metadata" }
            records[entity.recordId] = entity
        }
        override fun get(ownerUserId: String, senderDeviceId: String, distributionId: String) =
            records[OlmGroupKeyDistributionRecord.recordId(ownerUserId, senderDeviceId, distributionId)]
        override fun getAllForDevice(ownerUserId: String, senderDeviceId: String) =
            records.values.filter { it.ownerUserId == ownerUserId && it.senderDeviceId == senderDeviceId }
    }
    private class FakeStore : OlmDeliveryQueueStore {
        val rows = mutableListOf<QueuedOlmDeviceDelivery>()
        var failNextInsert = false

        override fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int {
            if (failNextInsert) {
                failNextInsert = false
                throw IllegalStateException("simulated outbox transaction failure")
            }
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

        override fun retryFailed(
            deliveryId: String,
            messageId: String,
            ownerUserId: String,
            senderDeviceId: String,
            nowMillis: Long,
        ) = false

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
