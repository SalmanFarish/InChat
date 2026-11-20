package com.example.inchat.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OlmPendingReceiptProcessingResult(
    val finalizationChecked: Int,
    val finalized: Int,
    val pendingScanned: Int,
    val committed: Int,
    val alreadyCommitted: Int,
    val waitingForTrust: Int,
    val missingRemoteDevice: Int,
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
 * An unknown or untrusted sender device is left pending; no receive ratchet is advanced.
 * Failures for one receipt do not prevent independent receipts in the same batch.
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
            var committed = 0
            var alreadyCommitted = 0
            var waitingForTrust = 0
            var missingRemoteDevice = 0
            var failed = 0

            // Recover the crash window where Room committed the message but the
            // native staged-plaintext cleanup had not yet completed.
            val awaitingFinalization = receiptDao.getAwaitingFinalization(limit)
            for (receipt in awaitingFinalization) {
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

            val pendingReceipts = receiptDao.getPending(limit)
            pendingScanned = pendingReceipts.size
            val deviceDirectoryCache = mutableMapOf<String, List<PublicDeviceKeyBundle>>()

            for (receipt in pendingReceipts) {
                // Stop using this batch if Firebase authentication changed while
                // the worker was running. The coordinator independently checks it too.
                if (authenticatedLocalUserId() != localUserId) break

                try {
                    val devices = deviceDirectoryCache[receipt.senderUserId]
                        ?: remoteDevicesForUser(receipt.senderUserId).also {
                            deviceDirectoryCache[receipt.senderUserId] = it
                        }
                    val remoteDevice = devices.firstOrNull {
                        it.deviceId == receipt.senderDeviceId
                    }
                    if (remoteDevice == null) {
                        missingRemoteDevice++
                        continue
                    }

                    if (!trustVerifier.isVerified(
                            peerUserId = receipt.senderUserId,
                            peerDeviceId = remoteDevice.deviceId,
                            peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
                        )
                    ) {
                        waitingForTrust++
                        continue
                    }

                    val envelope = EncryptedOlmEnvelopeCodec.decode(receipt.envelope)
                    require(envelope.senderDeviceId == receipt.senderDeviceId) {
                        "Stored envelope sender does not match its receipt"
                    }
                    require(envelope.recipientDeviceId == receipt.recipientDeviceId) {
                        "Stored envelope recipient does not match its receipt"
                    }
                    val ciphertextKind = OlmInboundCiphertextKind.fromEnvelope(envelope)

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
                    // Keep the immutable ciphertext receipt PENDING. A later
                    // worker pass can recover transient native/Room failures.
                    failed++
                }
            }

            OlmPendingReceiptProcessingResult(
                finalizationChecked = finalizationChecked,
                finalized = finalized,
                pendingScanned = pendingScanned,
                committed = committed,
                alreadyCommitted = alreadyCommitted,
                waitingForTrust = waitingForTrust,
                missingRemoteDevice = missingRemoteDevice,
                failed = failed,
            )
        }

    private companion object {
        const val DEFAULT_BATCH_LIMIT = 50
        const val MAX_BATCH_LIMIT = 100
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
