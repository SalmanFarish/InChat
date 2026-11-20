package com.example.inchat.data.model

/**
 * One server-authored, internally consistent group membership view for E2EE.
 *
 * The Worker writes `epoch` and `members` together at
 * `chats/{groupId}/cryptoMembership`. A caller must use this snapshot as a
 * unit; it must not combine the legacy members listener with a separate epoch
 * listener when deciding which Megolm key recipients are authorized.
 */
data class GroupCryptoMembershipSnapshot(
    val groupId: String,
    val epoch: Long,
    val members: Map<String, String>,
) {
    val memberUserIds: Set<String>
        get() = members.keys

    fun isValid(): Boolean =
        groupId.isNotBlank() &&
            epoch > 0L &&
            members.size in MIN_MEMBERS..MAX_MEMBERS &&
            members.all { (userId, role) ->
                userId.isNotBlank() && role in ALLOWED_ROLES
            }

    private companion object {
        const val MIN_MEMBERS = 2
        const val MAX_MEMBERS = 50
        val ALLOWED_ROLES = setOf("admin", "member")
    }
}
