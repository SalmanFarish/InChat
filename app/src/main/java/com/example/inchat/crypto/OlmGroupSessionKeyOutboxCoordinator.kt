package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/**
 * Persists per-device encrypted Megolm key distributions in the existing
 * account/device-scoped Olm outbox. The key itself is only inside each Olm
 * envelope; this coordinator never writes it to Firebase or Room plaintext.
 */
internal class OlmGroupSessionKeyOutboxCoordinator(
    private val queue: OlmDeliveryQueue,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val scheduleDelivery: (ownerUserId: String, senderDeviceId: String) -> Unit,
) {
    fun enqueue(
        currentMembership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupKeyDistribution,
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
            "Group membership changed after key distribution preparation"
        }
        require(distribution.distributionId.matches(DISTRIBUTION_ID)) {
            "Group key distribution ID is invalid"
        }
        require(distribution.deliveries.isNotEmpty()) {
            "No SAS-trusted group device is available for key distribution"
        }
        require(distribution.deliveries.size <= MAX_DELIVERIES) {
            "Group key distribution fan-out exceeds the device delivery limit"
        }
        require(distribution.deliveries.all { delivery ->
            delivery.recipientUserId in currentMembership.memberUserIds &&
                delivery.recipientDeviceId.matches(DEVICE_ID) &&
                delivery.sessionId.isNotBlank() &&
                delivery.sessionId.length <= MAX_SESSION_ID_LENGTH &&
                delivery.envelope.isNotBlank()
        }) {
            "Prepared group-key delivery contains an invalid or non-member target"
        }
        require(distribution.deliveries.map {
            "${it.recipientUserId}\u0000${it.recipientDeviceId}"
        }.distinct().size == distribution.deliveries.size) {
            "Group key distribution contains duplicate recipient devices"
        }
        require(distribution.deliveries.none {
            it.recipientUserId == ownerUid && it.recipientDeviceId == deviceId
        }) {
            "A device cannot distribute a group key to itself"
        }

        val inserted = queue.enqueue(
            ownerUserId = ownerUid,
            senderDeviceId = deviceId,
            messageId = distribution.distributionId,
            prepared = distribution.deliveries.map { delivery ->
                PreparedOlmDelivery(
                    recipientUserId = delivery.recipientUserId,
                    recipientDeviceId = delivery.recipientDeviceId,
                    sessionId = delivery.sessionId,
                    envelope = delivery.envelope,
                )
            },
        )
        // Unique WorkManager scheduling repairs the DB-commit/scheduler crash window.
        scheduleDelivery(ownerUid, deviceId)
        return inserted
    }

    private companion object {
        const val MAX_DELIVERIES = 500
        const val MAX_SESSION_ID_LENGTH = 128
        val DISTRIBUTION_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
