package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupKeyDistributionReadinessTest {
    @Test
    fun acceptsOnlyPersistedExactDistributionWhenEveryRemoteTargetAcknowledged() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone")), metadata)
        verifier.requireReady("alice", "alice-phone", membership(), keyDistribution(), messageDistribution())
        assertTrue(metadata.get("alice", "alice-phone", "group-key-7") != null)
    }

    @Test
    fun refusesDistributionWithoutDurableMetadata() {
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone")), FakeMetadataStore())
        assertRejected { verifier.requireReady("alice", "alice-phone", membership(), keyDistribution(), messageDistribution()) }
    }

    @Test
    fun refusesMetadataWithChangedSession() {
        val metadata = FakeMetadataStore()
        metadata.seed(record().toEntity().copy(megolmSessionId = "fedcba0987654321"))
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(setOf("bob-phone")), metadata)
        assertRejected { verifier.requireReady("alice", "alice-phone", membership(), keyDistribution(), messageDistribution()) }
    }

    @Test
    fun refusesKeyDistributionUntilEveryRemoteDeviceAcknowledges() {
        val metadata = FakeMetadataStore()
        metadata.persistIfAbsent(record())
        val verifier = RoomOlmGroupKeyDistributionReadiness(FakeDeliveryReceipts(emptySet()), metadata)
        assertRejected { verifier.requireReady("alice", "alice-phone", membership(), keyDistribution(), messageDistribution()) }
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Expected readiness to fail closed", rejected)
    }

    private fun membership() = GroupCryptoMembershipSnapshot(
        groupId = "group-1", epoch = 7L, members = mapOf("alice" to "admin", "bob" to "member"),
    )
    private fun keyDistribution() = PreparedOlmGroupKeyDistribution(
        distributionId = "group-key-7", groupId = "group-1", membershipEpoch = 7L, megolmSessionId = "1234567890abcdef",
        deliveries = listOf(keyDelivery("alice", "alice-tablet", "session-tablet"), keyDelivery("bob", "bob-phone", "session-bob")), skippedUntrustedDeviceCount = 0,
    )
    private fun messageDistribution() = PreparedOlmGroupMessageDistribution(
        messageId = "group-message-1", groupId = "group-1", membershipEpoch = 7L, megolmSessionId = "1234567890abcdef",
        deliveries = listOf(messageDelivery("alice", "alice-tablet", "session-tablet"), messageDelivery("bob", "bob-phone", "session-bob")), skippedUntrustedDeviceCount = 0,
    )
    private fun keyDelivery(user: String, device: String, session: String) = PreparedOlmGroupKeyDelivery(
        recipientUserId = user, recipientDeviceId = device, sessionId = session, envelope = "opaque-encrypted-envelope",
    )
    private fun messageDelivery(user: String, device: String, session: String) = PreparedOlmGroupMessageDelivery(
        recipientUserId = user, recipientDeviceId = device, sessionId = session, envelope = "opaque-encrypted-envelope",
    )
    private fun record() = OlmGroupKeyDistributionRecord(
        ownerUserId = "alice", senderDeviceId = "alice-phone", distributionId = "group-key-7", groupId = "group-1",
        membershipEpoch = 7L, megolmSessionId = "1234567890abcdef",
        targetDevices = listOf("alice" to "alice-tablet", "bob" to "bob-phone"),
    )

    private class FakeMetadataStore : OlmGroupKeyDistributionMetadataStore {
        private val entries = mutableMapOf<String, OlmGroupKeyDistributionEntity>()
        fun seed(entity: OlmGroupKeyDistributionEntity) { entries[entity.recordId] = entity }
        override fun persistIfAbsent(record: OlmGroupKeyDistributionRecord) { seed(record.toEntity()) }
        override fun get(ownerUserId: String, senderDeviceId: String, distributionId: String) =
            entries[OlmGroupKeyDistributionRecord.recordId(ownerUserId, senderDeviceId, distributionId)]
        override fun getAllForDevice(ownerUserId: String, senderDeviceId: String) =
            entries.values.filter { it.ownerUserId == ownerUserId && it.senderDeviceId == senderDeviceId }
    }

    private class FakeDeliveryReceipts(private val acknowledgedDevices: Set<String>) : OlmDeliveryReceiptDao {
        override fun insertIfAbsent(receipt: OlmDeliveryReceiptEntity) = -1L
        override fun getByDeliveryId(deliveryId: String): OlmDeliveryReceiptEntity? = null
        override fun observeDeliveredMessageIds(ownerUserId: String, senderDeviceId: String, recipientUserId: String): Flow<List<String>> = flowOf(emptyList())
        override fun getDeliveredMessageIds(ownerUserId: String, senderDeviceId: String, recipientUserId: String): List<String> = emptyList()
        override fun getAcknowledgedRecipientDeviceIds(ownerUserId: String, senderDeviceId: String, recipientUserId: String, messageId: String): List<String> =
            if (ownerUserId == "alice" && senderDeviceId == "alice-phone" && recipientUserId == "bob" && messageId == "group-key-7") acknowledgedDevices.toList() else emptyList()
        override fun getAcknowledgedTargetIdentitiesForDistribution(
            ownerUserId: String,
            senderDeviceId: String,
            distributionId: String,
        ): List<String> = emptyList()
        override fun observeHasRecipientAcknowledgement(ownerUserId: String, senderDeviceId: String, recipientUserId: String, messageId: String): Flow<Boolean> = flowOf(false)
    }
}