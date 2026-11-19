package com.example.inchat.crypto

/**
 * Builds and durably hands off one isolated direct-text message to every
 * recipient device and every other published device owned by the sender.
 *
 * This coordinator deliberately has no dependency on ChatRepository,
 * GroupChatRepository, the ordinary Firebase chats tree, or message UI state.
 * Version-1 authenticated payloads are direct-text only; group delivery must
 * wait for a separately authenticated conversation schema.
 */
internal class OlmOutboundDeliveryCoordinator(
    private val preparer: OlmFirstMessagePreparer,
    private val queue: OlmDeliveryQueue,
    private val scheduleDelivery: (ownerUserId: String, senderDeviceId: String) -> Unit,
) {
    /**
     * Prepare every independently encrypted copy before inserting any outbox
     * row. The queue store inserts the complete batch in one transaction.
     *
     * Scheduling is requested even when the batch was already present. This
     * makes an idempotent producer replay repair the narrow crash window where
     * the Room transaction committed but WorkManager scheduling did not.
     */
    suspend fun enqueueDirectTextMessage(
        senderUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
        messageId: String,
        sentAtMillis: Long,
        text: String,
        recipientDevices: List<PublicDeviceKeyBundle>,
        senderDevices: List<PublicDeviceKeyBundle>,
    ): Int {
        require(recipientUserId != senderUserId) {
            "A direct message recipient must be another account"
        }
        require(recipientDevices.isNotEmpty()) {
            "The recipient has no published devices"
        }
        require(senderDevices.any { it.deviceId == senderDeviceId }) {
            "The sending device is not present in the published device directory"
        }

        val targets = OlmFanOutPlanner.plan(
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            recipientDevicesByUser = mapOf(recipientUserId to recipientDevices),
            senderDevices = senderDevices,
        )
        require(targets.any { it.userId == recipientUserId }) {
            "No recipient-device delivery targets are available"
        }

        // Validate every per-device payload before the first one-time prekey
        // is claimed. A malformed later target must not consume earlier keys.
        val payloads = targets.map { target ->
            OlmAuthenticatedMessagePayload(
                messageId = messageId,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = target.userId,
                recipientDeviceId = target.device.deviceId,
                sentAtMillis = sentAtMillis,
                text = text,
            ).also(OlmAuthenticatedMessagePayloadCodec::encode)
        }

        // Do not enqueue a partial fan-out if any target cannot be prepared.
        val prepared = payloads.map { payload ->
            preparer.prepareFirstMessage(payload).also { delivery ->
                require(delivery.recipientUserId == payload.recipientUserId &&
                    delivery.recipientDeviceId == payload.recipientDeviceId
                ) {
                    "Prepared encrypted delivery does not match its authenticated target"
                }
            }
        }

        val inserted = queue.enqueue(
            ownerUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            messageId = messageId,
            prepared = prepared,
        )

        // WorkManager uses unique account/device work names, so replaying this
        // request is safe and can restore a missed immediate delivery trigger.
        scheduleDelivery(senderUserId, senderDeviceId)
        return inserted
    }
}
