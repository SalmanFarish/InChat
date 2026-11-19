package com.example.inchat.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OlmDeliveryWorkerRunResult(
    val uploaded: Int,
    val acknowledged: Int,
    val unmatchedAcknowledgements: Int,
    val retryRequired: Boolean,
)

/**
 * Coordinates one bounded sender-side pass over the account/device-scoped
 * encrypted outbox. The queue remains the durable source of truth; this class
 * never reads or writes ordinary chat message paths.
 */
internal class OlmDeliveryWorkerRunner(
    private val queue: OlmDeliveryQueue,
    private val transport: OlmOutgoingDeliveryTransport,
    private val senderUserId: String,
    private val senderDeviceId: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxDeliveriesPerRun: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(senderUserId.matches(USER_ID_PATTERN)) { "Sender user ID has an invalid format" }
        require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }
        require(maxDeliveriesPerRun > 0) { "Batch size must be positive" }
    }

    suspend fun run(): OlmDeliveryWorkerRunResult {
        // Acknowledgements are applied first. The processor deletes the relay ack
        // before deleting the exact matching Room row, making interruption safe.
        val acknowledgements = OlmDeliveryAckProcessor(
            queue = queue,
            transport = transport,
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
        ).processPending()

        var uploaded = 0
        repeat(maxDeliveriesPerRun) {
            val delivery = withContext(ioDispatcher) {
                queue.claimNext(senderUserId, senderDeviceId, clock().coerceAtLeast(0L))
            } ?: return OlmDeliveryWorkerRunResult(
                uploaded = uploaded,
                acknowledged = acknowledgements.removed,
                unmatchedAcknowledgements = acknowledgements.unmatched,
                retryRequired = false,
            )

            try {
                transport.upload(delivery, senderDeviceId)
                uploaded++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persist bounded backoff in Room before asking WorkManager to retry.
                // The stable deliveryId makes a retry safe if Firebase stored the
                // ciphertext but the client did not receive the write completion.
                withContext(ioDispatcher) {
                    queue.retry(
                        delivery = delivery,
                        nowMillis = clock().coerceAtLeast(0L),
                        errorCode = RELAY_UPLOAD_ERROR,
                    )
                }
                return OlmDeliveryWorkerRunResult(
                    uploaded = uploaded,
                    acknowledged = acknowledgements.removed,
                    unmatchedAcknowledgements = acknowledgements.unmatched,
                    retryRequired = true,
                )
            }
        }

        // Re-run once after a successful batch so a delayed recipient ack can be
        // collected promptly and larger queues continue in bounded chunks.
        return OlmDeliveryWorkerRunResult(
            uploaded = uploaded,
            acknowledged = acknowledgements.removed,
            unmatchedAcknowledgements = acknowledgements.unmatched,
            retryRequired = uploaded > 0,
        )
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 25
        const val RELAY_UPLOAD_ERROR = "relay_upload_failed"
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
