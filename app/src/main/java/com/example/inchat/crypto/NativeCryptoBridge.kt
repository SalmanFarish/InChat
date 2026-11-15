package com.example.inchat.crypto

/**
 * Narrow Android boundary for the experimental native crypto core.
 *
 * This bridge is currently used only by instrumentation tests. Private protocol
 * state is serialized in Rust and passed only as a transient byte array to the
 * Keystore-backed store for encryption/decryption. No private key or plaintext
 * serialized state is returned to Kotlin callers.
 */
internal object NativeCryptoBridge {

    init {
        System.loadLibrary("inchat_crypto_core")
    }

    external fun nativeVodozemacVersion(): String

    /**
     * Create or restore this installation's Olm identity and return only its public
     * identity/prekey bundle. Private account state remains in Rust and is saved
     * through the Android Keystore-backed store.
     */
    external fun nativeEnsureDeviceIdentity(
        store: CryptoStateStore,
        deviceId: String,
    ): String?

    /** Verify a remote public device bundle and all its Ed25519 signatures in Rust. */
    external fun nativeVerifyDeviceKeyBundle(encodedBundle: String): Boolean

    /** Mark generated device prekeys as published only after the directory upload succeeds. */
    external fun nativeMarkDeviceKeysPublished(
        store: CryptoStateStore,
        deviceId: String,
    ): Boolean

    /** Create and persist an outbound Olm session; returns its opaque session ID. */
    external fun nativeCreateOutboundOlmSession(
        store: CryptoStateStore,
        deviceId: String,
        remoteIdentityKey: String,
        remoteOneTimeKey: String,
    ): String?

    /** Encrypt with a saved session; wire format is one message-type byte followed by ciphertext. */
    external fun nativeEncryptOlmMessage(
        store: CryptoStateStore,
        deviceId: String,
        sessionId: String,
        plaintext: String,
    ): ByteArray?

    /** Establish an inbound session and atomically save prekey consumption plus session state. */
    external fun nativeCreateInboundOlmSession(
        store: CryptoStateStore,
        deviceId: String,
        remoteIdentityKey: String,
        wireMessage: ByteArray,
    ): String?

    /** Decrypt a normal Olm message and persist the advanced receive ratchet before returning. */
    external fun nativeDecryptOlmMessage(
        store: CryptoStateStore,
        deviceId: String,
        sessionId: String,
        wireMessage: ByteArray,
    ): String?

    external fun nativeOlmSelfTest(): Boolean

    /** Create a temporary Olm account and persist its Rust pickle via Keystore. */
    external fun nativePersistAccountState(store: CryptoStateStore): String

    /** Restore that account from Keystore and compare its public identity keys. */
    external fun nativeRestoreAccountState(
        store: CryptoStateStore,
        expectedIdentityKeys: String
    ): Boolean

    /** Persist a test Olm session and return only the peer's encrypted reply. */
    external fun nativePersistOlmSessionState(store: CryptoStateStore): ByteArray

    /** Restore the saved test session and verify it decrypts the encrypted reply. */
    external fun nativeRestoreOlmSessionState(
        store: CryptoStateStore,
        encryptedReply: ByteArray,
    ): Boolean
}
