package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MegolmGroupSessionKeyDistributorTest {
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

    private var capturedSessionKey: String = ""

    private fun distributor(
        keyBytes: ByteArray,
        snapshots: MutableList<GroupCryptoMembershipSnapshot>,
        enqueue: (GroupCryptoMembershipSnapshot, PreparedOlmGroupKeyDistribution) -> Int,
        preparedSessionId: String = "session-id-123456",
    ) = MegolmGroupSessionKeyDistributor(
        membershipForGroup = { groupId ->
            assertEquals("group-1", groupId)
            if (snapshots.isEmpty()) null else snapshots.removeAt(0)
        },
        authenticatedUserId = { "alice" },
        localDeviceId = { "alice-phone" },
        ensureOutboundSession = { membership ->
            assertEquals(9L, membership.epoch)
            OutboundMegolmSessionInfo(
                sessionId = "session-id-123456",
                groupId = membership.groupId,
                membershipEpoch = membership.epoch,
                created = false,
            )
        },
        exportOutboundSessionKey = { keyBytes },
        prepareDistribution = { distributionId, membership, sessionId, sessionKey, issuedAt ->
            capturedSessionKey = sessionKey
            assertEquals(distributionId(), distributionId)
            assertEquals(9L, membership.epoch)
            assertEquals(1234L, issuedAt)
            PreparedOlmGroupKeyDistribution(
                distributionId = distributionId,
                groupId = membership.groupId,
                membershipEpoch = membership.epoch,
                megolmSessionId = preparedSessionId,
                deliveries = listOf(
                    keyDelivery("bob", "bob-phone"),
                    keyDelivery("carol", "carol-phone"),
                ),
                skippedUntrustedDeviceCount = 1,
            ).also { assertEquals("session-id-123456", sessionId) }
        },
        enqueueDistribution = enqueue,
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
