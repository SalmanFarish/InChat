package com.example.inchat.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCryptoMembershipSnapshotTest {
    @Test
    fun acceptsConsistentServerAuthoredMembershipSnapshot() {
        val snapshot = GroupCryptoMembershipSnapshot(
            groupId = "group-1",
            epoch = 3L,
            members = mapOf(
                "admin-user" to "admin",
                "member-user" to "member",
            ),
        )

        assertTrue(snapshot.isValid())
        assertTrue(snapshot.memberUserIds.containsAll(setOf("admin-user", "member-user")))
    }

    @Test
    fun rejectsEmptyGroupIdOrNonPositiveEpoch() {
        assertFalse(snapshot(groupId = "", epoch = 1L).isValid())
        assertFalse(snapshot(epoch = 0L).isValid())
        assertFalse(snapshot(epoch = -1L).isValid())
    }

    @Test
    fun rejectsMembershipOutsideSupportedBounds() {
        assertFalse(snapshot(members = emptyMap()).isValid())
        assertFalse(
            snapshot(
                members = (1..51).associate { "member-$it" to "member" },
            ).isValid(),
        )
    }

    @Test
    fun rejectsUnknownRolesAndBlankMemberIds() {
        assertFalse(
            snapshot(
                members = mapOf(
                    "admin-user" to "admin",
                    "member-user" to "owner",
                ),
            ).isValid(),
        )
        assertFalse(
            snapshot(
                members = mapOf(
                    "admin-user" to "admin",
                    "" to "member",
                ),
            ).isValid(),
        )
    }

    @Test
    fun rejectsSnapshotWhenAnyMemberEntryIsMalformedInsteadOfDroppingIt() {
        val validEntries = listOf(
            "admin-user" to "admin",
            "member-user" to "member",
        )
        assertTrue(
            GroupCryptoMembershipSnapshot.fromEntries(
                groupId = "group-1",
                epoch = 3L,
                entries = validEntries,
            )?.isValid() == true,
        )

        assertFalse(
            GroupCryptoMembershipSnapshot.fromEntries(
                groupId = "group-1",
                epoch = 3L,
                entries = validEntries + (null to "member"),
            )?.isValid() == true,
        )
        assertFalse(
            GroupCryptoMembershipSnapshot.fromEntries(
                groupId = "group-1",
                epoch = 3L,
                entries = validEntries + ("unexpected-user" to "owner"),
            )?.isValid() == true,
        )
        assertFalse(
            GroupCryptoMembershipSnapshot.fromEntries(
                groupId = "group-1",
                epoch = 3L,
                entries = validEntries + ("admin-user" to "member"),
            )?.isValid() == true,
        )
        assertFalse(
            GroupCryptoMembershipSnapshot.fromEntries(
                groupId = "group-1",
                epoch = 3L,
                entries = validEntries + ("unknown-user" to null),
            )?.isValid() == true,
        )
        assertFalse(
            GroupCryptoMembershipSnapshot.fromEntries(
                groupId = "group-1",
                epoch = null,
                entries = validEntries,
            )?.isValid() == true,
        )
    }

    private fun snapshot(
        groupId: String = "group-1",
        epoch: Long = 1L,
        members: Map<String, String> = mapOf(
            "admin-user" to "admin",
            "member-user" to "member",
        ),
    ) = GroupCryptoMembershipSnapshot(
        groupId = groupId,
        epoch = epoch,
        members = members,
    )
}
