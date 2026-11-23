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
 * This prepares one authenticated per-device ciphertext envelope. The live
 * direct-message coordinator commits the complete prepared fan-out to the
 * durable Room outbox; this class itself never transmits application messages.
 */
internal fun interface OlmFirstMessagePreparer {
    suspend fun prepareFirstMessage(payload: OlmAuthenticatedMessagePayload): PreparedOlmDelivery
}

/** Narrow key-directory contract needed to prepare a first Olm message. */
internal interface OlmOutboundKeyDirectory {
    val authenticatedUserId: String
    suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle>
    suspend fun claimOneTimePreKey(targetUid: String, deviceId: String, keyId: String): Boolean
}

/** Native session operations required by the first-message preparer. */
internal interface OlmOutboundSessionRepository {
    val localDeviceId: String
    fun createOutboundSession(remoteDevice: PublicDeviceKeyBundle, remotePreKey: SignedOlmPreKey): String
    fun encryptAuthenticatedEnvelope(sessionId: String, payload: OlmAuthenticatedMessagePayload): String
}

internal class OlmOutboundDeliveryPreparer(
    private val directory: OlmOutboundKeyDirectory,
    private val sessions: OlmOutboundSessionRepository,
    private val trustVerifier: OlmDeviceTrustVerifier,
) : OlmFirstMessagePreparer {
    /** Prepare an initial prekey envelope for exactly one target device. */
    override suspend fun prepareFirstMessage(
        payload: OlmAuthenticatedMessagePayload,
    ): PreparedOlmDelivery {
        require(USER_ID_PATTERN.matches(payload.recipientUserId)) { "Recipient user ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(payload.recipientDeviceId)) { "Recipient device ID has an invalid format" }
        require(payload.senderDeviceId == sessions.localDeviceId) {
            "Authenticated payload sender device does not match this installation"
        }
        require(payload.senderUserId == directory.authenticatedUserId) {
            "Authenticated payload sender account does not match the signed-in account"
        }
        // Validate the complete versioned payload before claiming a one-time key.
        OlmAuthenticatedMessagePayloadCodec.encode(payload)

        val recipientUserId = payload.recipientUserId
        val recipientDeviceId = payload.recipientDeviceId
        val remoteDevice = directory.getUserDevices(recipientUserId)
            .firstOrNull { it.deviceId == recipientDeviceId }
            ?: throw IllegalStateException("Recipient device is not present in the public key directory")
        // Re-check the exact bundle just fetched from Firebase. The coordinator's
        // earlier trust check is not sufficient by itself because the directory
        // could change between planning and prekey preparation.
        check(
            trustVerifier.isVerified(
                peerUserId = recipientUserId,
                peerDeviceId = recipientDeviceId,
                peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
            )
        ) {
            "Recipient device identity changed or has not been verified"
        }

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
        require(payload.recipientDeviceId == remoteDevice.deviceId) {
            "Authenticated payload recipient device does not match the selected device"
        }
        val envelope = sessions.encryptAuthenticatedEnvelope(
            sessionId = sessionId,
            payload = payload,
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
