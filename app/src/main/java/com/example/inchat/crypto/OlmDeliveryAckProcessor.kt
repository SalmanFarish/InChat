package com.example.inchat.crypto

/**
 * Applies sender-side relay acknowledgements only after matching the exact
 * immutable recipient-device delivery identity in the durable local outbox.
 *
 * The remote acknowledgement is removed before the local row. If the process
 * stops between those operations, the retained outbox row may be resent; the
 * recipient's durable receipt ledger deduplicates it and emits the ack again.
 */
internal class OlmDeliveryAckProcessor(
    private val queue: OlmDeliveryQueue,
    private val transport: OlmDeliveryAckTransport,
    private val senderUserId: String,
    private val senderDeviceId: String,
    private val recordAcknowledgement: suspend (OlmDeliveryAcknowledgement, Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun processPending(): OlmDeliveryAckProcessingResult {
        val acknowledgements = transport.readMyDeliveryAcknowledgements(senderDeviceId)
        var removed = 0
        var unmatched = 0

        for (acknowledgement in acknowledgements) {
            if (acknowledgement.senderUserId != senderUserId ||
                acknowledgement.senderDeviceId != senderDeviceId ||
                !queue.hasMatchingDelivery(acknowledgement)
            ) {
                unmatched++
                continue
            }

            // Remove the relay ack first, then persist a metadata-only delivery
            // receipt before deleting the ciphertext outbox row. If the process
            // stops after receipt persistence, the retained row is safe to retry:
            // the recipient's durable receipt ledger will acknowledge it again.
            transport.removeMyDeliveryAcknowledgement(senderDeviceId, acknowledgement)
            recordAcknowledgement(acknowledgement, clock().coerceAtLeast(0L))
            if (queue.removeAcknowledgedDelivery(acknowledgement)) {
                removed++
            } else {
                // Another processor may have removed the exact row concurrently.
                unmatched++
            }
        }

        return OlmDeliveryAckProcessingResult(removed = removed, unmatched = unmatched)
    }
}

internal data class OlmDeliveryAckProcessingResult(
    val removed: Int,
    val unmatched: Int,
)
