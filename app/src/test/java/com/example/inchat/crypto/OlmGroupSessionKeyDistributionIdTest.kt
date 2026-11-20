package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupSessionKeyDistributionIdTest {
    @Test
    fun idIsStableAcrossRetriesAndBindsGroupEpochSenderDeviceAndSession() {
        val membership = snapshot("group-1", 7L)
        val first = OlmGroupSessionKeyDistributionId.create(
            membership,
            "alice",
            "alice-phone",
            "1234567890abcdef",
        )
        val retry = OlmGroupSessionKeyDistributionId.create(
            membership,
            "alice",
            "alice-phone",
            "1234567890abcdef",
        )

        assertEquals(first, retry)
        assertTrue(first.matches(Regex("[A-Fa-f0-9]{64}")))
        assertNotEquals(
            first,
            OlmGroupSessionKeyDistributionId.create(snapshot("group-1", 8L), "alice", "alice-phone", "1234567890abcdef"),
        )
        assertNotEquals(
            first,
            OlmGroupSessionKeyDistributionId.create(membership, "alice", "alice-tablet", "1234567890abcdef"),
        )
        assertNotEquals(
            first,
            OlmGroupSessionKeyDistributionId.create(membership, "alice", "alice-phone", "fedcba0987654321"),
        )
    }

    private fun snapshot(groupId: String, epoch: Long) = GroupCryptoMembershipSnapshot(
        groupId = groupId,
        epoch = epoch,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )
}
