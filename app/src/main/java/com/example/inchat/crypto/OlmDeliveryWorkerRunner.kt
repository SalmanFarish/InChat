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
    val followUpRequired: Boolean = false,
    val notificationDispatchFailures: Int = 0,
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
    private val syncEncryptedDirectPreview: suspend (QueuedOlmDeviceDelivery) -> Unit = {},
    private val syncEncryptedGroupPreview: suspend (QueuedOlmDeviceDelivery) -> Unit = {},
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
        var retryRequired = false
        var followUpRequired = false
        var notificationDispatchFailures = 0
        repeat(maxDeliveriesPerRun) { batchIndex ->
            val delivery = withContext(ioDispatcher) {
                queue.claimNext(senderUserId, senderDeviceId, clock().coerceAtLeast(0L))
            } ?: return OlmDeliveryWorkerRunResult(
                uploaded = uploaded,
                acknowledged = acknowledgements.removed,
                unmatchedAcknowledgements = acknowledgements.unmatched,
                retryRequired = retryRequired,
                notificationDispatchFailures = notificationDispatchFailures,
            )

            if (batchIndex == maxDeliveriesPerRun - 1) {
                // Continue draining full batches without placing the sender chain in retry backoff.
                followUpRequired = true
            }

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
                    if (!quarantined) retryRequired = true
                    return@repeat
                }
                // The relay write is the durable source of truth. Notify only
                // the other account's devices; sender-owned secondary devices
                // recover through their own scheduled inbox poll.
                transport.upload(delivery, senderDeviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OlmMailboxHttpException) {
                val transitioned = withContext(ioDispatcher) {
                    val now = clock().coerceAtLeast(0L)
                    if (failure.retryable) {
                        queue.retry(
                            delivery = delivery,
                            nowMillis = now,
                            errorCode = RELAY_UPLOAD_ERROR,
                        )
                    } else {
                        queue.failPermanently(
                            delivery = delivery,
                            nowMillis = now,
                            errorCode = OlmDeliveryRelayFailurePolicy.permanentErrorCode(
                                failure.statusCode,
                            ),
                        )
                    }
                }
                // A failed device must not head-of-line block unrelated deliveries.
                // Its own persisted backoff keeps it out of the remaining claims.
                if (failure.retryable || !transitioned) retryRequired = true
                return@repeat
            } catch (_: Exception) {
                withContext(ioDispatcher) {
                    queue.retry(
                        delivery = delivery,
                        nowMillis = clock().coerceAtLeast(0L),
                        errorCode = RELAY_UPLOAD_ERROR,
                    )
                }
                retryRequired = true
                return@repeat
            }

            // Dispatch the recipient wake-up immediately after the ciphertext
            // is durably accepted by the relay. The generic inbox-preview HTTP
            // update is independent and must not delay the FCM wake-up.
            try {
                // Every remote device needs a wake-up, including a companion
                // device owned by the sending account. It must stage the exact
                // ciphertext and ACK group-key distributions before group send.
                notifyEncryptedRecipient(delivery)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notificationDispatchFailures++
                // Push is only a wake-up hint. The relay already durably
                // accepted this immutable delivery, and the recipient's
                // inbox poll is the recovery path if push dispatch fails.
                // Do not spend the ciphertext upload retry budget or
                // re-upload a delivery solely because a notification failed.
            }

            if (delivery.recipientUserId != senderUserId && delivery.groupId == null) {
                try {
                    syncEncryptedDirectPreview(delivery)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    withContext(ioDispatcher) {
                        queue.retry(
                            delivery = delivery,
                            nowMillis = clock().coerceAtLeast(0L),
                            errorCode = DIRECT_PREVIEW_SYNC_ERROR,
                        )
                    }
                    retryRequired = true
                    return@repeat
                }
            }


            if (delivery.recipientUserId != senderUserId && delivery.groupId != null) {
                try {
                    syncEncryptedGroupPreview(delivery)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    withContext(ioDispatcher) {
                        queue.retry(
                            delivery = delivery,
                            nowMillis = clock().coerceAtLeast(0L),
                            errorCode = GROUP_PREVIEW_SYNC_ERROR,
                        )
                    }
                    retryRequired = true
                    return@repeat
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
                retryRequired = true
                return@repeat
            }
        }

        // Re-run once after a successful batch so a delayed recipient ack can be
        // collected promptly and larger queues continue in bounded chunks.
        return OlmDeliveryWorkerRunResult(
            uploaded = uploaded,
            acknowledged = acknowledgements.removed,
            unmatchedAcknowledgements = acknowledgements.unmatched,
            retryRequired = retryRequired,
            followUpRequired = followUpRequired,
            notificationDispatchFailures = notificationDispatchFailures,
        )
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 25
        const val RELAY_UPLOAD_ERROR = "relay_upload_failed"
        const val STALE_GROUP_MEMBERSHIP = "group_membership_epoch_stale"
        const val DIRECT_PREVIEW_SYNC_ERROR = "direct_preview_sync_failed"
        const val GROUP_PREVIEW_SYNC_ERROR = "group_preview_sync_failed"
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
