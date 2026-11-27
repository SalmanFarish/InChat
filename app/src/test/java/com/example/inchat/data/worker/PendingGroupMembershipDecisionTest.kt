package com.example.inchat.data.worker

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingGroupMembershipDecisionTest {
    private val valid = GroupCryptoMembershipSnapshot(
        groupId = "group_1",
        epoch = 1L,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    @Test
    fun unavailableOrInvalidMembershipRequestsAnotherRecoveryPass() {
        assertEquals(
            PendingGroupMembershipDecision.RETRY,
            pendingGroupMembershipDecision(null, "group_1", "alice"),
        )
        assertEquals(
            PendingGroupMembershipDecision.RETRY,
            pendingGroupMembershipDecision(valid.copy(epoch = 0L), "group_1", "alice"),
        )
        assertEquals(
            PendingGroupMembershipDecision.RETRY,
            pendingGroupMembershipDecision(valid, "different_group", "alice"),
        )
    }

    @Test
    fun currentMemberCanRetryButRemovedMemberIsNotAutomaticallySent() {
        assertEquals(
            PendingGroupMembershipDecision.READY,
            pendingGroupMembershipDecision(valid, "group_1", "alice"),
        )
        assertEquals(
            PendingGroupMembershipDecision.RETIRED,
            pendingGroupMembershipDecision(valid, "group_1", "charlie"),
        )
    }
}
