package com.example.inchat.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

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
        suspend fun reconcile(acknowledgement: OlmDeliveryAcknowledgement): AckOutcome {
            return try {
                if (acknowledgement.senderUserId != senderUserId ||
                    acknowledgement.senderDeviceId != senderDeviceId ||
                    !queue.hasMatchingDelivery(acknowledgement)
                ) {
                    AckOutcome(unmatched = true)
                } else {
                    // This delivery's sequence remains strict even though independent
                    // acknowledgements can progress concurrently:
                    // remote ACK delete -> durable Room receipt -> exact outbox row delete.
                    transport.removeMyDeliveryAcknowledgement(senderDeviceId, acknowledgement)
                    recordAcknowledgement(acknowledgement, clock().coerceAtLeast(0L))
                    if (queue.removeAcknowledgedDelivery(acknowledgement)) {
                        AckOutcome(removed = true)
                    } else {
                        // Another exact reconciliation may have completed first (for
                        // example, a duplicate ACK in the same relay snapshot).
                        AckOutcome(unmatched = true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AckOutcome(failure = failure)
            }
        }

        // Use a fixed worker pool rather than creating one coroutine per ACK.
        // The atomic cursor lets each worker claim the next independent item as
        // soon as its current network/Room work completes, without an unbounded
        // number of suspended jobs on devices with a large relay mailbox.
        val outcomes = arrayOfNulls<AckOutcome>(acknowledgements.size)
        val nextIndex = AtomicInteger()
        val workerCount = minOf(MAX_CONCURRENT_ACKNOWLEDGEMENTS, acknowledgements.size)
        coroutineScope {
            List(workerCount) {
                async {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= acknowledgements.size) break
                        outcomes[index] = reconcile(acknowledgements[index])
                    }
                }
            }.awaitAll()
        }

        var removed = 0
        var unmatched = 0
        var firstFailure: Exception? = null
        for (outcomeOrNull in outcomes) {
            val outcome = checkNotNull(outcomeOrNull) {
                "ACK worker pool completed without reconciling every snapshot item"
            }
            if (outcome.removed) removed++
            if (outcome.unmatched) unmatched++
            if (firstFailure == null) firstFailure = outcome.failure
        }

        // Do not suppress retry signaling: all later independent ACKs get their
        // chance, then WorkManager retries any item whose exact sequence failed.
        firstFailure?.let { throw it }
        OlmDeliveryAckProcessingResult(removed = removed, unmatched = unmatched)
    }

    private data class AckOutcome(
        val removed: Boolean = false,
        val unmatched: Boolean = false,
        val failure: Exception? = null,
    )

    private companion object {
        // WorkManager can overlap workers in one process. Serialize snapshots
        // across instances, while independent ACKs within one snapshot are bounded.
        val PROCESSOR_MUTEX = Mutex()
        const val MAX_CONCURRENT_ACKNOWLEDGEMENTS = 8
    }
}

internal data class OlmDeliveryAckProcessingResult(
    val removed: Int,
    val unmatched: Int,
)
