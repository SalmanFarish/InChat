package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64

/**
 * Orchestrates creation and durable distribution of the current outbound
 * Megolm session key. It does not report the key as ready: message sending must
 * still pass RoomOlmGroupKeyDistributionReadiness after device acknowledgements.
 *
 * Membership is read both before key export and again after per-device Olm
 * envelope preparation. A changed epoch aborts before the outbox is committed.
 */
internal class MegolmGroupSessionKeyDistributor(
    private val membershipForGroup: suspend (groupId: String) -> GroupCryptoMembershipSnapshot?,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val ensureOutboundSession: (GroupCryptoMembershipSnapshot) -> OutboundMegolmSessionInfo,
    private val exportOutboundSessionKey: (GroupCryptoMembershipSnapshot) -> ByteArray,
    private val prepareDistribution: suspend (
        distributionId: String,
        membership: GroupCryptoMembershipSnapshot,
        megolmSessionId: String,
        sessionKey: String,
        issuedAtMillis: Long,
    ) -> PreparedOlmGroupKeyDistribution,
    private val enqueueDistribution: (
        membership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupKeyDistribution,
    ) -> Int,
) {
    suspend fun distribute(
        groupId: String,
        distributionId: String,
        issuedAtMillis: Long,
    ): MegolmGroupKeyDistributionResult {
        require(groupId.matches(ID)) { "Group ID is invalid" }
        require(distributionId.matches(ID)) { "Group-key distribution ID is invalid" }
        require(issuedAtMillis >= 0L) { "Distribution timestamp cannot be negative" }

        val ownerUid = authenticatedUserId()
        val senderDevice = localDeviceId()
        val initial = requireNotNull(membershipForGroup(groupId)) {
            "Current E2EE group membership snapshot is unavailable"
        }
        require(initial.isValid() && initial.groupId == groupId) {
            "Current E2EE group membership snapshot is invalid"
        }
        require(ownerUid in initial.memberUserIds) {
            "Authenticated account is not a current group member"
        }

        val session = ensureOutboundSession(initial)
        require(
            session.groupId == groupId &&
                session.membershipEpoch == initial.epoch &&
                session.sessionId.matches(SESSION_ID)
        ) {
            "Native Megolm sender session does not match the current group epoch"
        }

        val keyBytes = exportOutboundSessionKey(initial)
        val encodedKey = try {
            require(keyBytes.size in MIN_KEY_BYTES..MAX_KEY_BYTES) {
                "Native Megolm session key has an invalid size"
            }
            Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes)
        } finally {
            keyBytes.fill(0)
        }

        val prepared = prepareDistribution(
            distributionId,
            initial,
            session.sessionId,
            encodedKey,
            issuedAtMillis,
        )
        require(
            prepared.distributionId == distributionId &&
                prepared.groupId == groupId &&
                prepared.membershipEpoch == initial.epoch &&
                prepared.megolmSessionId == session.sessionId &&
                prepared.deliveries.isNotEmpty()
        ) {
            "Prepared group-key fan-out does not match the current Megolm session"
        }

        // Re-read the authoritative membership after all directory/trust and
        // Olm session work. Do not enqueue a key to a stale membership epoch.
        val latest = requireNotNull(membershipForGroup(groupId)) {
            "Group membership disappeared before key distribution commit"
        }
        require(
            latest.isValid() &&
                latest.groupId == groupId &&
                latest.epoch == initial.epoch &&
                latest.memberUserIds == initial.memberUserIds &&
                authenticatedUserId() == ownerUid &&
                localDeviceId() == senderDevice
        ) {
            "Group membership or local identity changed during key distribution"
        }

        val inserted = enqueueDistribution(latest, prepared)
        return MegolmGroupKeyDistributionResult(
            distributionId = distributionId,
            groupId = groupId,
            membershipEpoch = initial.epoch,
            megolmSessionId = session.sessionId,
            recipientDeviceCount = prepared.deliveries.size,
            newlyQueuedDeliveryCount = inserted,
            skippedUntrustedDeviceCount = prepared.skippedUntrustedDeviceCount,
        )
    }

    private companion object {
        const val MIN_KEY_BYTES = 32
        const val MAX_KEY_BYTES = 1024
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
        val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
    }
}

internal data class MegolmGroupKeyDistributionResult(
    val distributionId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val megolmSessionId: String,
    val recipientDeviceCount: Int,
    val newlyQueuedDeliveryCount: Int,
    val skippedUntrustedDeviceCount: Int,
)
