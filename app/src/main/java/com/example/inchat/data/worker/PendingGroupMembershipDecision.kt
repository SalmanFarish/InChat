package com.example.inchat.data.worker

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/**
 * Distinguishes temporarily unavailable membership data from a durable loss of
 * group membership. Pending ciphertext must never be sent against an invalid
 * roster, but a transient read failure must not silently strand the local intent.
 */
internal enum class PendingGroupMembershipDecision {
    READY,
    RETRY,
    RETIRED,
}

internal fun pendingGroupMembershipDecision(
    snapshot: GroupCryptoMembershipSnapshot?,
    expectedGroupId: String,
    ownerUserId: String,
): PendingGroupMembershipDecision {
    if (snapshot == null || !snapshot.isValid() || snapshot.groupId != expectedGroupId) {
        return PendingGroupMembershipDecision.RETRY
    }
    return if (ownerUserId in snapshot.memberUserIds) {
        PendingGroupMembershipDecision.READY
    } else {
        PendingGroupMembershipDecision.RETIRED
    }
}
