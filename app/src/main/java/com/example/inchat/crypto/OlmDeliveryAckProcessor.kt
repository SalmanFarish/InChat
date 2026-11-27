package com.example.inchat.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    suspend fun processPending(): OlmDeliveryAckProcessingResult = PROCESSOR_MUTEX.withLock {
        val acknowledgements = transport.readMyDeliveryAcknowledgements(senderDeviceId)
        var removed = 0
        var unmatched = 0
        var firstFailure: Exception? = null

        for (acknowledgement in acknowledgements) {
            try {
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
            } catch (cancelled: CancellationException) {
                // Cancellation is a process/lifecycle boundary, not an item failure.
                // Preserve it immediately so the durable ACK/outbox recovery path runs.
                throw cancelled
            } catch (failure: Exception) {
                // One relay delete or Room receipt failure must not head-of-line
                // block later independent ACKs in this bounded snapshot. Continue
                // reconciling the rest, then surface the first error so WorkManager
                // retries the incomplete item(s) on the next pass.
                if (firstFailure == null) firstFailure = failure
            }
        }

        firstFailure?.let { throw it }
        OlmDeliveryAckProcessingResult(removed = removed, unmatched = unmatched)
    }

    private companion object {
        // WorkManager can overlap workers in one process. Serialize the remote-ack
        // delete -> durable receipt -> local outbox delete sequence across instances.
        val PROCESSOR_MUTEX = Mutex()
    }
}

internal data class OlmDeliveryAckProcessingResult(
    val removed: Int,
    val unmatched: Int,
)
