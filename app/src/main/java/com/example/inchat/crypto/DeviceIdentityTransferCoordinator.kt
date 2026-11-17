package com.example.inchat.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The narrow account-scoped directory operations needed to finish a transfer.
 * The backing directory is untrusted data; returned bundles are verified by
 * DeviceKeyDirectoryRepository before the coordinator receives them.
 */
internal interface DeviceIdentityTransferDirectory {
    val authenticatedUserId: String
    suspend fun publishMyDevice(bundle: PublicDeviceKeyBundle)
    suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle>
    suspend fun removeMyDevice(deviceId: String)
}

/**
 * Coordinates the account-directory portion of an identity handoff.
 *
 * Destination sequence: import the encrypted state, publish its re-bound public
 * bundle, then create the acknowledgement. Source sequence: verify that receipt,
 * confirm the target publishes the same Ed25519 identity under the bound account,
 * remove the old public directory entry, confirm removal, and only then delete the
 * old local Keystore key/state. All actions are retryable after process death.
 *
 * Pairing-derived key agreement, the encrypted Firebase relay, receipt exchange
 * and source retirement are connected here. QR UI, user-facing recovery screens
 * and two-physical-device verification are still required before exposure in UI. The receipt
 * is signed by the transferred identity, which is still present on the source
 * until retirement; it is not independent proof that a distinct device imported it.
 */
internal class DeviceIdentityTransferCoordinator(
    private val identityRepository: DeviceIdentityRepository,
    private val directory: DeviceIdentityTransferDirectory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * A transfer can begin only with a fresh, two-sided QR pairing proof and
     * explicit SAS confirmation on this source device. The destination must
     * separately call confirmResponse after its own user confirms the same code.
     */
    fun beginTransfer(
        pairing: DevicePairingProtocol.VerifiedPairing,
        confirmedSasCode: String,
    ): String {
        val ownerUserId = directory.authenticatedUserId
        val sourceDeviceId = identityRepository.currentDeviceId()
        require(pairing.sourceDeviceId == sourceDeviceId) {
            "Pairing proof belongs to another source device"
        }
        require(pairing.targetDeviceId != sourceDeviceId)
        require(pairing.expiresAtMillis > clock().coerceAtLeast(0L)) {
            "Device pairing has expired. Scan a new QR code."
        }
        require(
            MessageDigest.isEqual(
                pairing.sasCode.toByteArray(Charsets.US_ASCII),
                confirmedSasCode.toByteArray(Charsets.US_ASCII),
            )
        ) {
            "Confirm that the codes match on both devices before transferring"
        }
        return identityRepository.beginIdentityTransfer(
            targetDeviceId = pairing.targetDeviceId,
            ownerUserId = ownerUserId,
            transactionId = pairing.transactionId,
        )
    }

    /**
     * Encrypt the source identity using the one-time key derived from the exact
     * verified QR/SAS pairing. The pairing's ephemeral source key is consumed.
     * The returned bytes are ciphertext only; callers must deliver them through
     * an authenticated, size-limited transport and must not log or persist them
     * in plaintext storage.
     */
    fun exportPairedIdentity(
        pairingProtocol: DevicePairingProtocol,
        pairing: DevicePairingProtocol.VerifiedPairing,
    ): ByteArray {
        val pending = requireNotNull(identityRepository.pendingIdentityTransfer()) {
            "Start and confirm a device pairing before exporting"
        }
        require(
            pending.sourceDeviceId == pairing.sourceDeviceId &&
                pending.targetDeviceId == pairing.targetDeviceId &&
                pending.transactionId == pairing.transactionId &&
                !pending.exported &&
                pending.ownerUserId == directory.authenticatedUserId
        ) { "Pairing does not match the pending account transfer" }

        val transferKey = pairingProtocol.deriveSourceTransferKey(pairing)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        return try {
            val encrypted = identityRepository.exportEncryptedIdentity(
                targetDeviceId = pairing.targetDeviceId,
                transactionId = pairing.transactionId,
                transferKey = transferKey,
                nonce = nonce,
            )
            pairingProtocol.clearEphemeralSession(pairing.transactionId)
            encrypted
        } catch (failure: Throwable) {
            transferKey.fill(0)
            nonce.fill(0)
            throw failure
        }
    }

    /**
     * Decrypt a received transfer only with the destination's matching ephemeral
     * pairing session. A mismatched device or transaction is rejected before the
     * native import is attempted. Import consumes and clears the ciphertext buffer.
     */
    fun importPairedIdentity(
        pairingProtocol: DevicePairingProtocol,
        sourceDeviceId: String,
        transactionId: String,
        encryptedPayload: ByteArray,
    ) {
        require(encryptedPayload.size in 1..MAX_TRANSFER_PAYLOAD_BYTES) {
            "Encrypted device transfer has an invalid size"
        }
        val targetDeviceId = identityRepository.currentDeviceId()
        val transferKey = pairingProtocol.deriveDestinationTransferKey(
            targetDeviceId,
            transactionId,
        )
        try {
            identityRepository.importEncryptedIdentity(
                sourceDeviceId = sourceDeviceId,
                transactionId = transactionId,
                transferKey = transferKey,
                encryptedPayload = encryptedPayload,
            )
            pairingProtocol.clearEphemeralSession(transactionId)
        } catch (failure: Throwable) {
            transferKey.fill(0)
            encryptedPayload.fill(0)
            throw failure
        }
    }

    /**
     * Export and upload only the AEAD ciphertext. If upload fails before the relay's
     * ready marker is committed, remove the incomplete record and roll back the
     * local exported journal so the user can start a fresh pairing.
     */
    suspend fun exportPairedIdentityToRelay(
        pairingProtocol: DevicePairingProtocol,
        pairing: DevicePairingProtocol.VerifiedPairing,
        transport: DeviceIdentityTransferTransport,
    ) {
        val encryptedPayload = exportPairedIdentity(pairingProtocol, pairing)
        try {
            transport.upload(
                sourceDeviceId = pairing.sourceDeviceId,
                targetDeviceId = pairing.targetDeviceId,
                transactionId = pairing.transactionId,
                encryptedPayload = encryptedPayload,
            )
        } catch (failure: Throwable) {
            val safelyDiscarded = withContext(NonCancellable) {
                runCatching {
                    transport.discardUnreadyUpload(pairing.targetDeviceId, pairing.transactionId)
                }.getOrDefault(false)
            }
            if (safelyDiscarded) {
                identityRepository.cancelUnpublishedIdentityTransfer(
                    pairing.targetDeviceId,
                    pairing.transactionId,
                )
            }
            throw failure
        } finally {
            encryptedPayload.fill(0)
        }
    }

    /**
     * Download, validate and import a complete relay payload. A false result means
     * upload is not ready yet. After import, destination publication and receipt
     * delivery are separate retryable steps.
     */
    suspend fun importPairedIdentityFromRelay(
        pairingProtocol: DevicePairingProtocol,
        sourceDeviceId: String,
        transactionId: String,
        transport: DeviceIdentityTransferTransport,
    ): Boolean {
        val relayPayload = transport.download(
            targetDeviceId = identityRepository.currentDeviceId(),
            transactionId = transactionId,
        ) ?: return false
        require(relayPayload.sourceDeviceId == sourceDeviceId) {
            relayPayload.encryptedPayload.fill(0)
            "Relay source does not match the paired device"
        }
        require(relayPayload.targetDeviceId == identityRepository.currentDeviceId()) {
            relayPayload.encryptedPayload.fill(0)
            "Relay destination does not match this installation"
        }
        importPairedIdentity(
            pairingProtocol = pairingProtocol,
            sourceDeviceId = sourceDeviceId,
            transactionId = transactionId,
            encryptedPayload = relayPayload.encryptedPayload,
        )
        return true
    }

    /** Publish the imported device and relay its signed receipt; safe to retry. */
    suspend fun publishDestinationAcknowledgementToRelay(
        sourceDeviceId: String,
        ownerUserId: String,
        transactionId: String,
        transport: DeviceIdentityTransferTransport,
    ) {
        val acknowledgement = publishImportedDestination(
            sourceDeviceId = sourceDeviceId,
            ownerUserId = ownerUserId,
            transactionId = transactionId,
        )
        try {
            transport.publishAcknowledgement(
                targetDeviceId = identityRepository.currentDeviceId(),
                transactionId = transactionId,
                acknowledgement = acknowledgement,
            )
        } finally {
            acknowledgement.fill(0)
        }
    }

    /** Fetch the destination receipt, retire the source, then remove the relay. */
    suspend fun acknowledgeAndRetireSourceFromRelay(
        targetDeviceId: String,
        transactionId: String,
        transport: DeviceIdentityTransferTransport,
    ): Boolean {
        val acknowledgement = transport.readAcknowledgement(targetDeviceId, transactionId)
            ?: return false
        val retired = acknowledgeAndRetireSource(
            targetDeviceId = targetDeviceId,
            transactionId = transactionId,
            acknowledgement = acknowledgement,
        )
        if (retired) {
            transport.deleteCompleted(targetDeviceId, transactionId)
        }
        return retired
    }

    /**
     * Publish the imported target identity and return a context-bound Ed25519 receipt.
     * Publication happens before acknowledgement so the source can independently
     * confirm the destination is present in the public device directory.
     */
    suspend fun publishImportedDestination(
        sourceDeviceId: String,
        ownerUserId: String,
        transactionId: String,
    ): ByteArray {
        check(directory.authenticatedUserId == ownerUserId) {
            "Sign in to the same InChat account on the destination device"
        }
        val targetDeviceId = identityRepository.currentDeviceId()
        require(sourceDeviceId != targetDeviceId) {
            "A device identity cannot be transferred to itself"
        }

        val bundle = identityRepository.ensurePublicKeyBundle()
        check(bundle.deviceId == targetDeviceId) {
            "Imported destination identity does not match this installation"
        }
        directory.publishMyDevice(bundle)
        identityRepository.markPublicKeyBundlePublished()

        return identityRepository.createIdentityTransferAcknowledgement(
            sourceDeviceId = sourceDeviceId,
            transactionId = transactionId,
        )
    }

    /**
     * Verify the destination receipt, validate both account-directory entries,
     * revoke the source entry, then destroy the source's local encrypted identity.
     *
     * If any network operation fails, the source remains locked with its local
     * state intact. If the process dies after remote revocation, retrying is safe:
     * removal is idempotent and local deletion is completed from the journal.
     */
    suspend fun acknowledgeAndRetireSource(
        targetDeviceId: String,
        transactionId: String,
        acknowledgement: ByteArray,
    ): Boolean {
        try {
            val pending = requireNotNull(identityRepository.pendingIdentityTransfer()) {
                "No device identity transfer is pending"
            }
            require(
                pending.targetDeviceId == targetDeviceId &&
                    pending.transactionId == transactionId &&
                    pending.exported &&
                    pending.ownerUserId != null &&
                    pending.sourceIdentityFingerprint != null
            ) {
                "Transfer details do not match the pending source transaction"
            }
            val ownerUserId = requireNotNull(pending.ownerUserId)
            check(directory.authenticatedUserId == ownerUserId) {
                "The signed-in account does not match the account that started this transfer"
            }

            val accepted = identityRepository.acknowledgeIdentityTransfer(
                targetDeviceId = targetDeviceId,
                transactionId = transactionId,
                acknowledgement = acknowledgement,
            )
            if (!accepted) return false

            // Directory reads validate the Ed25519 identity binding and all
            // prekey signatures. A matching fingerprint proves the target entry
            // is the transferred identity, not merely a device with the right ID.
            val devices = directory.getUserDevices(ownerUserId)
            val target = devices.firstOrNull { it.deviceId == targetDeviceId }
                ?: error("Destination device has not been published yet")
            require(fingerprint(target.ed25519IdentityKey) == pending.sourceIdentityFingerprint) {
                "Published destination does not contain the transferred identity"
            }

            val sourceEntry = devices.firstOrNull { it.deviceId == pending.sourceDeviceId }
            if (sourceEntry != null) {
                require(fingerprint(sourceEntry.ed25519IdentityKey) == pending.sourceIdentityFingerprint) {
                    "Source directory entry no longer matches the transferring identity"
                }
            }
            // Always invoke removal, even when the public entry is already absent.
            // This also retries cleanup of prekey-claim metadata if an earlier
            // removal succeeded but its second cleanup operation was interrupted.
            directory.removeMyDevice(pending.sourceDeviceId)

            val remaining = directory.getUserDevices(ownerUserId)
            check(remaining.none { it.deviceId == pending.sourceDeviceId }) {
                "The old device is still present in the account directory"
            }

            identityRepository.retireIdentityTransfer(targetDeviceId, transactionId)
            return true
        } finally {
            acknowledgement.fill(0)
        }
    }

    private companion object {
        // Rust limits the decoded identity state to 8 MiB; allow modest AEAD
        // envelope overhead while rejecting oversized untrusted transport data.
        const val MAX_TRANSFER_PAYLOAD_BYTES = 9 * 1024 * 1024
    }

    private fun fingerprint(identityKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(identityKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
