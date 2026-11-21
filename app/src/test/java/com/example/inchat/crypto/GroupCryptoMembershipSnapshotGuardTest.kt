package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCryptoMembershipSnapshotGuardTest {
    @Test
    fun acceptsAnUnchangedAuthoritativeSnapshot() {
        val expected = membership(epoch = 8L)
        val latest = membership(epoch = 8L)

        val current = requireUnchangedGroupCryptoMembershipSnapshot(expected, latest)

        assertSame(latest, current)
        assertEquals(expected.members, current.members)
    }

    @Test
    fun rejectsAnAdvancedMembershipEpoch() {
        assertRejected {
            requireUnchangedGroupCryptoMembershipSnapshot(
                expected = membership(epoch = 8L),
                latest = membership(epoch = 9L),
            )
        }
    }

    @Test
    fun rejectsMemberOrRoleChangesEvenWhenTheEpochIsUnchanged() {
        val changedMembers = membership(epoch = 8L).copy(
            members = mapOf("alice" to "admin", "carol" to "member"),
        )
        val changedRole = membership(epoch = 8L).copy(
            members = mapOf("alice" to "member", "bob" to "member"),
        )

        assertRejected {
            requireUnchangedGroupCryptoMembershipSnapshot(membership(8L), changedMembers)
        }
        assertRejected {
            requireUnchangedGroupCryptoMembershipSnapshot(membership(8L), changedRole)
        }
    }

    @Test
    fun rejectsMissingOrInvalidLatestSnapshot() {
        assertRejected {
            requireUnchangedGroupCryptoMembershipSnapshot(membership(8L), null)
        }
        assertRejected {
            requireUnchangedGroupCryptoMembershipSnapshot(
                membership(8L),
                membership(8L).copy(members = mapOf("alice" to "admin")),
            )
        }
    }

    private fun membership(epoch: Long) = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = epoch,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("A changed or invalid membership snapshot must fail closed", rejected)
    }
}
