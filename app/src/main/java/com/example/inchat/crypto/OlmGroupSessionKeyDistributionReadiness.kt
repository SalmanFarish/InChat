package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/** Metadata-only capability proving one exact group/session key distribution is ready for use. */
internal data class OlmGroupKeyDistributionReadinessToken(
    val ownerUserId: String,
    val senderDeviceId: String,
    val distributionId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val megolmSessionId: String,
    val targetDevices: Set<Pair<String, String>>,
)

/** Result suitable for a send preflight before the native Megolm ratchet is advanced. */
internal sealed class OlmGroupKeyDistributionReadinessState {
    data class Missing(val distributionId: String) : OlmGroupKeyDistributionReadinessState()
    data class AwaitingAcknowledgements(
        val distributionId: String,
        val pendingTargets: Set<Pair<String, String>>,
    ) : OlmGroupKeyDistributionReadinessState()
    data class Ready(val token: OlmGroupKeyDistributionReadinessToken) : OlmGroupKeyDistributionReadinessState()
}

/** Guards group ciphertext enqueue until its exact Megolm key has reached targets. */
internal fun interface OlmGroupKeyDistributionReadinessVerifier {
    fun requireReady(
        ownerUserId: String,
        senderDeviceId: String,
        currentMembership: GroupCryptoMembershipSnapshot,
        keyDistribution: OlmGroupKeyDistributionReadinessToken,
        messageDistribution: PreparedOlmGroupMessageDistribution,
    )
}

/**
 * Requires the immutable Room distribution record and the existing delivery
 * receipt ledger to agree before every remote target may receive Megolm content.
 * The metadata ledger contains identities only; it never stores session keys.
 */
internal class RoomOlmGroupKeyDistributionReadiness(
    private val deliveryReceipts: OlmDeliveryReceiptDao,
    private val metadataStore: OlmGroupKeyDistributionMetadataStore,
) : OlmGroupKeyDistributionReadinessVerifier {

    /** Preflight for callers that must wait before advancing the Megolm ratchet. */
    fun evaluate(
        ownerUserId: String,
        senderDeviceId: String,
        currentMembership: GroupCryptoMembershipSnapshot,
        megolmSessionId: String,
    ): OlmGroupKeyDistributionReadinessState {
        require(currentMembership.isValid() && ownerUserId in currentMembership.memberUserIds) {
            "Current group membership snapshot is invalid for key readiness"
        }
        require(senderDeviceId.matches(DEVICE_ID)) {
            "Group key readiness sender device ID is invalid"
        }
        val distributionId = OlmGroupSessionKeyDistributionId.create(
            membership = currentMembership,
            senderUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            megolmSessionId = megolmSessionId,
        )
        val metadata = metadataStore.get(ownerUserId, senderDeviceId, distributionId)
            ?: return OlmGroupKeyDistributionReadinessState.Missing(distributionId)
        metadata.requireValidForRecovery()
        require(
            metadata.ownerUserId == ownerUserId &&
                metadata.senderDeviceId == senderDeviceId &&
                metadata.distributionId == distributionId &&
                metadata.groupId == currentMembership.groupId &&
                metadata.membershipEpoch == currentMembership.epoch &&
                metadata.megolmSessionId == megolmSessionId
        ) {
            "Persisted group-key distribution does not match the current sender session"
        }

        val targets = metadata.targetDevicePairs().toSet()
        require(targets.all { (userId, deviceId) ->
            userId in currentMembership.memberUserIds &&
                deviceId.matches(DEVICE_ID) &&
                !(userId == ownerUserId && deviceId == senderDeviceId)
        }) {
            "Persisted group-key targets contain a non-member or invalid device"
        }
        val remoteMembers = currentMembership.memberUserIds - ownerUserId
        require(remoteMembers.all { memberId -> targets.any { it.first == memberId } }) {
            "Persisted group-key target set omits a current remote member"
        }

        val acknowledged = deliveryReceipts.getAcknowledgedTargetIdentitiesForDistribution(
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            distributionId = distributionId,
        ).mapTo(mutableSetOf(), ::decodeTargetIdentity)
        require(acknowledged.all { it in targets }) {
            "Group-key acknowledgement ledger contains a target outside immutable metadata"
        }

        // Sender-owned companion devices synchronize independently. Every
        // remote target must acknowledge this exact distribution before use.
        val remoteTargets = targets.filterTo(mutableSetOf()) { it.first != ownerUserId }
        val pending = remoteTargets - acknowledged
        return if (pending.isEmpty()) {
            OlmGroupKeyDistributionReadinessState.Ready(
                OlmGroupKeyDistributionReadinessToken(
                    ownerUserId = ownerUserId,
                    senderDeviceId = senderDeviceId,
                    distributionId = distributionId,
                    groupId = metadata.groupId,
                    membershipEpoch = metadata.membershipEpoch,
                    megolmSessionId = metadata.megolmSessionId,
                    targetDevices = targets,
                ),
            )
        } else {
            OlmGroupKeyDistributionReadinessState.AwaitingAcknowledgements(
                distributionId = distributionId,
                pendingTargets = pending,
            )
        }
    }

    /**
     * Must run before preparing a new Megolm message. The returned token proves
     * that the full immutable key target set is acknowledged, so callers can
     * avoid advancing the sender ratchet while a group member is still waiting.
     */
    fun requireAcknowledged(
        ownerUserId: String,
        senderDeviceId: String,
        currentMembership: GroupCryptoMembershipSnapshot,
        megolmSessionId: String,
    ): OlmGroupKeyDistributionReadinessToken =
        (evaluate(ownerUserId, senderDeviceId, currentMembership, megolmSessionId)
            as? OlmGroupKeyDistributionReadinessState.Ready)?.token
            ?: throw IllegalArgumentException(
                "Megolm group key distribution has not been acknowledged by every remote target",
            )

    override fun requireReady(
        ownerUserId: String,
        senderDeviceId: String,
        currentMembership: GroupCryptoMembershipSnapshot,
        keyDistribution: OlmGroupKeyDistributionReadinessToken,
        messageDistribution: PreparedOlmGroupMessageDistribution,
    ) {
        require(currentMembership.isValid() && ownerUserId in currentMembership.memberUserIds) {
            "Current group membership snapshot is invalid for key readiness"
        }
        require(
            messageDistribution.groupId == currentMembership.groupId &&
                messageDistribution.membershipEpoch == currentMembership.epoch &&
                keyDistribution.groupId == currentMembership.groupId &&
                keyDistribution.membershipEpoch == currentMembership.epoch &&
                keyDistribution.megolmSessionId == messageDistribution.megolmSessionId &&
                keyDistribution.ownerUserId == ownerUserId &&
                keyDistribution.senderDeviceId == senderDeviceId
        ) {
            "Group membership or key-distribution identity changed before outbox commit"
        }
        val latest = requireAcknowledged(
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            currentMembership = currentMembership,
            megolmSessionId = keyDistribution.megolmSessionId,
        )
        require(latest == keyDistribution) {
            "Group-key readiness token no longer matches durable metadata or acknowledgements"
        }
        val messageTargets = messageDistribution.deliveries.map {
            it.recipientUserId to it.recipientDeviceId
        }
        require(messageTargets.isNotEmpty() &&
            messageTargets.distinct().size == messageTargets.size &&
            keyDistribution.targetDevices == messageTargets.toSet()
        ) {
            "Trusted group devices changed; a fresh Megolm session key distribution is required"
        }
    }

    private fun decodeTargetIdentity(encoded: String): Pair<String, String> {
        val separator = encoded.indexOf('/')
        require(separator > 0 && separator == encoded.lastIndexOf('/') && separator < encoded.lastIndex) {
            "Acknowledgement ledger contains a malformed target identity"
        }
        val userId = encoded.substring(0, separator)
        val deviceId = encoded.substring(separator + 1)
        require(USER_ID.matches(userId) && DEVICE_ID.matches(deviceId)) {
            "Acknowledgement ledger contains an invalid target identity"
        }
        return userId to deviceId
    }

    private companion object {
        val USER_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * Performs the sender-side gate before Megolm ratchet advancement. This ensures
 * that the acknowledged key target set is still exactly the set of SAS-trusted
 * devices that would receive the next group message.
 */
internal class RoomOlmGroupMessageSendPreflight(
    private val readiness: RoomOlmGroupKeyDistributionReadiness,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val currentTrustedTargets: suspend (GroupCryptoMembershipSnapshot) -> Set<Pair<String, String>>,
) {
    suspend fun requireReady(
        membership: GroupCryptoMembershipSnapshot,
        token: OlmGroupKeyDistributionReadinessToken,
    ) {
        require(membership.isValid()) {
            "Authoritative group membership snapshot is invalid"
        }
        val ownerUid = authenticatedUserId()
        val senderDevice = localDeviceId()
        val latestToken = readiness.requireAcknowledged(
            ownerUserId = ownerUid,
            senderDeviceId = senderDevice,
            currentMembership = membership,
            megolmSessionId = token.megolmSessionId,
        )
        require(latestToken == token) {
            "Group-key readiness token changed before message staging"
        }
        val currentTargets = currentTrustedTargets(membership)
        require(authenticatedUserId() == ownerUid && localDeviceId() == senderDevice) {
            "Authenticated group sender identity changed during trusted-device lookup"
        }
        require(currentTargets.isNotEmpty() && currentTargets == token.targetDevices) {
            "Trusted group devices changed; a fresh Megolm session key distribution is required"
        }
    }
}
