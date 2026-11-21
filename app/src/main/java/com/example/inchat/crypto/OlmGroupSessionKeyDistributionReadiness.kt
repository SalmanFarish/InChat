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
        keyDistribution: PreparedOlmGroupKeyDistribution,
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
        keyDistribution: PreparedOlmGroupKeyDistribution,
    ): OlmGroupKeyDistributionReadinessToken {
        require(currentMembership.isValid() && ownerUserId in currentMembership.memberUserIds) {
            "Current group membership snapshot is invalid for key readiness"
        }
        require(keyDistribution.groupId == currentMembership.groupId &&
            keyDistribution.membershipEpoch == currentMembership.epoch) {
            "Group membership changed after key distribution preparation"
        }
        val state = evaluate(
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            currentMembership = currentMembership,
            megolmSessionId = keyDistribution.megolmSessionId,
        )
        val token = (state as? OlmGroupKeyDistributionReadinessState.Ready)?.token
            ?: throw IllegalArgumentException(
                "Megolm group key distribution has not been acknowledged by every remote target",
            )
        require(keyDistribution.distributionId == token.distributionId) {
            "Prepared group-key distribution ID does not match the persisted ready distribution"
        }
        val declaredTargets = keyDistribution.targetDevices
        require(declaredTargets.isNotEmpty() &&
            declaredTargets.size == declaredTargets.distinct().size &&
            declaredTargets.toSet() == token.targetDevices
        ) {
            "Prepared group-key distribution does not describe its complete immutable target set"
        }
        val preparedTargets = keyDistribution.deliveries.map {
            it.recipientUserId to it.recipientDeviceId
        }
        require(preparedTargets.isNotEmpty() &&
            preparedTargets.distinct().size == preparedTargets.size &&
            preparedTargets.all { it in token.targetDevices }
        ) {
            "Prepared group-key envelopes contain duplicate or unexpected targets"
        }
        return token
    }

    override fun requireReady(
        ownerUserId: String,
        senderDeviceId: String,
        currentMembership: GroupCryptoMembershipSnapshot,
        keyDistribution: PreparedOlmGroupKeyDistribution,
        messageDistribution: PreparedOlmGroupMessageDistribution,
    ) {
        require(messageDistribution.groupId == currentMembership.groupId &&
            messageDistribution.membershipEpoch == currentMembership.epoch &&
            keyDistribution.megolmSessionId == messageDistribution.megolmSessionId) {
            "Group membership changed after the key or message distribution was prepared"
        }
        val token = requireAcknowledged(
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            currentMembership = currentMembership,
            keyDistribution = keyDistribution,
        )
        val messageTargets = messageDistribution.deliveries.map {
            it.recipientUserId to it.recipientDeviceId
        }
        require(messageTargets.isNotEmpty() &&
            messageTargets.distinct().size == messageTargets.size &&
            token.targetDevices.containsAll(messageTargets)
        ) {
            "A group message cannot be sent to a device that was not given this Megolm session key"
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
