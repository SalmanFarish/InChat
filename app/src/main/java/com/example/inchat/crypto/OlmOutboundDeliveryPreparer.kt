package com.example.inchat.crypto

/** A first encrypted message plus the Olm session that was created for its target device. */
internal data class PreparedOlmDelivery(
    val recipientUserId: String,
    val recipientDeviceId: String,
    val sessionId: String,
    val envelope: String,
)

/**
 * Coordinates the first outbound Olm message to one device.
 *
 * It reads the signed public directory, atomically claims one published OTK when
 * available, and falls back to the signed fallback key only when every OTK is
 * already claimed. No plaintext or private key is written to Firebase.
 *
 * This is an isolated crypto primitive: it does not enqueue, transmit, or persist
 * application messages and is not wired into live chat sending.
 */
internal class OlmOutboundDeliveryPreparer(
    private val directory: DeviceKeyDirectoryRepository,
    private val sessions: OlmSessionRepository,
) {
    /** Prepare an initial prekey envelope for exactly one remote device. */
    suspend fun prepareFirstMessage(
        recipientUserId: String,
        recipientDeviceId: String,
        plaintext: String,
    ): PreparedOlmDelivery {
        require(USER_ID_PATTERN.matches(recipientUserId)) { "Recipient user ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(recipientDeviceId)) { "Recipient device ID has an invalid format" }

        val remoteDevice = directory.getUserDevices(recipientUserId)
            .firstOrNull { it.deviceId == recipientDeviceId }
            ?: throw IllegalStateException("Recipient device is not present in the public key directory")

        var claimedPreKey: SignedOlmPreKey? = null
        for (candidate in remoteDevice.oneTimeKeys) {
            if (directory.claimOneTimePreKey(recipientUserId, recipientDeviceId, candidate.keyId)) {
                claimedPreKey = candidate
                break
            }
        }

        // The signed fallback key is reusable and remains safe as a last resort
        // when all one-time keys have already been atomically claimed.
        val selectedPreKey = claimedPreKey ?: requireNotNull(remoteDevice.fallbackKey) {
            "Recipient device has no signed fallback prekey"
        }

        val sessionId = sessions.createOutboundSession(remoteDevice, selectedPreKey)
        val envelope = sessions.encryptEnvelope(
            sessionId = sessionId,
            recipientDeviceId = remoteDevice.deviceId,
            plaintext = plaintext,
        )
        return PreparedOlmDelivery(
            recipientUserId = recipientUserId,
            recipientDeviceId = remoteDevice.deviceId,
            sessionId = sessionId,
            envelope = envelope,
        )
    }

    private companion object {
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
