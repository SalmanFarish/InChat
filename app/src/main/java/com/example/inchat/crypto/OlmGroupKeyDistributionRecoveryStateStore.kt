package com.example.inchat.crypto

/**
 * One bounded recovery snapshot for a persisted distribution. Only routing
 * identities/statuses are read; encrypted envelopes never leave the queue DAO.
 */
internal data class OlmGroupKeyDistributionRecoveryState(
    val queuedTargets: Set<Pair<String, String>>,
    val acknowledgedTargets: Set<Pair<String, String>>,
    val failedTargets: Set<Pair<String, String>>,
)

/** Reads and validates the durable Room state consumed by the pure recovery planner. */
internal class RoomOlmGroupKeyDistributionRecoveryStateStore(
    private val queueDao: OlmDeliveryQueueDao,
    private val receiptDao: OlmDeliveryReceiptDao,
) {
    fun read(metadata: OlmGroupKeyDistributionEntity): OlmGroupKeyDistributionRecoveryState {
        metadata.requireValidForRecovery()
        val queueRows = queueDao.getTargetStatesForDistribution(
            ownerUserId = metadata.ownerUserId,
            senderDeviceId = metadata.senderDeviceId,
            distributionId = metadata.distributionId,
        )
        val queueTargets = mutableSetOf<Pair<String, String>>()
        val failedTargets = mutableSetOf<Pair<String, String>>()
        queueRows.forEach { row ->
            val target = row.recipientUserId to row.recipientDeviceId
            require(queueTargets.add(target)) {
                "Outbox contains duplicate target rows for one group-key distribution"
            }
            when (row.status) {
                "QUEUED", "IN_FLIGHT", "UPLOADED" -> Unit
                "FAILED" -> failedTargets += target
                "ACKNOWLEDGED" -> {
                    // ACK rows are normally removed only after a durable receipt.
                    // Treat a retained ACK row as accounted for, but the receipt
                    // ledger remains the authoritative acknowledgement source.
                }
                else -> throw IllegalArgumentException("Outbox contains an unknown delivery status")
            }
        }

        val acknowledgedTargets = receiptDao
            .getAcknowledgedTargetIdentitiesForDistribution(
                ownerUserId = metadata.ownerUserId,
                senderDeviceId = metadata.senderDeviceId,
                distributionId = metadata.distributionId,
            )
            .mapTo(mutableSetOf(), ::decodeTargetIdentity)

        return OlmGroupKeyDistributionRecoveryState(
            queuedTargets = queueTargets - failedTargets,
            acknowledgedTargets = acknowledgedTargets,
            failedTargets = failedTargets,
        )
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
