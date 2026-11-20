package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

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
 * Uses the existing metadata-only Olm delivery receipt table to make sure every
 * remote device that will receive a Megolm ciphertext has acknowledged this
 * exact epoch's key distribution. No new relay or database table is required.
 */
internal class RoomOlmGroupKeyDistributionReadiness(
    private val deliveryReceipts: OlmDeliveryReceiptDao,
) : OlmGroupKeyDistributionReadinessVerifier {

    override fun requireReady(
        ownerUserId: String,
        senderDeviceId: String,
        currentMembership: GroupCryptoMembershipSnapshot,
        keyDistribution: PreparedOlmGroupKeyDistribution,
        messageDistribution: PreparedOlmGroupMessageDistribution,
    ) {
        require(currentMembership.isValid() && ownerUserId in currentMembership.memberUserIds) {
            "Current group membership snapshot is invalid for key readiness"
        }
        require(keyDistribution.groupId == currentMembership.groupId &&
            keyDistribution.membershipEpoch == currentMembership.epoch &&
            messageDistribution.groupId == currentMembership.groupId &&
            messageDistribution.membershipEpoch == currentMembership.epoch &&
            keyDistribution.megolmSessionId == messageDistribution.megolmSessionId
        ) {
            "Group membership changed after the key or message distribution was prepared"
        }
        require(keyDistribution.distributionId.matches(DISTRIBUTION_ID)) {
            "Group key distribution ID is invalid"
        }

        val keyTargets = keyDistribution.deliveries
            .map { it.recipientUserId to it.recipientDeviceId }
            .toSet()
        val messageTargets = messageDistribution.deliveries
            .map { it.recipientUserId to it.recipientDeviceId }
            .toSet()
        require(keyTargets.size == keyDistribution.deliveries.size) {
            "Group key distribution contains duplicate device targets"
        }
        require(messageTargets.size == messageDistribution.deliveries.size) {
            "Group message distribution contains duplicate device targets"
        }
        require(messageTargets.isNotEmpty() && keyTargets.containsAll(messageTargets)) {
            "A group message cannot be sent to a device that was not given this Megolm session key"
        }

        // Sender-owned companion devices are deliberately not represented in
        // recipient-account receipts. Their reliable local sync copy can be
        // retried independently. Every other member device must acknowledge the
        // exact key distribution before any group ciphertext targets it.
        val remoteKeyTargets = keyDistribution.deliveries
            .filter { it.recipientUserId != ownerUserId }
            .groupBy { it.recipientUserId }
        require(remoteKeyTargets.keys == currentMembership.memberUserIds - ownerUserId) {
            "Group key distribution does not cover every other current member"
        }
        remoteKeyTargets.forEach { (recipientUserId, deliveries) ->
            val acknowledged = deliveryReceipts.getAcknowledgedRecipientDeviceIds(
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = recipientUserId,
                messageId = keyDistribution.distributionId,
            ).toSet()
            val expected = deliveries.map { it.recipientDeviceId }.toSet()
            require(acknowledged.containsAll(expected)) {
                "Megolm key distribution has not been acknowledged by every target device for $recipientUserId"
            }
        }
    }

    private companion object {
        val DISTRIBUTION_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
