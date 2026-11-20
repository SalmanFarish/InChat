package com.example.inchat.crypto

import android.content.Context
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** The first successfully decrypted message that establishes an inbound Olm session. */
internal data class InboundOlmMessage(
    val sessionId: String,
    val plaintext: String,
)

/** Plaintext staged in encrypted native state until the Room receipt commit succeeds. */
internal data class StagedInboundOlmMessage(
    val receiptId: String,
    val sessionId: String,
    val plaintext: String,
)

/**
 * App-facing wrapper for the native, encrypted-at-rest Olm session prototype.
 *
 * This repository is intentionally not connected to Firebase, chat sending,
 * chat receiving, or message persistence. Callers must obtain remote bundles
 * through DeviceKeyDirectoryRepository, which verifies their signatures.
 */
internal class OlmSessionRepository(
    context: Context,
    private val identityRepository: SasVerificationLocalIdentity = DeviceIdentityRepository(context.applicationContext),
) : OlmGroupKeyOutboundSessionRepository, OlmGroupMessageOutboundSessionRepository {
    private val appContext = context.applicationContext

    private val cachedLocalBundle: PublicDeviceKeyBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        identityRepository.ensurePublicKeyBundle()
    }

    // Do not let a repository instance created before transfer retain permission
    // to use the old identity after another component locks it for migration.
    private val localBundle: PublicDeviceKeyBundle
        get() {
            identityRepository.requireIdentityUsable()
            return cachedLocalBundle
        }

    private fun localStore(): CryptoStateStore =
        CryptoStateStore(appContext, "device-${localBundle.deviceId}")

    /** Serialize complete native state transitions, not only their file I/O. */
    private fun <T> withLocalStore(block: (CryptoStateStore) -> T): T {
        val store = localStore()
        return store.withExclusiveAccess { block(store) }
    }

    /** Create and persist an outbound session using a verified remote prekey. */
    override fun createOutboundSession(
        remoteDevice: PublicDeviceKeyBundle,
        remotePreKey: SignedOlmPreKey,
    ): String {
        verifyRemoteDevice(remoteDevice)
        require(remoteDevice.deviceId != localBundle.deviceId) {
            "Cannot create an Olm session with this same device"
        }
        require(remotePreKey.keyKind == "one_time" || remotePreKey.keyKind == "fallback") {
            "Remote Olm prekey has an unsupported kind"
        }
        val isPublishedByDevice = remoteDevice.oneTimeKeys.any {
            it.keyId == remotePreKey.keyId &&
                it.publicKey == remotePreKey.publicKey &&
                it.signature == remotePreKey.signature
        } || remoteDevice.fallbackKey?.let {
            it.keyId == remotePreKey.keyId &&
                it.publicKey == remotePreKey.publicKey &&
                it.signature == remotePreKey.signature
        } == true
        require(isPublishedByDevice) {
            "Remote Olm prekey does not belong to the supplied device bundle"
        }

        return requireNotNull(
            withLocalStore { store ->
                NativeCryptoBridge.nativeCreateOutboundOlmSession(
                    store,
                    localBundle.deviceId,
                    remoteDevice.curve25519IdentityKey,
                    remotePreKey.publicKey,
                )
            }
        ) {
            "Could not create the outbound Olm session"
        }
    }

    /**
     * Encrypt and wrap an Olm wire message for a specific recipient device.
     * The envelope is transport metadata plus ciphertext; it is not a signature.
     */
    fun encryptEnvelope(
        sessionId: String,
        recipientDeviceId: String,
        plaintext: String,
    ): String {
        require(DEVICE_ID_REGEX.matches(recipientDeviceId)) {
            "Recipient device ID has an invalid format"
        }
        require(recipientDeviceId != localBundle.deviceId) {
            "Cannot send an Olm envelope to this same device"
        }
        val wireMessage = encrypt(sessionId, plaintext)
        return EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = localBundle.deviceId,
                recipientDeviceId = recipientDeviceId,
                sessionId = sessionId,
                ciphertext = wireMessage,
            )
        )
    }

    /**
     * Establish an inbound session from an encoded version 1 envelope.
     * The remote bundle identity and both routing device IDs must agree.
     */
    fun createInboundSessionFromEnvelope(
        remoteDevice: PublicDeviceKeyBundle,
        encodedEnvelope: String,
    ): InboundOlmMessage {
        verifyRemoteDevice(remoteDevice)
        require(remoteDevice.deviceId != localBundle.deviceId) {
            "Cannot create an Olm session with this same device"
        }
        val envelope = EncryptedOlmEnvelopeCodec.decode(encodedEnvelope)
        require(envelope.senderDeviceId == remoteDevice.deviceId) {
            "Envelope sender does not match the verified remote device"
        }
        require(envelope.recipientDeviceId == localBundle.deviceId) {
            "Envelope is addressed to a different device"
        }
        val inbound = createInboundSession(remoteDevice, envelope.ciphertext)
        require(inbound.sessionId == envelope.sessionId) {
            "Envelope session ID does not match the established Olm session"
        }
        return inbound
    }

    /**
     * Stage the first pre-key message for a durable receipt. Native state saves
     * OTK consumption, the new session, and plaintext atomically.
     */
    fun prepareInboundSessionFromEnvelope(
        receiptId: String,
        remoteDevice: PublicDeviceKeyBundle,
        encodedEnvelope: String,
    ): StagedInboundOlmMessage {
        validateReceiptId(receiptId)
        verifyRemoteDevice(remoteDevice)
        require(remoteDevice.deviceId != localBundle.deviceId) {
            "Cannot create an Olm session with this same device"
        }
        val envelope = EncryptedOlmEnvelopeCodec.decode(encodedEnvelope)
        require(envelope.senderDeviceId == remoteDevice.deviceId) {
            "Envelope sender does not match the verified remote device"
        }
        require(envelope.recipientDeviceId == localBundle.deviceId) {
            "Envelope is addressed to a different device"
        }
        val encodedResult = requireNotNull(
            withLocalStore { store ->
                NativeCryptoBridge.nativePrepareInboundOlmSession(
                    store,
                    localBundle.deviceId,
                    receiptId,
                    remoteDevice.curve25519IdentityKey,
                    envelope.ciphertext,
                )
            }
        ) {
            "Could not stage the inbound pre-key message"
        }
        return parseStagedResult(encodedResult, receiptId, envelope.sessionId)
    }

    /** Stage or recover a normal message without advancing its receive ratchet twice. */
    fun prepareDecryption(
        receiptId: String,
        expectedSenderDeviceId: String,
        encodedEnvelope: String,
    ): StagedInboundOlmMessage {
        validateReceiptId(receiptId)
        require(DEVICE_ID_REGEX.matches(expectedSenderDeviceId)) {
            "Expected sender device ID has an invalid format"
        }
        val envelope = EncryptedOlmEnvelopeCodec.decode(encodedEnvelope)
        require(envelope.senderDeviceId == expectedSenderDeviceId) {
            "Envelope sender does not match the expected device"
        }
        require(envelope.recipientDeviceId == localBundle.deviceId) {
            "Envelope is addressed to a different device"
        }
        val encodedResult = requireNotNull(
            withLocalStore { store ->
                NativeCryptoBridge.nativePrepareOlmMessage(
                    store,
                    localBundle.deviceId,
                    receiptId,
                    envelope.sessionId,
                    envelope.ciphertext,
                )
            }
        ) {
            "Could not stage or recover the inbound Olm message"
        }
        if (encodedResult.startsWith("INCHAT_CRYPTO_ERROR:")) {
            throw IllegalStateException(encodedResult.removePrefix("INCHAT_CRYPTO_ERROR:"))
        }
        return parseStagedResult(encodedResult, receiptId, envelope.sessionId)
    }

    /** Call only after OlmInboundMessageCommitter has committed message + receipt. */
    fun finalizeInboundDecryption(receiptId: String): Boolean {
        validateReceiptId(receiptId)
        return withLocalStore { store ->
            NativeCryptoBridge.nativeFinalizeInboundDecryption(
                store,
                localBundle.deviceId,
                receiptId,
            )
        }
    }

    private fun parseStagedResult(
        encodedResult: String,
        expectedReceiptId: String,
        expectedSessionId: String,
    ): StagedInboundOlmMessage {
        val result = JSONObject(encodedResult)
        val receiptId = result.getString("receiptId")
        val sessionId = result.getString("sessionId")
        require(receiptId == expectedReceiptId) {
            "Native staged receipt ID does not match the requested receipt"
        }
        require(sessionId == expectedSessionId) {
            "Native staged session ID does not match the envelope"
        }
        return StagedInboundOlmMessage(
            receiptId = receiptId,
            sessionId = sessionId,
            plaintext = result.getString("plaintext"),
        )
    }

    private fun validateReceiptId(receiptId: String) {
        require(RECEIPT_ID_REGEX.matches(receiptId)) {
            "Inbound receipt ID must be a SHA-256 hex digest"
        }
    }

    /** Decrypt an encoded envelope only when it is addressed to this device. */
    fun decryptEnvelope(
        expectedSenderDeviceId: String,
        encodedEnvelope: String,
    ): String {
        require(expectedSenderDeviceId.isNotBlank() && expectedSenderDeviceId.length <= 64) {
            "Expected sender device ID has an invalid format"
        }
        val envelope = EncryptedOlmEnvelopeCodec.decode(encodedEnvelope)
        require(envelope.senderDeviceId == expectedSenderDeviceId) {
            "Envelope sender does not match the expected device"
        }
        require(envelope.recipientDeviceId == localBundle.deviceId) {
            "Envelope is addressed to a different device"
        }
        return decrypt(envelope.sessionId, envelope.ciphertext)
    }

    /** Installation-scoped sender device ID used inside authenticated payloads. */
    override val localDeviceId: String
        get() = localBundle.deviceId

    /** Encrypt a typed message whose application metadata is inside the Olm ciphertext. */
    fun encryptAuthenticatedMessage(
        sessionId: String,
        payload: OlmAuthenticatedMessagePayload,
    ): ByteArray {
        require(payload.senderDeviceId == localBundle.deviceId) {
            "Authenticated payload sender device does not match this installation"
        }
        return encrypt(sessionId, OlmAuthenticatedMessagePayloadCodec.encode(payload))
    }

    /** Encrypt and wrap a typed first message for its declared recipient device. */
    override fun encryptAuthenticatedEnvelope(
        sessionId: String,
        payload: OlmAuthenticatedMessagePayload,
    ): String {
        require(payload.senderDeviceId == localBundle.deviceId) {
            "Authenticated payload sender device does not match this installation"
        }
        return encryptEnvelope(
            sessionId = sessionId,
            recipientDeviceId = payload.recipientDeviceId,
            plaintext = OlmAuthenticatedMessagePayloadCodec.encode(payload),
        )
    }

    /**
     * Encrypt a per-device Megolm session-key distribution inside an established
     * Olm session. The signed Megolm key is a decryption capability, so callers
     * must use a SAS-trusted recipient device in the current group membership
     * snapshot and must never send this payload through a public key directory.
     */
    override fun encryptGroupSessionKeyDistributionEnvelope(
        sessionId: String,
        payload: OlmGroupSessionKeyDistributionPayload,
    ): String {
        require(payload.senderDeviceId == localBundle.deviceId) {
            "Group session-key sender device does not match this installation"
        }
        return encryptEnvelope(
            sessionId = sessionId,
            recipientDeviceId = payload.recipientDeviceId,
            plaintext = OlmGroupSessionKeyDistributionPayloadCodec.encode(payload),
        )
    }

    /** Encrypt a typed Megolm ciphertext payload inside an authenticated Olm envelope. */
    override fun encryptGroupMessageEnvelope(
        sessionId: String,
        payload: OlmGroupMessagePayload,
    ): String {
        require(payload.senderDeviceId == localBundle.deviceId) {
            "Group message sender device does not match this installation"
        }
        return encryptEnvelope(
            sessionId = sessionId,
            recipientDeviceId = payload.recipientDeviceId,
            plaintext = OlmGroupMessagePayloadCodec.encode(payload),
        )
    }

    /** Encrypt a message and persist the advanced sending ratchet before returning. */
    fun encrypt(sessionId: String, plaintext: String): ByteArray {
        validateSessionId(sessionId)
        require(plaintext.toByteArray(StandardCharsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Olm plaintext exceeds the 64 KiB limit"
        }
        return requireNotNull(
            withLocalStore { store ->
                NativeCryptoBridge.nativeEncryptOlmMessage(
                    store,
                    localBundle.deviceId,
                    sessionId,
                    plaintext,
                )
            }
        ) {
            "Could not encrypt the Olm message"
        }
    }

    /**
     * Establish an inbound session from an initial pre-key message.
     * Native code persists one-time-prekey consumption and session state together.
     */
    fun createInboundSession(
        remoteDevice: PublicDeviceKeyBundle,
        wireMessage: ByteArray,
    ): InboundOlmMessage {
        verifyRemoteDevice(remoteDevice)
        require(remoteDevice.deviceId != localBundle.deviceId) {
            "Cannot create an Olm session with this same device"
        }
        validateWireMessage(wireMessage)
        val encoded = requireNotNull(
            withLocalStore { store ->
                NativeCryptoBridge.nativeCreateInboundOlmSession(
                    store,
                    localBundle.deviceId,
                    remoteDevice.curve25519IdentityKey,
                    wireMessage,
                )
            }
        ) {
            "Could not establish the inbound Olm session"
        }
        val result = JSONObject(encoded)
        val sessionId = result.getString("sessionId")
        validateSessionId(sessionId)
        return InboundOlmMessage(
            sessionId = sessionId,
            plaintext = result.getString("plaintext"),
        )
    }

    /** Decrypt a normal Olm message and persist the receive ratchet before returning. */
    fun decrypt(sessionId: String, wireMessage: ByteArray): String {
        validateSessionId(sessionId)
        validateWireMessage(wireMessage)
        return requireNotNull(
            withLocalStore { store ->
                NativeCryptoBridge.nativeDecryptOlmMessage(
                    store,
                    localBundle.deviceId,
                    sessionId,
                    wireMessage,
                )
            }
        ) {
            "Could not decrypt the Olm message"
        }
    }

    /** Re-check signatures at the crypto boundary, even if the bundle came from cache. */
    private fun verifyRemoteDevice(device: PublicDeviceKeyBundle) {
        require(device.deviceId.isNotBlank() && device.deviceId.length <= 64) {
            "Remote device ID has an invalid format"
        }
        require(device.oneTimeKeys.size <= MAX_ONE_TIME_KEYS) {
            "Remote device bundle contains too many one-time prekeys"
        }
        val fallback = requireNotNull(device.fallbackKey) {
            "Remote device bundle has no fallback key"
        }
        val identity = JSONObject()
            .put("ed25519", device.ed25519IdentityKey)
            .put("curve25519", device.curve25519IdentityKey)
            .put("curve25519Signature", device.curve25519IdentityKeySignature)

        fun SignedOlmPreKey.toJson(): JSONObject = JSONObject()
            .put("keyKind", keyKind)
            .put("keyId", keyId)
            .put("key", publicKey)
            .put("signature", signature)

        val oneTime = org.json.JSONArray().apply {
            device.oneTimeKeys.forEach { put(it.toJson()) }
        }
        val encoded = JSONObject()
            .put("schemaVersion", 1)
            .put("deviceId", device.deviceId)
            .put("identityKeys", identity)
            .put("fallbackKey", fallback.toJson())
            .put("oneTimeKeys", oneTime)
            .toString()
        check(NativeCryptoBridge.nativeVerifyDeviceKeyBundle(encoded)) {
            "Remote device bundle failed cryptographic signature verification"
        }
    }

    private fun validateSessionId(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= MAX_SESSION_ID_LENGTH) {
            "Olm session ID has an invalid format"
        }
    }

    private fun validateWireMessage(wireMessage: ByteArray) {
        require(wireMessage.size in 2..MAX_WIRE_BYTES) {
            "Encrypted Olm message has an invalid size"
        }
    }

    private companion object {
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        const val MAX_WIRE_BYTES = 1024 * 1024
        const val MAX_SESSION_ID_LENGTH = 128
        const val MAX_ONE_TIME_KEYS = 20
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val RECEIPT_ID_REGEX = Regex("[A-Fa-f0-9]{64}")
    }
}
