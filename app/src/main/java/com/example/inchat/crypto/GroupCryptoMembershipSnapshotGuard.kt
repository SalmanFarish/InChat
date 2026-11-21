package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/**
 * Revalidates a server-authored group snapshot immediately before a native
 * Megolm operation. Epoch and the complete member/role map must remain stable.
 */
internal fun requireUnchangedGroupCryptoMembershipSnapshot(
    expected: GroupCryptoMembershipSnapshot,
    latest: GroupCryptoMembershipSnapshot?,
): GroupCryptoMembershipSnapshot {
    require(expected.isValid()) {
        "Previously validated E2EE membership snapshot is invalid"
    }
    val current = requireNotNull(latest) {
        "Current E2EE group membership snapshot disappeared"
    }
    require(
        current.isValid() &&
            current.groupId == expected.groupId &&
            current.epoch == expected.epoch &&
            current.members == expected.members
    ) {
        "E2EE group membership changed while validating the encrypted payload"
    }
    return current
}
