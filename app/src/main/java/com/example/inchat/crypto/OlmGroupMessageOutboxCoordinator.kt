package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/**
 * Durably enqueues a fully prepared Megolm/Olm fan-out using the existing
 * account/device-scoped ciphertext outbox and worker. No group-specific relay
 * or paid infrastructure is introduced.
 */
internal class OlmGroupMessageOutboxCoordinator(
    private val queue: OlmDeliveryQueue,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val scheduleDelivery: (ownerUserId: String, senderDeviceId: String) -> Unit,
) {
    fun enqueue(
        currentMembership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupMessageDistribution,
    ): Int {
        require(currentMembership.isValid()) {
            "Authoritative group membership snapshot is invalid"
        }
        val ownerUid = authenticatedUserId()
        val deviceId = localDeviceId()
        require(ownerUid in currentMembership.memberUserIds) {
            "Authenticated sender is not a current group member"
        }
        require(distribution.groupId == currentMembership.groupId &&
            distribution.membershipEpoch == currentMembership.epoch
        ) {
            "Group membership changed after encrypted fan-out preparation"
        }
        require(distribution.messageId.matches(MESSAGE_ID)) {
            "Group message ID is invalid"
        }
        require(distribution.deliveries.isNotEmpty()) {
            "No trusted recipient devices are available for this group message"
        }
        require(distribution.deliveries.size <= MAX_DELIVERIES) {
            "Group message fan-out exceeds the device delivery limit"
        }
        require(distribution.deliveries.all { delivery ->
            delivery.recipientUserId in currentMembership.memberUserIds &&
                delivery.recipientDeviceId.matches(DEVICE_ID) &&
                delivery.sessionId.isNotBlank() &&
                delivery.sessionId.length <= MAX_SESSION_ID_LENGTH &&
                delivery.envelope.isNotBlank()
        }) {
            "Prepared group delivery contains an invalid or non-member target"
        }
        require(distribution.deliveries.map {
            "${it.recipientUserId}\u0000${it.recipientDeviceId}"
        }.distinct().size == distribution.deliveries.size) {
            "Group message fan-out contains duplicate recipient devices"
        }
        require(distribution.deliveries.none {
            it.recipientUserId == ownerUid && it.recipientDeviceId == deviceId
        }) {
            "A group message cannot be delivered back to the sending device"
        }

        // The generic outbox validates every outer Olm envelope's sender,
        // recipient, session and canonical encoding before inserting the batch.
        val inserted = queue.enqueue(
            ownerUserId = ownerUid,
            senderDeviceId = deviceId,
            messageId = distribution.messageId,
            prepared = distribution.deliveries.map { delivery ->
                PreparedOlmDelivery(
                    recipientUserId = delivery.recipientUserId,
                    recipientDeviceId = delivery.recipientDeviceId,
                    sessionId = delivery.sessionId,
                    envelope = delivery.envelope,
                )
            },
        )
        // Unique WorkManager scheduling makes this safe to repeat after a crash
        // between the Room commit and scheduling the worker.
        scheduleDelivery(ownerUid, deviceId)
        return inserted
    }

    private companion object {
        const val MAX_DELIVERIES = 500
        const val MAX_SESSION_ID_LENGTH = 128
        val MESSAGE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
