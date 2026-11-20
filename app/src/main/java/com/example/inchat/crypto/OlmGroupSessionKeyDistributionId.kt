package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.security.MessageDigest

/**
 * Stable per-sender-device Megolm key-distribution receipt ID.
 *
 * Regenerating per-device Olm envelopes after a process restart must preserve
 * the same distribution ID so the durable ACK ledger still identifies the
 * exact group/session key that was distributed.
 */
internal object OlmGroupSessionKeyDistributionId {
    fun create(
        membership: GroupCryptoMembershipSnapshot,
        senderUserId: String,
        senderDeviceId: String,
        megolmSessionId: String,
    ): String {
        require(membership.isValid()) {
            "Authoritative group membership snapshot is invalid"
        }
        require(senderUserId in membership.memberUserIds) {
            "Key distribution sender is not a current group member"
        }
        require(DEVICE_ID.matches(senderDeviceId)) {
            "Key distribution sender device ID is invalid"
        }
        require(SESSION_ID.matches(megolmSessionId)) {
            "Megolm session ID is invalid"
        }
        val canonical = listOf(
            CONTEXT,
            membership.groupId,
            membership.epoch.toString(),
            senderUserId,
            senderDeviceId,
            megolmSessionId,
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val CONTEXT = "INCHAT_MEGOLM_KEY_DISTRIBUTION_V1"
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
}
