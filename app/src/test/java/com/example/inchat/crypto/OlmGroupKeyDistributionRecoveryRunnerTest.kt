package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
            existingOutboundSession = { snapshot, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                OutboundMegolmSessionInfo(expectedSessionId, snapshot.groupId, snapshot.epoch, created = false)
            },
            exportOutboundSessionKey = { _, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                keyBytes
            },
            prepareDistribution = { id, snapshot, session, key, issuedAt, selected, expected ->
                assertEquals(distributionId, id)
                assertEquals(membership, snapshot)
                assertEquals(sessionId, session)
                assertTrue(key.isNotBlank())
                assertEquals(1234L, issuedAt)
                preparedTargets = selected
                assertEquals(metadata.targetDevicePairs().toSet(), expected)
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
            existingOutboundSession = { snapshot, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                OutboundMegolmSessionInfo(expectedSessionId, snapshot.groupId, snapshot.epoch, created = false)
            },
            exportOutboundSessionKey = { _, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                ByteArray(32) { 9 }
            },
            prepareDistribution = { id, snapshot, session, _, _, _, expected ->
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
    fun accountSwitchDuringInitialMembershipLookupStopsBeforeReadingMegolmSessionKey() = runBlocking {
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
            targetDevices = listOf("bob" to "bob-phone"),
        ).toEntity()
        var activeUserId = "alice"
        var sessionReads = 0
        var keyExports = 0
        var preparations = 0
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
                // Simulate Firebase auth changing while the membership lookup suspends.
                activeUserId = "mallory"
                membership
            },
            authenticatedUserId = { activeUserId },
            localDeviceId = { "alice-phone" },
            existingOutboundSession = { snapshot, expectedSessionId ->
                sessionReads++
                OutboundMegolmSessionInfo(
                    expectedSessionId,
                    snapshot.groupId,
                    snapshot.epoch,
                    created = false,
                )
            },
            exportOutboundSessionKey = { _, _ ->
                keyExports++
                ByteArray(32) { 4 }
            },
            prepareDistribution = { _, _, _, _, _, _, _ ->
                preparations++
                error("Must not prepare after the account has changed")
            },
            resumeDistribution = { _, _ ->
                resumed = true
                1
            },
            onDistributionFailure = { _, _ -> failureReported = true },
        )

        val failure = runCatching { runner.run() }.exceptionOrNull()

        assertTrue("An account switch during lookup must abort recovery", failure is IllegalArgumentException)
        assertEquals(0, sessionReads)
        assertEquals(0, keyExports)
        assertEquals(0, preparations)
        assertTrue(!resumed)
        assertTrue(!failureReported)
    }

    @Test
    fun accountSwitchDuringOneDistributionAbortsRecoveryInsteadOfBeingCountedAsItemFailure() {
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
        var authReads = 0
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
            authenticatedUserId = {
                authReads += 1
                if (authReads == 1) "alice" else "mallory"
            },
            localDeviceId = { "alice-phone" },
            existingOutboundSession = { snapshot, expectedSessionId ->
                OutboundMegolmSessionInfo(expectedSessionId, snapshot.groupId, snapshot.epoch, created = false)
            },
            exportOutboundSessionKey = { _, _ -> ByteArray(32) { 3 } },
            prepareDistribution = { id, snapshot, session, _, _, _, _ ->
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
            resumeDistribution = { _, _ -> error("Must not resume after account switch") },
            onDistributionFailure = { _, _ -> failureReported = true },
        )

        val failure = runCatching {
            kotlinx.coroutines.runBlocking { runner.run() }
        }.exceptionOrNull()

        assertTrue("Account switch must abort the recovery pass", failure is IllegalArgumentException)
        assertFalse("Account switch must not be reported as a retryable row failure", failureReported)
    }

    @Test
    fun doesNotResumeWhenPreparedDeviceLosesTrustBeforeEnqueue() {
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
            membershipForGroup = { membership },
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            existingOutboundSession = { snapshot, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                OutboundMegolmSessionInfo(expectedSessionId, snapshot.groupId, snapshot.epoch, created = false)
            },
            exportOutboundSessionKey = { _, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                ByteArray(32) { 5 }
            },
            prepareDistribution = { id, snapshot, session, _, _, _, expected ->
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
            validatePreparedTargets = { false },
            onDistributionFailure = { _, _ -> failureReported = true },
        )

        val result = kotlinx.coroutines.runBlocking { runner.run() }

        assertEquals(1, result.distributionsScanned)
        assertEquals(0, result.distributionsFailed)
        assertEquals(1, result.distributionsSkipped)
        assertEquals(0, result.deliveriesQueued)
        assertFalse(resumed)
        assertFalse(failureReported)
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
            existingOutboundSession = { snapshot, _ ->
                OutboundMegolmSessionInfo("fedcba0987654321", snapshot.groupId, snapshot.epoch, created = false)
            },
            exportOutboundSessionKey = { _, _ ->
                exported = true
                ByteArray(32)
            },
            prepareDistribution = { _, _, _, _, _, _, expected ->
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
            existingOutboundSession = { snapshot, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                OutboundMegolmSessionInfo(expectedSessionId, snapshot.groupId, snapshot.epoch, created = false)
            },
            exportOutboundSessionKey = { _, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                ByteArray(32) { 4 }
            },
            prepareDistribution = { _, _, _, _, _, _, expected ->
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

    @Test
    fun missingNativeSessionIsSkippedWithoutExportOrPreparation() {
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
            existingOutboundSession = { _, _ -> null },
            exportOutboundSessionKey = { _, _ ->
                exported = true
                ByteArray(32)
            },
            prepareDistribution = { _, _, _, _, _, _, expected ->
                prepared = true
                error("Must not prepare a missing session")
            },
            resumeDistribution = { _, _ -> error("Must not enqueue a missing session") },
        )

        val result = kotlinx.coroutines.runBlocking { runner.run() }

        assertEquals(1, result.distributionsScanned)
        assertEquals(1, result.distributionsSkipped)
        assertEquals(0, result.distributionsFailed)
        assertEquals(0, result.deliveriesQueued)
        assertFalse(exported)
        assertFalse(prepared)
    }

    @Test
    fun overlappingRecoveryRunnersDoNotPrepareTheSameMissingKeyDistributionTwice() = runBlocking {
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
            targetDevices = listOf("bob" to "bob-phone"),
        ).toEntity()
        val metadataStore = FakeMetadataStore(metadata)
        var recoveryState = OlmGroupKeyDistributionRecoveryState(
            queuedTargets = emptySet(),
            acknowledgedTargets = emptySet(),
            failedTargets = emptySet(),
        )
        val stateReader = object : OlmGroupKeyDistributionRecoveryStateReader {
            override fun read(metadata: OlmGroupKeyDistributionEntity) = recoveryState
        }
        val membershipLookupEntered = CompletableDeferred<Unit>()
        val releaseMembershipLookup = CompletableDeferred<Unit>()
        val membershipLookups = AtomicInteger()
        val keyExports = AtomicInteger()
        val preparations = AtomicInteger()
        val resumes = AtomicInteger()

        fun runner() = OlmGroupKeyDistributionRecoveryRunner(
            metadataStore = metadataStore,
            recoveryStateReader = stateReader,
            membershipForGroup = {
                if (membershipLookups.incrementAndGet() == 1) {
                    membershipLookupEntered.complete(Unit)
                    releaseMembershipLookup.await()
                }
                membership
            },
            authenticatedUserId = { "alice" },
            localDeviceId = { "alice-phone" },
            existingOutboundSession = { snapshot, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                OutboundMegolmSessionInfo(expectedSessionId, snapshot.groupId, snapshot.epoch, created = false)
            },
            exportOutboundSessionKey = { _, expectedSessionId ->
                assertEquals(sessionId, expectedSessionId)
                keyExports.incrementAndGet()
                ByteArray(32) { 6 }
            },
            prepareDistribution = { id, snapshot, session, _, issuedAt, selected, expected ->
                preparations.incrementAndGet()
                assertEquals(distributionId, id)
                assertEquals(0L, issuedAt)
                assertEquals(setOf("bob" to "bob-phone"), selected)
                assertEquals(setOf("bob" to "bob-phone"), expected)
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
                            envelope = "encrypted-envelope",
                        ),
                    ),
                    skippedUntrustedDeviceCount = 0,
                    targetDevices = listOf("bob" to "bob-phone"),
                )
            },
            resumeDistribution = { _, prepared ->
                assertEquals(1, prepared.deliveries.size)
                resumes.incrementAndGet()
                recoveryState = OlmGroupKeyDistributionRecoveryState(
                    queuedTargets = setOf("bob" to "bob-phone"),
                    acknowledgedTargets = emptySet(),
                    failedTargets = emptySet(),
                )
                1
            },
            nowMillis = { 0L },
        )

        val first = async { runner().run() }
        membershipLookupEntered.await()
        val secondStarted = CompletableDeferred<Unit>()
        val second = async {
            secondStarted.complete(Unit)
            runner().run()
        }
        secondStarted.await()
        yield()

        // The first pass is paused after its durable missing-target snapshot.
        // The second runner must wait rather than exporting/claiming/preparing
        // the same group session key concurrently.
        assertEquals(1, membershipLookups.get())
        assertEquals(1, keyExports.get())
        assertEquals(1, preparations.get())

        releaseMembershipLookup.complete(Unit)
        val firstResult = first.await()
        val secondResult = second.await()

        assertEquals(1, firstResult.deliveriesQueued)
        assertEquals(1, secondResult.distributionsSkipped)
        assertEquals(0, secondResult.deliveriesQueued)
        // The first pass performs its initial and final membership reads;
        // the serialized second pass exits from the durable recovery snapshot.
        assertEquals(2, membershipLookups.get())
        assertEquals(1, keyExports.get())
        assertEquals(1, preparations.get())
        assertEquals(1, resumes.get())
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
