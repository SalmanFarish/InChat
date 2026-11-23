package com.example.inchat.crypto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Statuses for the isolated encrypted direct-message delivery path only. */
internal enum class EncryptedMessageDeliveryStatus {
    NOT_TRACKED,
    WAITING,
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
}

/**
 * Pure projection from metadata-only Room state to the encrypted message status.
 * A recipient-device acknowledgement takes precedence over retries still pending
 * for the recipient's other devices.
 */
internal fun resolveEncryptedMessageDeliveryStatus(
    queueStatuses: List<String>,
    recipientAcknowledged: Boolean,
): EncryptedMessageDeliveryStatus {
    if (recipientAcknowledged) return EncryptedMessageDeliveryStatus.DELIVERED
    val statuses = queueStatuses.toSet()
    return when {
        OlmDeliveryStatus.UPLOADED.name in statuses -> EncryptedMessageDeliveryStatus.SENT
        OlmDeliveryStatus.IN_FLIGHT.name in statuses -> EncryptedMessageDeliveryStatus.SENDING
        OlmDeliveryStatus.QUEUED.name in statuses -> EncryptedMessageDeliveryStatus.WAITING
        OlmDeliveryStatus.FAILED.name in statuses -> EncryptedMessageDeliveryStatus.FAILED
        else -> EncryptedMessageDeliveryStatus.NOT_TRACKED
    }
}

/**
 * Combines the scoped ciphertext-outbox lifecycle with durable recipient-device
 * acknowledgements. This repository is not connected to ChatRepository and must
 * only be used by an encrypted-message surface.
 */
internal class EncryptedMessageDeliveryStatusRepository(
    private val queueDao: OlmDeliveryQueueDao,
    private val receiptDao: OlmDeliveryReceiptDao,
) {
    fun observe(
        ownerUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
        messageId: String,
    ): Flow<EncryptedMessageDeliveryStatus> {
        require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
        require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }
        require(recipientUserId.matches(USER_ID_PATTERN)) { "Recipient user ID has an invalid format" }
        require(ownerUserId != recipientUserId) { "Encrypted direct-message status requires another account" }
        require(messageId.matches(MESSAGE_ID_PATTERN)) { "Message ID has an invalid format" }

        return combine(
            queueDao.observeDeliveryStatuses(
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = recipientUserId,
                messageId = messageId,
            ),
            receiptDao.observeHasRecipientAcknowledgement(
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = recipientUserId,
                messageId = messageId,
            ),
            ::resolveEncryptedMessageDeliveryStatus,
        ).distinctUntilChanged()
    }

    /**
     * Observe real encrypted delivery state for every message in one direct chat.
     * This also supports history synced from another sender device: such a message
     * may have a durable commit/receipt here without being in this device's outbox.
     */
    fun observeConversation(
        ownerUserId: String,
        recipientUserId: String,
        conversationId: String,
    ): Flow<Map<String, EncryptedMessageDeliveryStatus>> {
        require(USER_ID_PATTERN.matches(ownerUserId)) {
            "Owner user ID has an invalid format"
        }
        require(USER_ID_PATTERN.matches(recipientUserId)) {
            "Recipient user ID has an invalid format"
        }
        require(ownerUserId != recipientUserId) {
            "Encrypted direct-message status requires another account"
        }
        require(
            conversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(
                ownerUserId,
                recipientUserId,
            )
        ) {
            "Encrypted delivery status conversation does not match its participants"
        }

        return combine(
            queueDao.observeConversationDeliveryStatuses(
                ownerUserId = ownerUserId,
                recipientUserId = recipientUserId,
                conversationId = conversationId,
            ),
            receiptDao.observeDeliveredMessageIdsForConversation(
                ownerUserId = ownerUserId,
                recipientUserId = recipientUserId,
                conversationId = conversationId,
            ),
        ) { queueRows, deliveredMessageIds ->
            val deliveredIds = deliveredMessageIds.toSet()
            buildMap<String, EncryptedMessageDeliveryStatus> {
                queueRows.groupBy { it.messageId }.forEach { (messageId, rows) ->
                    put(
                        messageId,
                        resolveEncryptedMessageDeliveryStatus(
                            queueStatuses = rows.map { it.status },
                            recipientAcknowledged = messageId in deliveredIds,
                        ),
                    )
                }
                // Acked outbox rows are deleted after the receipt is durable.
                deliveredIds.forEach { messageId ->
                    put(messageId, EncryptedMessageDeliveryStatus.DELIVERED)
                }
            }
        }.distinctUntilChanged()
    }

    private companion object {
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
