package com.example.inchat.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class OlmPendingReceiptProcessingResult(
    val finalizationChecked: Int,
    val finalized: Int,
    val pendingScanned: Int,
    val deferredScanned: Int,
    val committed: Int,
    val alreadyCommitted: Int,
    val waitingForTrust: Int,
    val missingRemoteDevice: Int,
    val rejected: Int,
    val failed: Int,
)

/** The authenticated decrypt-and-commit operation supplied by the handoff coordinator. */
internal fun interface OlmInboundReceiptHandoff {
    suspend fun process(
        receiptId: String,
        remoteDevice: PublicDeviceKeyBundle,
        remoteDeviceUserId: String,
        ciphertextKind: OlmInboundCiphertextKind,
        localUserId: String,
    ): OlmInboundHandoffResult
}

/** Idempotently removes native staged plaintext for a Room-committed receipt. */
internal fun interface OlmCommittedReceiptFinalizer {
    suspend fun finalizeReceipt(receiptId: String, localUserId: String): Boolean
}

/**
 * Advances durable ciphertext receipts through the existing SAS-gated Olm handoff.
 *
 * Public device bundles are resolved through the signature-verifying device directory.
 * An unknown or untrusted sender device is deferred rather than left at the head of
 * the new-message queue. No receive ratchet is advanced before SAS trust is confirmed.
 */
internal class OlmPendingReceiptProcessor(
    private val receiptDao: OlmInboundReceiptDao,
    private val remoteDevicesForUser: suspend (userId: String) -> List<PublicDeviceKeyBundle>,
    private val trustVerifier: OlmDeviceTrustVerifier,
    private val handoff: OlmInboundReceiptHandoff,
    private val finalizer: OlmCommittedReceiptFinalizer,
    private val authenticatedLocalUserId: () -> String?,
    private val expectedLocalUserId: String? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun processPending(limit: Int = DEFAULT_BATCH_LIMIT): OlmPendingReceiptProcessingResult =
        withContext(ioDispatcher) {
            // WorkManager may create multiple workers in one app process. Serialize
            // receipt passes so the same pending ciphertext cannot advance an Olm
            // receive ratchet concurrently. Room remains the durable deduplication layer.
            PROCESSOR_MUTEX.withLock {
                require(limit in 1..MAX_BATCH_LIMIT) {
                    "Inbound receipt batch limit must be between 1 and $MAX_BATCH_LIMIT"
                }
                val authenticatedUserId = authenticatedLocalUserId()
                // Bind this pass to the account that scheduled its worker. A stale
                // worker must not adopt a newly signed-in account after inbox polling.
                if (expectedLocalUserId != null && authenticatedUserId != expectedLocalUserId) {
                    return@withLock OlmPendingReceiptProcessingResult(
                        finalizationChecked = 0, finalized = 0, pendingScanned = 0,
                        deferredScanned = 0, committed = 0, alreadyCommitted = 0,
                        waitingForTrust = 0, missingRemoteDevice = 0, rejected = 0, failed = 0,
                    )
                }
                val localUserId = requireNotNull(authenticatedUserId) {
                    "Sign in before processing encrypted inbound receipts"
                }
                require(USER_ID_PATTERN.matches(localUserId)) {
                    "Authenticated local user ID has an invalid format"
                }

                var finalizationChecked = 0
                var finalized = 0
                var pendingScanned = 0
                var deferredScanned = 0
                var committed = 0
                var alreadyCommitted = 0
                var waitingForTrust = 0
                var missingRemoteDevice = 0
                var rejected = 0
                var failed = 0

                // Recover the crash window where Room committed the message but the
                // native staged-plaintext cleanup had not yet completed.
                for (receipt in receiptDao.getAwaitingFinalization(localUserId, limit)) {
                    if (authenticatedLocalUserId() != localUserId) break
                    finalizationChecked++
                    try {
                        if (finalizer.finalizeReceipt(receipt.receiptId, localUserId)) {
                            finalized++
                        } else {
                            failed++
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed++
                    }
                }

                // Snapshot both queues before changing states. A receipt deferred in
                // this pass is reconsidered on the next pass, not twice immediately.
                val pendingReceipts = receiptDao.getPending(localUserId, limit)
                val deferredReceipts = receiptDao.getWaitingForDevice(localUserId, limit)
                pendingScanned = pendingReceipts.size
                deferredScanned = deferredReceipts.size
                val deviceDirectoryCache = mutableMapOf<String, List<PublicDeviceKeyBundle>>()
                val failedDeviceDirectoryLookups = mutableSetOf<String>()

                suspend fun resolveDevices(senderUserId: String): List<PublicDeviceKeyBundle> {
                    val cached = deviceDirectoryCache[senderUserId]
                    if (cached != null) return cached
                    check(senderUserId !in failedDeviceDirectoryLookups) {
                        "Sender device directory lookup already failed during this pass"
                    }
                    return try {
                        remoteDevicesForUser(senderUserId).also { deviceDirectoryCache[senderUserId] = it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failedDeviceDirectoryLookups += senderUserId
                        throw error
                    }
                }

                suspend fun processReceipt(
                    receipt: OlmInboundReceiptEntity,
                    isDeferred: Boolean,
                ) {
                    if (authenticatedLocalUserId() != localUserId) return

                    val envelope: EncryptedOlmEnvelope
                    val ciphertextKind: OlmInboundCiphertextKind
                    try {
                        // Quarantine corrupt durable metadata before resolving remote
                        // devices or invoking the native ratchet handoff. Otherwise a
                        // bad digest/status/identity would remain PENDING and retry forever.
                        validateOlmInboundReceiptIntegrity(receipt)
                        envelope = EncryptedOlmEnvelopeCodec.decode(receipt.envelope)
                        require(envelope.senderDeviceId == receipt.senderDeviceId) {
                            "Stored envelope sender does not match its receipt"
                        }
                        require(envelope.recipientDeviceId == receipt.recipientDeviceId) {
                            "Stored envelope recipient does not match its receipt"
                        }
                        ciphertextKind = OlmInboundCiphertextKind.fromEnvelope(envelope)
                    } catch (invalidEnvelope: IllegalArgumentException) {
                        // Structurally malformed or unknown wire formats cannot recover
                        // on retry. Quarantine them without blocking valid later messages.
                        // Validation is local CPU work, but auth can still change
                        // concurrently on the main thread. Never mutate a receipt
                        // after this pass has lost its authenticated account scope.
                        if (authenticatedLocalUserId() != localUserId) return
                        receiptDao.markRejected(receipt.receiptId)
                        rejected++
                        return
                    }

                    // A directory outage for one sender must not abort this entire
                    // bounded batch and starve receipts from other senders. Cache the
                    // failure for this pass, rotate the affected receipt, and continue.
                    val devices = try {
                        resolveDevices(receipt.senderUserId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (authenticatedLocalUserId() == localUserId) {
                            receiptDao.markAttempted(
                                receiptId = receipt.receiptId,
                                attemptedAtMillis = clock().coerceAtLeast(0L),
                            )
                        }
                        failed++
                        return
                    }
                    // Directory resolution can suspend while Firebase auth changes.
                    // Do not mutate even deferred-receipt state under a stale account scope.
                    if (authenticatedLocalUserId() != localUserId) return
                    try {
                        val remoteDevice = devices.firstOrNull {
                            it.deviceId == receipt.senderDeviceId
                        }
                        if (remoteDevice == null) {
                            if (!isDeferred) receiptDao.markWaitingForDevice(receipt.receiptId)
                            receiptDao.markAttempted(
                                receiptId = receipt.receiptId,
                                attemptedAtMillis = clock().coerceAtLeast(0L),
                            )
                            missingRemoteDevice++
                            return
                        }

                        val isTrusted = trustVerifier.isTrustedForMessaging(
                            peerUserId = receipt.senderUserId,
                            peerDeviceId = remoteDevice.deviceId,
                            peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
                        )
                        // Trust lookup is synchronous today, but it may consult
                        // storage and race an auth callback. Re-fence before any
                        // account-scoped Room state transition.
                        if (authenticatedLocalUserId() != localUserId) return
                        if (!isTrusted) {
                            if (!isDeferred) receiptDao.markWaitingForDevice(receipt.receiptId)
                            receiptDao.markAttempted(
                                receiptId = receipt.receiptId,
                                attemptedAtMillis = clock().coerceAtLeast(0L),
                            )
                            waitingForTrust++
                            return
                        }

                        if (isDeferred) {
                            if (authenticatedLocalUserId() != localUserId) return
                            val restored = receiptDao.restorePending(receipt.receiptId)
                            val currentStatus = receiptDao.getByReceiptId(receipt.receiptId)?.status
                            check(
                                restored == 1 ||
                                    currentStatus == OlmInboundReceiptStatus.PENDING.name ||
                                    currentStatus == OlmInboundReceiptStatus.PROCESSED.name ||
                                    currentStatus == OlmInboundReceiptStatus.FINALIZED.name
                            ) {
                                "Deferred inbound receipt could not be restored for processing"
                            }
                        }

                        when (
                            handoff.process(
                                receiptId = receipt.receiptId,
                                remoteDevice = remoteDevice,
                                remoteDeviceUserId = receipt.senderUserId,
                                ciphertextKind = ciphertextKind,
                                localUserId = localUserId,
                            )
                        ) {
                            OlmInboundHandoffResult.COMMITTED -> committed++
                            OlmInboundHandoffResult.ALREADY_COMMITTED -> alreadyCommitted++
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep immutable ciphertext recoverable, but rotate failed rows
                        // behind receipts that have not yet been attempted. Otherwise a
                        // permanently blocked first batch can starve a later key receipt.
                        if (authenticatedLocalUserId() == localUserId) {
                            receiptDao.markRetryAttempted(
                                receiptId = receipt.receiptId,
                                attemptedAtMillis = clock().coerceAtLeast(0L),
                            )
                        }
                        failed++
                    }
                }

                for (receipt in pendingReceipts) {
                    processReceipt(receipt, isDeferred = false)
                }
                for (receipt in deferredReceipts) {
                    processReceipt(receipt, isDeferred = true)
                }

                OlmPendingReceiptProcessingResult(
                    finalizationChecked = finalizationChecked,
                    finalized = finalized,
                    pendingScanned = pendingScanned,
                    deferredScanned = deferredScanned,
                    committed = committed,
                    alreadyCommitted = alreadyCommitted,
                    waitingForTrust = waitingForTrust,
                    missingRemoteDevice = missingRemoteDevice,
                    rejected = rejected,
                    failed = failed,
                )
            }
        }

    private companion object {
        val PROCESSOR_MUTEX = Mutex()
        const val DEFAULT_BATCH_LIMIT = 50
        const val MAX_BATCH_LIMIT = 100
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
