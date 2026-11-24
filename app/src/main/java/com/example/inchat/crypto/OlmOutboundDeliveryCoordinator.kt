package com.example.inchat.crypto

/**
 * Builds and durably hands off one isolated direct-text message to every
 * recipient device and every other published device owned by the sender.
 *
 * This coordinator deliberately has no dependency on ChatRepository,
 * GroupChatRepository, the ordinary Firebase chats tree, or message UI state.
 * Version-2 authenticated payloads bind the direct conversation ID; group delivery must
 * wait for a separately authenticated conversation schema.
 */
internal class OlmOutboundDeliveryCoordinator(
    private val preparer: OlmFirstMessagePreparer,
    private val queue: OlmDeliveryQueue,
    private val trustVerifier: OlmDeviceTrustVerifier,
    private val scheduleDelivery: (ownerUserId: String, senderDeviceId: String) -> Unit,
    private val senderMessageRepository: EncryptedOlmMessageRepository? = null,
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
        conversationId: String,
        sentAtMillis: Long,
        text: String,
        recipientDevices: List<PublicDeviceKeyBundle>,
        senderDevices: List<PublicDeviceKeyBundle>,
    ): Int {
        require(recipientUserId != senderUserId) {
            "A direct message recipient must be another account"
        }
        require(
            conversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(
                senderUserId,
                recipientUserId,
            )
        ) {
            "Conversation ID does not match the direct-message participants"
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
        // Each target is checked against this installation's encrypted identity pins.
        // First-use pins are automatic; only a changed key requires a new SAS check.
        targets.forEach { target ->
            check(
                trustVerifier.isTrustedForMessaging(
                    peerUserId = target.userId,
                    peerDeviceId = target.device.deviceId,
                    peerEd25519IdentityKey = target.device.ed25519IdentityKey,
                )
            ) {
                "Device ${target.userId}/${target.device.deviceId} has a changed identity; verify its safety number before messaging"
            }
        }

        // Validate every per-device payload before the first one-time prekey
        // is claimed. A malformed later target must not consume earlier keys.
        val payloads = targets.map { target ->
            OlmAuthenticatedMessagePayload(
                messageId = messageId,
                conversationId = conversationId,
                conversationPeerUserId = recipientUserId,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = target.userId,
                recipientDeviceId = target.device.deviceId,
                sentAtMillis = sentAtMillis,
                text = text,
            ).also { OlmAuthenticatedMessagePayloadCodec.encode(it) }
        }

        val directCommit = DirectOutgoingMessageCommit(
            ownerUserId = senderUserId,
            conversationId = conversationId,
            conversationPeerUserId = recipientUserId,
            messageId = messageId,
            senderDeviceId = senderDeviceId,
            sentAtMillis = sentAtMillis,
            recipientDeviceCount = payloads.size,
        )
        // The local timeline copy is encrypted with the same Keystore-backed
        // AES-GCM vault as inbound records. Its recipient-device binding is
        // deterministic so retries cannot change immutable local metadata.
        val senderLocalMessage = senderMessageRepository?.let {
            DecryptedOlmMessage(
                ownerUserId = senderUserId,
                conversationId = conversationId,
                messageId = messageId,
                conversationPeerUserId = recipientUserId,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = recipientUserId,
                recipientDeviceId = recipientDevices.minBy { device -> device.deviceId }.deviceId,
                sentAtMillis = sentAtMillis,
                text = text,
            )
        }
        // Check the permanent commit ledger before claiming any one-time prekeys.
        // A committed replay only repairs the WorkManager scheduling window.
        if (queue.hasDirectMessageCommit(directCommit)) {
            senderLocalMessage?.let { senderMessageRepository?.verifyOutgoingMessage(it) }
            scheduleDelivery(senderUserId, senderDeviceId)
            return 0
        }

        // Prepare the encrypted timeline row before the transaction, but do not
        // persist it independently. The Room store commits it with the complete
        // ciphertext fan-out and permanent message marker in one transaction.
        val encryptedSenderLocalCopy = senderLocalMessage?.let {
            senderMessageRepository?.prepareOutgoingMessage(it)
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
            conversationId = conversationId,
            conversationPeerUserId = recipientUserId,
            directMessageCommit = directCommit,
            directMessageLocalCopy = encryptedSenderLocalCopy,
        )
        senderLocalMessage?.let { senderMessageRepository?.verifyOutgoingMessage(it) }

        // WorkManager uses unique account/device work names, so replaying this
        // request is safe and can restore a missed immediate delivery trigger.
        scheduleDelivery(senderUserId, senderDeviceId)
        return inserted
    }
}
