package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64

/**
 * Orchestrates creation and durable distribution of the current outbound
 * Megolm session key. It does not report the key as ready: message sending must
 * still pass RoomOlmGroupKeyDistributionReadiness after device acknowledgements.
 *
 * Membership is read both before key export and again after per-device Olm
 * envelope preparation. Any member/role snapshot change aborts before the outbox is committed.
 */
internal class MegolmGroupSessionKeyDistributor(
    private val membershipForGroup: suspend (groupId: String) -> GroupCryptoMembershipSnapshot?,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val currentTrustedTargetDevices: suspend (GroupCryptoMembershipSnapshot) -> Set<Pair<String, String>>,
    private val ensureOutboundSession: (
        GroupCryptoMembershipSnapshot,
        Set<Pair<String, String>>,
    ) -> OutboundMegolmSessionInfo,
    private val exportOutboundSessionKey: (
        GroupCryptoMembershipSnapshot,
        sessionId: String,
    ) -> ByteArray,
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
    private val resumeDistribution: (
        membership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupKeyDistribution,
    ) -> Int,
) {
    suspend fun distribute(
        groupId: String,
        distributionId: String,
        issuedAtMillis: Long,
    ): MegolmGroupKeyDistributionResult =
        prepareAndPersist(groupId, distributionId, issuedAtMillis, resuming = false)

    /** Re-export the existing native session key and rebuild per-device envelopes for a saved distribution. */
    suspend fun resume(
        groupId: String,
        distributionId: String,
        issuedAtMillis: Long,
    ): MegolmGroupKeyDistributionResult =
        prepareAndPersist(groupId, distributionId, issuedAtMillis, resuming = true)

    private suspend fun prepareAndPersist(
        groupId: String,
        distributionId: String,
        issuedAtMillis: Long,
        resuming: Boolean,
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

        val trustedTargets = currentTrustedTargetDevices(initial)
        require(trustedTargets.isNotEmpty() && trustedTargets.size <= MAX_GROUP_TARGET_DEVICES) {
            "Current SAS-trusted group device roster has an invalid size"
        }
        require(trustedTargets.all { (targetUserId, targetDeviceId) ->
            targetUserId in initial.memberUserIds &&
                targetUserId.matches(USER_ID) &&
                targetDeviceId.matches(DEVICE_ID) &&
                !(targetUserId == ownerUid && targetDeviceId == senderDevice)
        }) {
            "Current SAS-trusted group device roster contains an invalid target"
        }
        require((initial.memberUserIds - ownerUid).all { memberId ->
            trustedTargets.any { it.first == memberId }
        }) {
            "Every current remote group member must have a trusted device"
        }

        val session = ensureOutboundSession(initial, trustedTargets)
        require(
            session.groupId == groupId &&
                session.membershipEpoch == initial.epoch &&
                session.sessionId.matches(SESSION_ID)
        ) {
            "Native Megolm sender session does not match the current group epoch and device roster"
        }

        val expectedDistributionId = OlmGroupSessionKeyDistributionId.create(
            membership = initial,
            senderUserId = ownerUid,
            senderDeviceId = senderDevice,
            megolmSessionId = session.sessionId,
        )
        require(distributionId == expectedDistributionId) {
            "Group-key distribution ID must be stable for this exact sender session and membership epoch"
        }

        val keyBytes = exportOutboundSessionKey(initial, session.sessionId)
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
                prepared.deliveries.isNotEmpty() &&
                prepared.targetDevices.size == trustedTargets.size &&
                prepared.targetDevices.toSet() == trustedTargets
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
                latest.members == initial.members &&
                authenticatedUserId() == ownerUid &&
                localDeviceId() == senderDevice &&
                currentTrustedTargetDevices(latest) == trustedTargets
        ) {
            "Group membership or local identity changed during key distribution"
        }

        val inserted = if (resuming) {
            resumeDistribution(latest, prepared)
        } else {
            enqueueDistribution(latest, prepared)
        }
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
        const val MAX_GROUP_TARGET_DEVICES = 256
        val USER_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
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
