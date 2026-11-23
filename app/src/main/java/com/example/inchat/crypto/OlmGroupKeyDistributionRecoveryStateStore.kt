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

/** Read-only contract for the durable queue/acknowledgement snapshot. */
internal interface OlmGroupKeyDistributionRecoveryStateReader {
    fun read(metadata: OlmGroupKeyDistributionEntity): OlmGroupKeyDistributionRecoveryState
}

/** Reads and validates the durable Room state consumed by the pure recovery planner. */
internal class RoomOlmGroupKeyDistributionRecoveryStateStore(
    private val queueDao: OlmDeliveryQueueDao,
    private val receiptDao: OlmDeliveryReceiptDao,
) : OlmGroupKeyDistributionRecoveryStateReader {
    override fun read(metadata: OlmGroupKeyDistributionEntity): OlmGroupKeyDistributionRecoveryState {
        metadata.requireValidForRecovery()
        val queueRows = queueDao.getTargetStatesForDistribution(
            ownerUserId = metadata.ownerUserId,
            senderDeviceId = metadata.senderDeviceId,
            distributionId = metadata.distributionId,
        )
        val acknowledgedTargets = receiptDao
            .getAcknowledgedTargetIdentitiesForDistribution(
                ownerUserId = metadata.ownerUserId,
                senderDeviceId = metadata.senderDeviceId,
                distributionId = metadata.distributionId,
            )
            .mapTo(mutableSetOf(), ::decodeTargetIdentity)

        return OlmGroupKeyDistributionRecoveryStateReducer.reduce(
            queueRows = queueRows,
            acknowledgedTargets = acknowledgedTargets,
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


/**
 * Reconciles the queue snapshot with the durable acknowledgement ledger.
 * An ACKNOWLEDGED queue row without its receipt is inconsistent and must not
 * be treated as success or silently recreated as a new key delivery.
 */
internal object OlmGroupKeyDistributionRecoveryStateReducer {
    fun reduce(
        queueRows: List<OlmDeliveryQueueTargetState>,
        acknowledgedTargets: Set<Pair<String, String>>,
    ): OlmGroupKeyDistributionRecoveryState {
        val seenTargets = mutableSetOf<Pair<String, String>>()
        val queuedTargets = mutableSetOf<Pair<String, String>>()
        val failedTargets = mutableSetOf<Pair<String, String>>()

        queueRows.forEach { row ->
            val target = row.recipientUserId to row.recipientDeviceId
            require(seenTargets.add(target)) {
                "Outbox contains duplicate target rows for one group-key distribution"
            }
            when (row.status) {
                "QUEUED", "IN_FLIGHT", "UPLOADED" -> {
                    require(target !in acknowledgedTargets) {
                        "Outbox queue state conflicts with a durable acknowledgement receipt"
                    }
                    queuedTargets += target
                }
                "FAILED" -> {
                    require(target !in acknowledgedTargets) {
                        "Failed outbox state conflicts with a durable acknowledgement receipt"
                    }
                    failedTargets += target
                }
                "ACKNOWLEDGED" -> require(target in acknowledgedTargets) {
                    "Acknowledged outbox row is missing its durable receipt"
                }
                else -> throw IllegalArgumentException("Outbox contains an unknown delivery status")
            }
        }

        return OlmGroupKeyDistributionRecoveryState(
            queuedTargets = queuedTargets,
            acknowledgedTargets = acknowledgedTargets,
            failedTargets = failedTargets,
        )
    }
}
