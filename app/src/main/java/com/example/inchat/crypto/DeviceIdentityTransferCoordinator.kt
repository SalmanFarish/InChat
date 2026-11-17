package com.example.inchat.crypto

import java.security.MessageDigest

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
 * This class does not provide the pairing transport. The transfer key, nonce,
 * encrypted payload and receipt must still be carried by an authenticated
 * device-to-device channel before this coordinator is exposed in UI.
 */
internal class DeviceIdentityTransferCoordinator(
    private val identityRepository: DeviceIdentityRepository,
    private val directory: DeviceIdentityTransferDirectory,
) {
    /** Start a transfer bound to the currently authenticated Firebase account. */
    fun beginTransfer(targetDeviceId: String): String {
        val ownerUserId = directory.authenticatedUserId
        return identityRepository.beginIdentityTransfer(targetDeviceId, ownerUserId)
    }

    /**
     * Publish the imported target identity and return its Ed25519 proof-of-possession receipt.
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
                directory.removeMyDevice(pending.sourceDeviceId)
            }

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

    private fun fingerprint(identityKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(identityKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
