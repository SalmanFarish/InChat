package com.example.inchat.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun processPending(limit: Int = DEFAULT_BATCH_LIMIT): OlmPendingReceiptProcessingResult =
        withContext(ioDispatcher) {
            require(limit in 1..MAX_BATCH_LIMIT) {
                "Inbound receipt batch limit must be between 1 and $MAX_BATCH_LIMIT"
            }
            val localUserId = requireNotNull(authenticatedLocalUserId()) {
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
            for (receipt in receiptDao.getAwaitingFinalization(limit)) {
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
            val pendingReceipts = receiptDao.getPending(limit)
            val deferredReceipts = receiptDao.getWaitingForDevice(limit)
            pendingScanned = pendingReceipts.size
            deferredScanned = deferredReceipts.size
            val deviceDirectoryCache = mutableMapOf<String, List<PublicDeviceKeyBundle>>()

            suspend fun resolveDevices(senderUserId: String): List<PublicDeviceKeyBundle> {
                val cached = deviceDirectoryCache[senderUserId]
                if (cached != null) return cached
                val loaded = remoteDevicesForUser(senderUserId)
                deviceDirectoryCache[senderUserId] = loaded
                return loaded
            }

            suspend fun processReceipt(
                receipt: OlmInboundReceiptEntity,
                isDeferred: Boolean,
            ) {
                if (authenticatedLocalUserId() != localUserId) return

                val envelope: EncryptedOlmEnvelope
                val ciphertextKind: OlmInboundCiphertextKind
                try {
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
                    receiptDao.markRejected(receipt.receiptId)
                    rejected++
                    return
                }

                // Let directory/network failures escape to WorkManager so the whole
                // pass can retry. Missing entries are a normal deferred condition.
                val devices = resolveDevices(receipt.senderUserId)
                try {
                    val remoteDevice = devices.firstOrNull {
                        it.deviceId == receipt.senderDeviceId
                    }
                    if (remoteDevice == null) {
                        if (!isDeferred) receiptDao.markWaitingForDevice(receipt.receiptId)
                        missingRemoteDevice++
                        return
                    }

                    if (!trustVerifier.isVerified(
                            peerUserId = receipt.senderUserId,
                            peerDeviceId = remoteDevice.deviceId,
                            peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
                        )
                    ) {
                        if (!isDeferred) receiptDao.markWaitingForDevice(receipt.receiptId)
                        waitingForTrust++
                        return
                    }

                    if (isDeferred) {
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
                    // Keep the immutable receipt recoverable. Transient crypto,
                    // Keystore, Room, and mapping errors are retried on a later pass.
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

    private companion object {
        const val DEFAULT_BATCH_LIMIT = 50
        const val MAX_BATCH_LIMIT = 100
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
