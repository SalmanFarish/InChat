package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MegolmGroupSessionKeyDistributorTest {
    @Test
    fun groupDeviceRosterFingerprintIsStableAcrossSetIterationOrder() {
        val first = linkedSetOf("bob" to "bob-phone", "alice" to "alice-tablet")
        val second = linkedSetOf("alice" to "alice-tablet", "bob" to "bob-phone")
        assertEquals(
            GroupDeviceRosterFingerprint.create(membership(9L), first),
            GroupDeviceRosterFingerprint.create(membership(9L), second),
        )
    }

    @Test
    fun groupDeviceRosterFingerprintChangesWhenTrustedDeviceSetChanges() {
        val oldRoster = setOf("bob" to "bob-phone")
        val newRoster = setOf("bob" to "bob-phone", "bob" to "bob-tablet")
        assertTrue(
            GroupDeviceRosterFingerprint.create(membership(9L), oldRoster) !=
                GroupDeviceRosterFingerprint.create(membership(9L), newRoster),
        )
    }

    @Test
    fun preparesAndQueuesExactCurrentSessionThenReturnsPendingAcknowledgementState() = runBlocking {
        val key = ByteArray(64) { (it + 1).toByte() }
        val expectedKey = key.copyOf()
        var enqueueCalls = 0
        val snapshots = mutableListOf(membership(9L), membership(9L))
        val distributor = distributor(
            keyBytes = key,
            snapshots = snapshots,
            enqueue = { current, distribution ->
                enqueueCalls++
                assertEquals(9L, current.epoch)
                assertEquals("session-id-123456", distribution.megolmSessionId)
                assertEquals(2, distribution.deliveries.size)
                2
            },
        )

        val result = distributor.distribute("group-1", distributionId(), 1234L)

        assertEquals(1, enqueueCalls)
        assertEquals(distributionId(), result.distributionId)
        assertEquals(9L, result.membershipEpoch)
        assertEquals(2, result.recipientDeviceCount)
        assertEquals(2, result.newlyQueuedDeliveryCount)
        assertEquals(1, result.skippedUntrustedDeviceCount)
        assertArrayEquals(ByteArray(64), key)
        assertArrayEquals(expectedKey, Base64.getUrlDecoder().decode(capturedSessionKey))
    }

    @Test
    fun membershipEpochChangeAfterPreparationPreventsOutboxCommit() = runBlocking {
        val key = ByteArray(64) { it.toByte() }
        var enqueueCalls = 0
        val distributor = distributor(
            keyBytes = key,
            snapshots = mutableListOf(membership(9L), membership(10L)),
            enqueue = { _, _ -> enqueueCalls++; 1 },
        )

        var rejected = false
        try {
            distributor.distribute("group-1", distributionId(), 1234L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(0, enqueueCalls)
        assertArrayEquals(ByteArray(64), key)
    }

    @Test
    fun roleMapChangeAtSameEpochPreventsKeyDistributionCommit() = runBlocking {
        val key = ByteArray(64) { 11 }
        var enqueueCalls = 0
        val changedRoles = membership(9L).copy(
            members = mapOf("alice" to "member", "bob" to "admin", "carol" to "member"),
        )
        val distributor = distributor(
            keyBytes = key,
            snapshots = mutableListOf(membership(9L), changedRoles),
            enqueue = { _, _ -> enqueueCalls++; 1 },
        )

        var rejected = false
        try {
            distributor.distribute("group-1", distributionId(), 1234L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(0, enqueueCalls)
        assertArrayEquals(ByteArray(64), key)
    }

    @Test
    fun preparedDistributionMustMatchNativeSessionAndCallerIdentity() = runBlocking {
        val key = ByteArray(64) { 7 }
        var enqueueCalls = 0
        val distributor = distributor(
            keyBytes = key,
            snapshots = mutableListOf(membership(9L), membership(9L)),
            enqueue = { _, _ -> enqueueCalls++; 1 },
            preparedSessionId = "different-session-123",
        )

        var rejected = false
        try {
            distributor.distribute("group-1", distributionId(), 1234L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(0, enqueueCalls)
        assertArrayEquals(ByteArray(64), key)
    }

    @Test
    fun resumeRepreparesSameSessionAndUsesRecoveryCoordinator() = runBlocking {
        val key = ByteArray(64) { (it + 3).toByte() }
        var enqueueCalls = 0
        var resumeCalls = 0
        val distributor = distributor(
            keyBytes = key,
            snapshots = mutableListOf(membership(9L), membership(9L)),
            enqueue = { _, _ -> enqueueCalls++; 99 },
            resume = { current, prepared ->
                resumeCalls++
                assertEquals(9L, current.epoch)
                assertEquals(distributionId(), prepared.distributionId)
                assertEquals("session-id-123456", prepared.megolmSessionId)
                1
            },
        )

        val result = distributor.resume("group-1", distributionId(), 1234L)

        assertEquals(0, enqueueCalls)
        assertEquals(1, resumeCalls)
        assertEquals(1, result.newlyQueuedDeliveryCount)
        assertArrayEquals(ByteArray(64), key)
    }

    @Test
    fun newGroupMessageRequiresActiveSessionForExactMembershipAndDeviceRoster() {
        val currentMembership = membership(9L)
        val targets = setOf("bob" to "bob-phone", "carol" to "carol-phone")
        val expectedId = "session-current"
        val matchingSession = OutboundMegolmSessionInfo(
            sessionId = expectedId,
            groupId = currentMembership.groupId,
            membershipEpoch = currentMembership.epoch,
            created = false,
            targetRosterFingerprint = GroupDeviceRosterFingerprint.create(currentMembership, targets),
        )
        assertTrue(
            isOutboundMegolmSessionReadyFor(
                matchingSession,
                currentMembership,
                targets,
                expectedId,
            ),
        )

        val changedRoles = currentMembership.copy(
            members = mapOf("alice" to "member", "bob" to "admin", "carol" to "member"),
        )
        assertFalse(
            isOutboundMegolmSessionReadyFor(
                matchingSession,
                changedRoles,
                targets,
                expectedId,
            ),
        )

        val archivedSession = matchingSession.copy(sessionId = "session-archived")
        assertFalse(
            isOutboundMegolmSessionReadyFor(
                archivedSession,
                currentMembership,
                targets,
                expectedId,
            ),
        )
    }

    @Test
    fun groupDeviceRosterFingerprintChangesWhenMembershipRolesChangeAtSameEpoch() {
        val currentMembership = membership(9L)
        val changedMembership = currentMembership.copy(
            members = mapOf("alice" to "member", "bob" to "admin", "carol" to "member"),
        )
        val targets = setOf("bob" to "bob-phone", "carol" to "carol-phone")

        assertTrue(
            GroupDeviceRosterFingerprint.create(currentMembership, targets) !=
                GroupDeviceRosterFingerprint.create(changedMembership, targets),
        )
    }

    private var capturedSessionKey: String = ""

    private fun distributor(
        keyBytes: ByteArray,
        snapshots: MutableList<GroupCryptoMembershipSnapshot>,
        enqueue: (GroupCryptoMembershipSnapshot, PreparedOlmGroupKeyDistribution) -> Int,
        resume: (GroupCryptoMembershipSnapshot, PreparedOlmGroupKeyDistribution) -> Int = enqueue,
        preparedSessionId: String = "session-id-123456",
    ) = MegolmGroupSessionKeyDistributor(
        membershipForGroup = { groupId ->
            assertEquals("group-1", groupId)
            if (snapshots.isEmpty()) null else snapshots.removeAt(0)
        },
        authenticatedUserId = { "alice" },
        localDeviceId = { "alice-phone" },
        currentTrustedTargetDevices = { membership ->
            assertEquals(9L, membership.epoch)
            setOf("bob" to "bob-phone", "carol" to "carol-phone")
        },
        ensureOutboundSession = { membership, targets ->
            assertEquals(9L, membership.epoch)
            assertEquals(setOf("bob" to "bob-phone", "carol" to "carol-phone"), targets)
            OutboundMegolmSessionInfo(
                sessionId = "session-id-123456",
                groupId = membership.groupId,
                membershipEpoch = membership.epoch,
                created = false,
                targetRosterFingerprint = GroupDeviceRosterFingerprint.create(membership, targets),
            )
        },
        exportOutboundSessionKey = { membership, sessionId ->
            assertEquals(9L, membership.epoch)
            assertEquals("session-id-123456", sessionId)
            keyBytes
        },
        prepareDistribution = { distributionKeyId, membership, sessionId, sessionKey, issuedAt ->
            capturedSessionKey = sessionKey
            assertEquals(distributionId(), distributionKeyId)
            assertEquals(9L, membership.epoch)
            assertEquals(1234L, issuedAt)
            PreparedOlmGroupKeyDistribution(
                distributionId = distributionKeyId,
                groupId = membership.groupId,
                membershipEpoch = membership.epoch,
                megolmSessionId = preparedSessionId,
                deliveries = listOf(
                    keyDelivery("bob", "bob-phone"),
                    keyDelivery("carol", "carol-phone"),
                ),
                skippedUntrustedDeviceCount = 1,
                targetDevices = listOf("bob" to "bob-phone", "carol" to "carol-phone"),
            ).also { assertEquals("session-id-123456", sessionId) }
        },
        enqueueDistribution = enqueue,
        resumeDistribution = resume,
    )

    private fun keyDelivery(userId: String, deviceId: String) = PreparedOlmGroupKeyDelivery(
        recipientUserId = userId,
        recipientDeviceId = deviceId,
        sessionId = "olm-session-$deviceId",
        envelope = "authenticated-olm-envelope",
    )

    private fun distributionId() = OlmGroupSessionKeyDistributionId.create(
        membership = membership(9L),
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        megolmSessionId = "session-id-123456",
    )

    private fun membership(epoch: Long) = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = epoch,
        members = mapOf("alice" to "admin", "bob" to "member", "carol" to "member"),
    )
}
