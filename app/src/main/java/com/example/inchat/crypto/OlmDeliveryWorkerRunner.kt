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
    private val recordAcknowledgement: suspend (OlmDeliveryAcknowledgement, Long) -> Unit,
    private val validateBeforeUpload: suspend (QueuedOlmDeviceDelivery) -> Boolean = { true },
    private val notifyEncryptedRecipient: suspend (QueuedOlmDeviceDelivery) -> Unit = {},
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
            recordAcknowledgement = recordAcknowledgement,
            clock = clock,
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
                // A committed Megolm fan-out is bound to the exact membership
                // epoch at encryption time. Never relay old group ciphertext
                // after membership changes, even if this recipient is still a
                // member (or was later re-added under a newer epoch).
                if (!validateBeforeUpload(delivery)) {
                    val quarantined = withContext(ioDispatcher) {
                        queue.failPermanently(
                            delivery = delivery,
                            nowMillis = clock().coerceAtLeast(0L),
                            errorCode = STALE_GROUP_MEMBERSHIP,
                        )
                    }
                    if (!quarantined) {
                        return OlmDeliveryWorkerRunResult(
                            uploaded = uploaded,
                            acknowledged = acknowledgements.removed,
                            unmatchedAcknowledgements = acknowledgements.unmatched,
                            retryRequired = true,
                        )
                    }
                    return@repeat
                }
                // The relay write is the durable source of truth. Notify only
                // the other account's devices; sender-owned secondary devices
                // recover through their own scheduled inbox poll.
                transport.upload(delivery, senderDeviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
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

            if (delivery.recipientUserId != senderUserId) {
                try {
                    notifyEncryptedRecipient(delivery)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Push is only a wake-up hint. The relay already durably
                    // accepted this immutable delivery, and the recipient's
                    // inbox poll is the recovery path if push dispatch fails.
                    // Do not spend the ciphertext upload retry budget or
                    // re-upload a delivery solely because a notification failed.
                }
            }

            val markedUploaded = withContext(ioDispatcher) {
                queue.markUploaded(
                    delivery = delivery,
                    nowMillis = clock().coerceAtLeast(0L),
                )
            }
            uploaded++
            if (!markedUploaded) {
                // The upload completed after this claim expired or was reclaimed.
                // Do not report the pass as settled: another worker owns the row,
                // and WorkManager should promptly re-run acknowledgement/recovery.
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
        const val STALE_GROUP_MEMBERSHIP = "group_membership_epoch_stale"
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
