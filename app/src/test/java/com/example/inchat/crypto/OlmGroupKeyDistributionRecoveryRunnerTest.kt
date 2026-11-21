package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupKeyDistributionRecoveryRunnerTest {
    @Test
    fun recoversOnlyMissingTargetsFromTheExactExistingMegolmSession() {
        val membership = membership()
        val sessionId = "1234567890abcdef"
        val distributionId = OlmGroupSessionKeyDistributionId.create(
            membership = membership,
            senderUserId = "alice",
            senderDeviceId = "alice-phone",
            megolmSessionId = sessionId,
        )
        val metadata = OlmGroupKeyDistributionRecord(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            distributionId = distributionId,
            groupId = membership.groupId,
            membershipEpoch = membership.epoch,
            megolmSessionId = sessionId,
            targetDevices = listOf("alice" to "alice-tablet", "bob" to "bob-phone"),
        ).toEntity()
        val metadataStore = FakeMetadataStore(metadata)
        val stateReader = FakeStateReader(
            OlmGroupKeyDistributionRecoveryState(
                queuedTargets = setOf("alice" to "alice-tablet"),
                acknowledgedTargets = emptySet(),
                failedTargets = emptySet(),
            ),
        )
        val keyBytes = ByteArray(32) { 7 }
        var preparedTargets: Set<Pair<String, String>> = emptySet()
        var resumed = 0

        val runner = OlmGroupKeyDistributionRecoveryRunner(
            metadataStore = metadataStore,
            recoveryStateReader = stateReader,
            membershipForGroup = { membership },
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            ensureOutboundSession = {
                OutboundMegolmSessionInfo(sessionId, membership.groupId, membership.epoch, created = false)
            },
            exportOutboundSessionKey = { keyBytes },
            prepareDistribution = { id, snapshot, session, key, issuedAt, selected ->
                assertEquals(distributionId, id)
                assertEquals(membership, snapshot)
                assertEquals(sessionId, session)
                assertTrue(key.isNotBlank())
                assertEquals(1234L, issuedAt)
                preparedTargets = selected
                PreparedOlmGroupKeyDistribution(
                    distributionId = id,
                    groupId = snapshot.groupId,
                    membershipEpoch = snapshot.epoch,
                    megolmSessionId = session,
                    deliveries = listOf(
                        PreparedOlmGroupKeyDelivery(
                            recipientUserId = "bob",
                            recipientDeviceId = "bob-phone",
                            sessionId = "olm-session-bob",
                            envelope = "ciphertext-envelope",
                        ),
                    ),
                    skippedUntrustedDeviceCount = 0,
                    targetDevices = metadata.targetDevicePairs(),
                )
            },
            resumeDistribution = { snapshot, prepared ->
                assertEquals(membership, snapshot)
                assertEquals(preparedTargets, prepared.deliveries.map {
                    it.recipientUserId to it.recipientDeviceId
                }.toSet())
                resumed += 1
                1
            },
            nowMillis = { 1234L },
        )

        val result = kotlinx.coroutines.runBlocking { runner.run() }

        assertEquals(1, result.distributionsScanned)
        assertEquals(0, result.distributionsSkipped)
        assertEquals(1, result.deliveriesQueued)
        assertEquals(setOf("bob" to "bob-phone"), preparedTargets)
        assertEquals(1, resumed)
        assertTrue(keyBytes.all { it == 0.toByte() })
    }

    @Test
    fun refusesToResumeWhenMembershipChangesDuringEnvelopePreparation() {
        val initialMembership = membership()
        val changedMembership = GroupCryptoMembershipSnapshot(
            groupId = initialMembership.groupId,
            epoch = initialMembership.epoch + 1,
            members = initialMembership.members,
        )
        val sessionId = "1234567890abcdef"
        val distributionId = OlmGroupSessionKeyDistributionId.create(
            membership = initialMembership,
            senderUserId = "alice",
            senderDeviceId = "alice-phone",
            megolmSessionId = sessionId,
        )
        val metadata = OlmGroupKeyDistributionRecord(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            distributionId = distributionId,
            groupId = initialMembership.groupId,
            membershipEpoch = initialMembership.epoch,
            megolmSessionId = sessionId,
            targetDevices = listOf("alice" to "alice-tablet", "bob" to "bob-phone"),
        ).toEntity()
        var membershipReads = 0
        var resumed = false
        var failureReported = false
        val runner = OlmGroupKeyDistributionRecoveryRunner(
            metadataStore = FakeMetadataStore(metadata),
            recoveryStateReader = FakeStateReader(
                OlmGroupKeyDistributionRecoveryState(
                    queuedTargets = emptySet(),
                    acknowledgedTargets = emptySet(),
                    failedTargets = emptySet(),
                ),
            ),
            membershipForGroup = {
                membershipReads += 1
                if (membershipReads == 1) initialMembership else changedMembership
            },
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            ensureOutboundSession = {
                OutboundMegolmSessionInfo(sessionId, initialMembership.groupId, initialMembership.epoch, created = false)
            },
            exportOutboundSessionKey = { ByteArray(32) { 9 } },
            prepareDistribution = { id, snapshot, session, _, _, _ ->
                PreparedOlmGroupKeyDistribution(
                    distributionId = id,
                    groupId = snapshot.groupId,
                    membershipEpoch = snapshot.epoch,
                    megolmSessionId = session,
                    deliveries = listOf(
                        PreparedOlmGroupKeyDelivery(
                            recipientUserId = "bob",
                            recipientDeviceId = "bob-phone",
                            sessionId = "olm-session-bob",
                            envelope = "ciphertext-envelope",
                        ),
                    ),
                    skippedUntrustedDeviceCount = 0,
                    targetDevices = metadata.targetDevicePairs(),
                )
            },
            resumeDistribution = { _, _ ->
                resumed = true
                1
            },
            onDistributionFailure = { _, _ -> failureReported = true },
        )

        val result = kotlinx.coroutines.runBlocking { runner.run() }

        assertEquals(1, result.distributionsScanned)
        assertEquals(1, result.distributionsFailed)
        assertEquals(0, result.deliveriesQueued)
        assertFalse(resumed)
        assertTrue(failureReported)
    }

    @Test
    fun doesNotExportKeyOrPrepareWhenNativeSessionNoLongerMatchesMetadata() {
        val membership = membership()
        val sessionId = "1234567890abcdef"
        val distributionId = OlmGroupSessionKeyDistributionId.create(
            membership = membership,
            senderUserId = "alice",
            senderDeviceId = "alice-phone",
            megolmSessionId = sessionId,
        )
        val metadata = OlmGroupKeyDistributionRecord(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            distributionId = distributionId,
            groupId = membership.groupId,
            membershipEpoch = membership.epoch,
            megolmSessionId = sessionId,
            targetDevices = listOf("alice" to "alice-tablet", "bob" to "bob-phone"),
        ).toEntity()
        var exported = false
        var prepared = false
        val runner = OlmGroupKeyDistributionRecoveryRunner(
            metadataStore = FakeMetadataStore(metadata),
            recoveryStateReader = FakeStateReader(
                OlmGroupKeyDistributionRecoveryState(
                    queuedTargets = emptySet(),
                    acknowledgedTargets = emptySet(),
                    failedTargets = emptySet(),
                ),
            ),
            membershipForGroup = { membership },
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            ensureOutboundSession = {
                OutboundMegolmSessionInfo("fedcba0987654321", membership.groupId, membership.epoch, created = true)
            },
            exportOutboundSessionKey = {
                exported = true
                ByteArray(32)
            },
            prepareDistribution = { _, _, _, _, _, _ ->
                prepared = true
                error("Must not prepare a rotated session")
            },
            resumeDistribution = { _, _ -> error("Must not enqueue a rotated session") },
        )

        val result = kotlinx.coroutines.runBlocking { runner.run() }

        assertEquals(1, result.distributionsScanned)
        assertEquals(1, result.distributionsSkipped)
        assertEquals(0, result.deliveriesQueued)
        assertFalse(exported)
        assertFalse(prepared)
    }

    @Test
    fun isolatesOneDistributionPreparationFailureAndReportsItForRetry() {
        val membership = membership()
        val sessionId = "1234567890abcdef"
        val distributionId = OlmGroupSessionKeyDistributionId.create(
            membership = membership,
            senderUserId = "alice",
            senderDeviceId = "alice-phone",
            megolmSessionId = sessionId,
        )
        val metadata = OlmGroupKeyDistributionRecord(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            distributionId = distributionId,
            groupId = membership.groupId,
            membershipEpoch = membership.epoch,
            megolmSessionId = sessionId,
            targetDevices = listOf("alice" to "alice-tablet", "bob" to "bob-phone"),
        ).toEntity()
        var failureReported = false
        val runner = OlmGroupKeyDistributionRecoveryRunner(
            metadataStore = FakeMetadataStore(metadata),
            recoveryStateReader = FakeStateReader(
                OlmGroupKeyDistributionRecoveryState(
                    queuedTargets = emptySet(),
                    acknowledgedTargets = emptySet(),
                    failedTargets = emptySet(),
                ),
            ),
            membershipForGroup = { membership },
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            ensureOutboundSession = {
                OutboundMegolmSessionInfo(sessionId, membership.groupId, membership.epoch, created = false)
            },
            exportOutboundSessionKey = { ByteArray(32) { 4 } },
            prepareDistribution = { _, _, _, _, _, _ ->
                throw IllegalStateException("temporary directory failure")
            },
            resumeDistribution = { _, _ -> error("Must not enqueue failed preparation") },
            onDistributionFailure = { failedMetadata, error ->
                assertEquals(metadata.distributionId, failedMetadata.distributionId)
                assertEquals("temporary directory failure", error.message)
                failureReported = true
            },
        )

        val result = kotlinx.coroutines.runBlocking { runner.run() }

        assertEquals(1, result.distributionsScanned)
        assertEquals(0, result.distributionsSkipped)
        assertEquals(1, result.distributionsFailed)
        assertEquals(0, result.deliveriesQueued)
        assertTrue(failureReported)
    }

    private fun membership() = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = 7L,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private class FakeMetadataStore(
        private val metadata: OlmGroupKeyDistributionEntity,
    ) : OlmGroupKeyDistributionMetadataStore {
        override fun persistIfAbsent(record: OlmGroupKeyDistributionRecord) = Unit
        override fun get(ownerUserId: String, senderDeviceId: String, distributionId: String) =
            metadata.takeIf {
                it.ownerUserId == ownerUserId &&
                    it.senderDeviceId == senderDeviceId &&
                    it.distributionId == distributionId
            }
        override fun getAllForDevice(ownerUserId: String, senderDeviceId: String) =
            listOf(metadata).filter {
                it.ownerUserId == ownerUserId && it.senderDeviceId == senderDeviceId
            }
    }

    private class FakeStateReader(
        private val state: OlmGroupKeyDistributionRecoveryState,
    ) : OlmGroupKeyDistributionRecoveryStateReader {
        override fun read(metadata: OlmGroupKeyDistributionEntity) = state
    }
}
