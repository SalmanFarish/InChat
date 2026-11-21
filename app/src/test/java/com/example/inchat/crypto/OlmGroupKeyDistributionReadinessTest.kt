package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupKeyDistributionReadinessTest {
    @Test
    fun acceptsOnlyPersistedExactDistributionWhenEveryRemoteTargetAcknowledged() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone"), distributionId()), metadata)
        verifier.requireReady("alice", "alice-phone", membership(), keyDistribution(), messageDistribution())
        assertTrue(metadata.get("alice", "alice-phone", distributionId()) != null)
    }

    @Test
    fun preflightReturnsMissingWhenKeyDistributionHasNotBeenPersisted() {
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone"), distributionId()), FakeMetadataStore())
        val state = verifier.evaluate("alice", "alice-phone", membership(), "1234567890abcdef")
        assertTrue(state is OlmGroupKeyDistributionReadinessState.Missing)
    }

    @Test
    fun preflightReportsExactRemoteDevicesStillAwaitingAcknowledgement() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(emptySet(), distributionId()), metadata)

        val state = verifier.evaluate("alice", "alice-phone", membership(), "1234567890abcdef")

        assertTrue(state is OlmGroupKeyDistributionReadinessState.AwaitingAcknowledgements)
        assertEquals(
            setOf("bob" to "bob-phone"),
            (state as OlmGroupKeyDistributionReadinessState.AwaitingAcknowledgements).pendingTargets,
        )
    }

    @Test
    fun preflightBecomesReadyWhenRemoteTargetsAcknowledgeAndDoesNotRequireCompanionAck() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone"), distributionId()), metadata)

        val state = verifier.evaluate("alice", "alice-phone", membership(), "1234567890abcdef")

        assertTrue(state is OlmGroupKeyDistributionReadinessState.Ready)
        val token = (state as OlmGroupKeyDistributionReadinessState.Ready).token
        assertEquals("group-1", token.groupId)
        assertEquals(7L, token.membershipEpoch)
        assertEquals(setOf("alice" to "alice-tablet", "bob" to "bob-phone"), token.targetDevices)
    }

    @Test
    fun refusesDistributionWithoutDurableMetadata() {
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone"), distributionId()), FakeMetadataStore())
        assertRejected { verifier.requireReady("alice", "alice-phone", membership(), keyDistribution(), messageDistribution()) }
    }

    @Test
    fun refusesMetadataWithChangedSession() {
        val metadata = FakeMetadataStore()
        metadata.seed(record().toEntity().copy(megolmSessionId = "fedcba0987654321"))
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone"), distributionId()), metadata)
        assertRejected { verifier.requireReady("alice", "alice-phone", membership(), keyDistribution(), messageDistribution()) }
    }

    @Test
    fun refusesKeyDistributionUntilEveryRemoteDeviceAcknowledges() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(emptySet(), distributionId()), metadata)
        assertRejected { verifier.requireReady("alice", "alice-phone", membership(), keyDistribution(), messageDistribution()) }
    }

    @Test
    fun rechecksAcknowledgementsAfterPreflightBeforeOutboxCommit() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val receipts = FakeDeliveryReceipts(setOf("bob-phone"), distributionId())
        val verifier = RoomOlmGroupKeyDistributionReadiness(receipts, metadata)
        val token = verifier.requireAcknowledged(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            currentMembership = membership(),
            megolmSessionId = "1234567890abcdef",
        )

        receipts.acknowledgedDevices = emptySet()

        assertRejected {
            verifier.requireReady(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                currentMembership = membership(),
                keyDistribution = token,
                messageDistribution = messageDistribution(),
            )
        }
    }

    @Test
    fun finalOutboxGateRejectsNewlyTrustedDeviceMissingFromKeyDistribution() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone"), distributionId()), metadata)
        val token = verifier.requireAcknowledged(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            currentMembership = membership(),
            megolmSessionId = "1234567890abcdef",
        )
        val changedMessageTargets = messageDistribution().copy(
            deliveries = messageDistribution().deliveries + messageDelivery(
                "bob",
                "bob-new-phone",
                "session-new-device",
            ),
        )

        assertRejected {
            verifier.requireReady(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                currentMembership = membership(),
                keyDistribution = token,
                messageDistribution = changedMessageTargets,
            )
        }
    }

    @Test
    fun senderPreflightRejectsTrustedDeviceRosterChangeBeforeStaging() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone"), distributionId()), metadata)
        val token = verifier.requireAcknowledged(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            currentMembership = membership(),
            megolmSessionId = "1234567890abcdef",
        )
        val preflight = RoomOlmGroupMessageSendPreflight(
            readiness = verifier,
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            currentTrustedTargets = {
                setOf(
                    "alice" to "alice-tablet",
                    "bob" to "bob-phone",
                    "bob" to "bob-new-phone",
                )
            },
        )

        var rejected = false
        try {
            kotlinx.coroutines.runBlocking {
                preflight.requireReady(membership(), token)
            }
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected device-roster mismatch to block before staging", rejected)
    }

    @Test
    fun refusesAcknowledgementForTargetOutsideImmutableMetadata() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(
            FakeDeliveryReceipts(setOf("bob-phone", "intruder-device"), distributionId()),
            metadata,
        )
        assertRejected {
            verifier.evaluate("alice", "alice-phone", membership(), "1234567890abcdef")
        }
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected readiness to fail closed", rejected)
    }

    private fun membership() = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = 7L,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private fun keyDistribution() = OlmGroupKeyDistributionReadinessToken(
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        distributionId = distributionId(),
        groupId = "group-1",
        membershipEpoch = 7L,
        megolmSessionId = "1234567890abcdef",
        targetDevices = setOf("alice" to "alice-tablet", "bob" to "bob-phone"),
    )

    private fun messageDistribution() = PreparedOlmGroupMessageDistribution(
        messageId = "group-message-1",
        groupId = "group-1",
        membershipEpoch = 7L,
        megolmSessionId = "1234567890abcdef",
        deliveries = listOf(
            messageDelivery("alice", "alice-tablet", "session-tablet"),
            messageDelivery("bob", "bob-phone", "session-bob"),
        ),
        skippedUntrustedDeviceCount = 0,
    )

    private fun messageDelivery(user: String, device: String, session: String) = PreparedOlmGroupMessageDelivery(
        recipientUserId = user,
        recipientDeviceId = device,
        sessionId = session,
        envelope = "opaque-encrypted-envelope",
    )

    private fun record() = OlmGroupKeyDistributionRecord(
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        distributionId = distributionId(),
        groupId = "group-1",
        membershipEpoch = 7L,
        megolmSessionId = "1234567890abcdef",
        targetDevices = listOf("alice" to "alice-tablet", "bob" to "bob-phone"),
    )

    private fun distributionId() = OlmGroupSessionKeyDistributionId.create(
        membership = membership(),
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        megolmSessionId = "1234567890abcdef",
    )

    private class FakeMetadataStore : OlmGroupKeyDistributionMetadataStore {
        private val entries = mutableMapOf<String, OlmGroupKeyDistributionEntity>()

        fun seed(entity: OlmGroupKeyDistributionEntity) {
            entries[entity.recordId] = entity
        }

        override fun persistIfAbsent(record: OlmGroupKeyDistributionRecord) {
            seed(record.toEntity())
        }

        override fun get(ownerUserId: String, senderDeviceId: String, distributionId: String) =
            entries[OlmGroupKeyDistributionRecord.recordId(ownerUserId, senderDeviceId, distributionId)]

        override fun getAllForDevice(ownerUserId: String, senderDeviceId: String) =
            entries.values.filter { it.ownerUserId == ownerUserId && it.senderDeviceId == senderDeviceId }
    }

    private class FakeDeliveryReceipts(
        var acknowledgedDevices: Set<String>,
        private val expectedDistributionId: String,
    ) : OlmDeliveryReceiptDao {
        override fun insertIfAbsent(receipt: OlmDeliveryReceiptEntity) = -1L
        override fun getByDeliveryId(deliveryId: String): OlmDeliveryReceiptEntity? = null
        override fun observeDeliveredMessageIds(
            ownerUserId: String,
            senderDeviceId: String,
            recipientUserId: String,
        ): Flow<List<String>> = flowOf(emptyList())

        override fun getDeliveredMessageIds(
            ownerUserId: String,
            senderDeviceId: String,
            recipientUserId: String,
        ): List<String> = emptyList()

        override fun getAcknowledgedRecipientDeviceIds(
            ownerUserId: String,
            senderDeviceId: String,
            recipientUserId: String,
            messageId: String,
        ): List<String> =
            if (ownerUserId == "alice" && senderDeviceId == "alice-phone" &&
                recipientUserId == "bob" && messageId == expectedDistributionId
            ) acknowledgedDevices.toList() else emptyList()

        override fun getAcknowledgedTargetIdentitiesForDistribution(
            ownerUserId: String,
            senderDeviceId: String,
            distributionId: String,
        ): List<String> =
            if (ownerUserId == "alice" && senderDeviceId == "alice-phone" &&
                distributionId == expectedDistributionId
            ) acknowledgedDevices.map { "bob/$it" } else emptyList()

        override fun observeHasRecipientAcknowledgement(
            ownerUserId: String,
            senderDeviceId: String,
            recipientUserId: String,
            messageId: String,
        ): Flow<Boolean> = flowOf(false)
    }
}
