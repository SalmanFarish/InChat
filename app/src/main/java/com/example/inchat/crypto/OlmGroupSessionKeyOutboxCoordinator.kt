package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/**
 * Persists per-device encrypted Megolm key distributions in the existing
 * account/device-scoped Olm outbox. The key itself is only inside each Olm
 * envelope; this coordinator never writes it to Firebase or Room plaintext.
 */
internal class OlmGroupSessionKeyOutboxCoordinator(
    private val queue: OlmDeliveryQueue,
    private val metadataStore: OlmGroupKeyDistributionMetadataStore,
    private val deliveryReceipts: OlmDeliveryReceiptDao,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val scheduleDelivery: (ownerUserId: String, senderDeviceId: String) -> Unit,
    private val recoveryStateReader: OlmGroupKeyDistributionRecoveryStateReader? = null,
) {
    fun enqueue(
        currentMembership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupKeyDistribution,
    ): Int = enqueueInternal(currentMembership, distribution, requirePersistedMetadata = false)

    /**
     * Resume an interrupted distribution after its encrypted envelopes have been
     * prepared again for the original session and exact target devices. Stable
     * delivery IDs let the Room outbox retain completed rows and insert only gaps.
     */
    fun resume(
        currentMembership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupKeyDistribution,
    ): Int = enqueueInternal(currentMembership, distribution, requirePersistedMetadata = true)

    private fun enqueueInternal(
        currentMembership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupKeyDistribution,
        requirePersistedMetadata: Boolean,
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
        require(distribution.megolmSessionId.matches(SESSION_ID)) {
            "Group key distribution Megolm session ID is invalid"
        }
        val immutableTargets = distribution.targetDevices
        val immutableTargetSet = immutableTargets.toSet()
        val preparedTargetSet = distribution.deliveries.map {
            it.recipientUserId to it.recipientDeviceId
        }.toSet()
        require(immutableTargets.isNotEmpty() &&
            immutableTargets.size <= MAX_OLM_DEVICE_DELIVERY_TARGETS &&
            immutableTargetSet.size == immutableTargets.size
        ) {
            "Group-key immutable target set is empty, duplicated, or oversized"
        }
        require(immutableTargets.all { (userId, targetDeviceId) ->
            userId in currentMembership.memberUserIds &&
                targetDeviceId.matches(DEVICE_ID) &&
                !(userId == ownerUid && targetDeviceId == deviceId)
        }) {
            "Group-key immutable target set contains an invalid or non-member device"
        }
        require((currentMembership.memberUserIds - ownerUid).all { memberId ->
            immutableTargets.any { it.first == memberId }
        }) {
            "Group-key immutable target set omits a current member"
        }
        require(preparedTargetSet.isNotEmpty() &&
            preparedTargetSet.size == distribution.deliveries.size &&
            preparedTargetSet.all { it in immutableTargetSet }
        ) {
            "Prepared group-key envelopes contain duplicate or unexpected targets"
        }
        require(distribution.deliveries.all { delivery ->
            delivery.sessionId.isNotBlank() &&
                delivery.sessionId.length <= MAX_SESSION_ID_LENGTH &&
                delivery.envelope.isNotBlank()
        }) {
            "Prepared group-key delivery contains an invalid envelope"
        }
        if (!requirePersistedMetadata) {
            require(preparedTargetSet == immutableTargetSet) {
                "Initial group-key distribution must prepare every immutable target"
            }
        }

        val record = OlmGroupKeyDistributionRecord(
            ownerUserId = ownerUid,
            senderDeviceId = deviceId,
            distributionId = distribution.distributionId,
            groupId = distribution.groupId,
            membershipEpoch = distribution.membershipEpoch,
            megolmSessionId = distribution.megolmSessionId,
            targetDevices = immutableTargets,
        )
        if (requirePersistedMetadata) {
            val persisted = requireNotNull(
                metadataStore.get(ownerUid, deviceId, distribution.distributionId),
            ) { "Cannot resume a group-key distribution without its durable metadata record" }
            require(persisted == record.toEntity()) {
                "Prepared recovery envelopes do not match the persisted group-key distribution"
            }
            require(persisted.targetDevicePairs() == record.targetDevices.distinct().sortedWith(
                compareBy<Pair<String, String>> { it.first }.thenBy { it.second },
            )) { "Persisted recovery target set is inconsistent" }
            if (preparedTargetSet != immutableTargetSet) {
                val stateReader = requireNotNull(recoveryStateReader) {
                    "Partial group-key recovery requires a durable queue/acknowledgement state reader"
                }
                val state = stateReader.read(persisted)
                val recoveryPlan = OlmGroupKeyDistributionRecoveryPlanner.plan(
                    metadata = persisted,
                    queuedTargets = state.queuedTargets,
                    acknowledgedTargets = state.acknowledgedTargets,
                    failedTargets = state.failedTargets,
                )
                require(preparedTargetSet.all { it in recoveryPlan.missingTargets }) {
                    "Recovery envelopes may be prepared only for targets absent from queue and acknowledgement state"
                }
            }
        } else {
            // Persist non-secret identity and the complete target set before queue writes.
            metadataStore.persistIfAbsent(record)
        }

        // Ack receipts outlive queue rows. Do not resurrect remote or
        // sender-owned companion-device deliveries already acknowledged.
        val acknowledgedByRecipient = distribution.deliveries
            .groupBy { it.recipientUserId }
            .mapValues { (recipientUserId, _) ->
                deliveryReceipts.getAcknowledgedRecipientDeviceIds(
                    ownerUserId = ownerUid,
                    senderDeviceId = deviceId,
                    recipientUserId = recipientUserId,
                    messageId = distribution.distributionId,
                ).toSet()
            }
        val pendingDeliveries = distribution.deliveries.filter { delivery ->
            delivery.recipientDeviceId !in acknowledgedByRecipient[delivery.recipientUserId].orEmpty()
        }
        val inserted = if (pendingDeliveries.isEmpty()) 0 else queue.enqueue(
            ownerUserId = ownerUid,
            senderDeviceId = deviceId,
            messageId = distribution.distributionId,
            prepared = pendingDeliveries.map { delivery ->
                PreparedOlmDelivery(
                    recipientUserId = delivery.recipientUserId,
                    recipientDeviceId = delivery.recipientDeviceId,
                    sessionId = delivery.sessionId,
                    envelope = delivery.envelope,
                )
            },
        )
        if (pendingDeliveries.isNotEmpty()) scheduleDelivery(ownerUid, deviceId)
        return inserted
    }

    private companion object {
        const val MAX_SESSION_ID_LENGTH = 128
        val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
        val DISTRIBUTION_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
