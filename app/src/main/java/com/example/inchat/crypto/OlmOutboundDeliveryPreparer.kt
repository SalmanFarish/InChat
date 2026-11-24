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

/**
 * Server-authorized context for consuming a public one-time prekey. The claim
 * remains globally unique per device/key, while Firebase rules ensure the
 * claimant and key owner currently share this direct chat or group.
 */
internal data class OlmPreKeyClaimScope(
    val conversationType: String,
    val conversationId: String,
    val conversationPeerUserId: String? = null,
    val membershipEpoch: Long? = null,
) {
    fun toClaimRecord(claimantUid: String): Map<String, Any> {
        require(USER_ID_PATTERN.matches(claimantUid)) {
            "Prekey claim account has an invalid user ID"
        }
        return when (conversationType) {
            "direct" -> {
                val peerUid = requireNotNull(conversationPeerUserId) {
                    "Direct prekey claim is missing its conversation peer"
                }
                require(USER_ID_PATTERN.matches(peerUid) && peerUid != claimantUid) {
                    "Direct prekey claim peer is invalid"
                }
                require(
                    conversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(
                        claimantUid,
                        peerUid,
                    ) && membershipEpoch == null
                ) {
                    "Direct prekey claim scope does not match its participants"
                }
                mapOf(
                    "claimantUid" to claimantUid,
                    "conversationType" to conversationType,
                    "conversationId" to conversationId,
                    "conversationPeerUserId" to peerUid,
                )
            }
            "group" -> {
                require(
                    GROUP_ID_PATTERN.matches(conversationId) &&
                        conversationPeerUserId == null &&
                        membershipEpoch != null &&
                        membershipEpoch > 0L
                ) {
                    "Group prekey claim scope is invalid"
                }
                mapOf(
                    "claimantUid" to claimantUid,
                    "conversationType" to conversationType,
                    "conversationId" to conversationId,
                    "membershipEpoch" to requireNotNull(membershipEpoch),
                )
            }
            else -> error("Unsupported prekey claim conversation type")
        }
    }

    companion object {
        fun direct(conversationId: String, peerUserId: String) = OlmPreKeyClaimScope(
            conversationType = "direct",
            conversationId = conversationId,
            conversationPeerUserId = peerUserId,
        )

        fun group(groupId: String, membershipEpoch: Long) = OlmPreKeyClaimScope(
            conversationType = "group",
            conversationId = groupId,
            membershipEpoch = membershipEpoch,
        )

        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val GROUP_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/** Narrow key-directory contract needed to prepare a first Olm message. */
internal interface OlmOutboundKeyDirectory {
    val authenticatedUserId: String
    suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle>
    suspend fun claimOneTimePreKey(
        targetUid: String,
        deviceId: String,
        keyId: String,
        scope: OlmPreKeyClaimScope,
    ): Boolean
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
            trustVerifier.isTrustedForMessaging(
                peerUserId = recipientUserId,
                peerDeviceId = recipientDeviceId,
                peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
            )
        ) {
            "Recipient device identity changed. Verify its safety number before messaging"
        }

        var claimedPreKey: SignedOlmPreKey? = null
        for (candidate in remoteDevice.oneTimeKeys) {
            if (directory.claimOneTimePreKey(
                    recipientUserId,
                    recipientDeviceId,
                    candidate.keyId,
                    OlmPreKeyClaimScope.direct(
                        conversationId = payload.conversationId,
                        peerUserId = payload.conversationPeerUserId,
                    ),
                )) {
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
