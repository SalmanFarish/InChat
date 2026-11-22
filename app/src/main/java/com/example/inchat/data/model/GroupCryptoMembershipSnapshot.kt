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

    companion object {
        /**
         * Builds a snapshot only when every server member entry is well formed.
         * Silently dropping malformed children could authorize against an
         * incomplete member map, so any invalid entry invalidates the snapshot.
         */
        fun fromEntries(
            groupId: String,
            epoch: Long?,
            entries: List<Pair<String?, String?>>,
        ): GroupCryptoMembershipSnapshot? {
            if (epoch == null || entries.any { (userId, role) ->
                    userId.isNullOrBlank() || role == null || role !in ALLOWED_ROLES
                }
            ) {
                return null
            }
            val members = entries.associate { (userId, role) ->
                requireNotNull(userId) to requireNotNull(role)
            }
            if (members.size != entries.size) return null
            return GroupCryptoMembershipSnapshot(groupId, epoch, members)
                .takeIf { it.isValid() }
        }

        const val MIN_MEMBERS = 2
        const val MAX_MEMBERS = 50
        private val ALLOWED_ROLES = setOf("admin", "member")
    }
}
