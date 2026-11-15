package com.example.inchat.crypto

import android.content.Context
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** The first successfully decrypted message that establishes an inbound Olm session. */
internal data class InboundOlmMessage(
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
internal class OlmSessionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val identityRepository = DeviceIdentityRepository(appContext)

    private val localBundle: PublicDeviceKeyBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        identityRepository.ensurePublicKeyBundle()
    }

    private fun localStore(): CryptoStateStore =
        CryptoStateStore(appContext, "device-${localBundle.deviceId}")

    /** Create and persist an outbound session using a verified remote prekey. */
    fun createOutboundSession(
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
            NativeCryptoBridge.nativeCreateOutboundOlmSession(
                localStore(),
                localBundle.deviceId,
                remoteDevice.curve25519IdentityKey,
                remotePreKey.publicKey,
            )
        ) {
            "Could not create the outbound Olm session"
        }
    }

    /** Encrypt a message and persist the advanced sending ratchet before returning. */
    fun encrypt(sessionId: String, plaintext: String): ByteArray {
        validateSessionId(sessionId)
        require(plaintext.toByteArray(StandardCharsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Olm plaintext exceeds the 64 KiB limit"
        }
        return requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                localStore(),
                localBundle.deviceId,
                sessionId,
                plaintext,
            )
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
            NativeCryptoBridge.nativeCreateInboundOlmSession(
                localStore(),
                localBundle.deviceId,
                remoteDevice.curve25519IdentityKey,
                wireMessage,
            )
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
            NativeCryptoBridge.nativeDecryptOlmMessage(
                localStore(),
                localBundle.deviceId,
                sessionId,
                wireMessage,
            )
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
    }
}
