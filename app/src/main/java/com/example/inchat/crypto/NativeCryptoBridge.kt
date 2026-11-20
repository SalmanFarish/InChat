package com.example.inchat.crypto

/**
 * Narrow Android boundary for the experimental native crypto core.
 *
 * This bridge is used by the isolated E2EE prototype. Private protocol state is
 * serialized in Rust and passed only as a transient byte array to the
 * Keystore-backed store. Plaintext is returned only to the calling app layer
 * after successful decryption; private keys and serialized ratchet state are
 * never returned to Kotlin.
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

    /**
     * Encrypt the current device identity for a specifically paired destination.
     * The source ID, destination ID, and transaction ID are authenticated as AAD.
     * The transfer key and nonce are consumed and cleared by native code.
     */
    external fun nativeExportDeviceIdentity(
        store: CryptoStateStore,
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        transferKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray?

    /**
     * Import an authenticated, transaction-bound transfer into an empty
     * destination store. Native code validates and rebinds the state, discards
     * source-device unpublished prekeys, and persists it through Android Keystore.
     */
    external fun nativeImportDeviceIdentity(
        store: CryptoStateStore,
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        transferKey: ByteArray,
        encryptedPayload: ByteArray,
    ): Boolean

    /**
     * Sign a destination receipt with the imported Ed25519 identity private key.
     * The signature binds the source, destination and transaction identifiers.
     */
    external fun nativeCreateDeviceTransferAcknowledgement(
        store: CryptoStateStore,
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
    ): ByteArray?

    /** Verify the context-bound receipt against the source identity public key. */
    external fun nativeVerifyDeviceTransferAcknowledgement(
        store: CryptoStateStore,
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        acknowledgement: ByteArray,
    ): Boolean

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

    /** Atomically stage an initial pre-key decrypt for a durable inbound receipt. */
    external fun nativePrepareInboundOlmSession(
        store: CryptoStateStore,
        deviceId: String,
        receiptId: String,
        remoteIdentityKey: String,
        wireMessage: ByteArray,
    ): String?

    /** Decrypt or recover staged plaintext for a normal Olm message receipt. */
    external fun nativePrepareOlmMessage(
        store: CryptoStateStore,
        deviceId: String,
        receiptId: String,
        sessionId: String,
        wireMessage: ByteArray,
    ): String?

    /** Clear staged plaintext after the Room message and receipt commit succeeds. */
    external fun nativeFinalizeInboundDecryption(
        store: CryptoStateStore,
        deviceId: String,
        receiptId: String,
    ): Boolean

    /** Ensure the local sender ratchet exists for exactly this membership epoch. */
    external fun nativeEnsureOutboundMegolmSession(
        store: CryptoStateStore,
        deviceId: String,
        groupId: String,
        membershipEpoch: Long,
        senderUserId: String,
    ): String?

    /** Export a Megolm session key; callers must distribute it only via authenticated Olm. */
    external fun nativeExportOutboundMegolmSessionKey(
        store: CryptoStateStore,
        deviceId: String,
        groupId: String,
        membershipEpoch: Long,
        senderUserId: String,
    ): ByteArray?

    /** Import a signed Megolm key received over authenticated Olm after membership authorization. */
    external fun nativeImportInboundMegolmSessionKey(
        store: CryptoStateStore,
        deviceId: String,
        groupId: String,
        membershipEpoch: Long,
        senderUserId: String,
        senderDeviceId: String,
        expectedSessionId: String,
        sessionKey: ByteArray,
    ): Boolean

    /** Encrypt a group message and persist the advanced sender ratchet before returning ciphertext. */
    external fun nativeEncryptOutboundMegolmMessage(
        store: CryptoStateStore,
        deviceId: String,
        groupId: String,
        membershipEpoch: Long,
        senderUserId: String,
        plaintext: String,
    ): String?

    /**
     * Decrypt an authenticated group ciphertext, atomically persisting the
     * advanced inbound Megolm ratchet and recoverable plaintext in native state.
     * The caller must have already authenticated the Olm payload and membership.
     */
    external fun nativePrepareInboundMegolmDecryption(
        store: CryptoStateStore,
        deviceId: String,
        receiptId: String,
        ownerUserId: String,
        groupId: String,
        membershipEpoch: Long,
        messageId: String,
        senderUserId: String,
        senderDeviceId: String,
        sessionId: String,
        expectedMessageIndex: Long,
        sentAtMillis: Long,
        ciphertext: ByteArray,
    ): String?

    /** Enumerate native pending Megolm stages for crash recovery after process death. */
    external fun nativeListPendingMegolmDecryptionReceipts(
        store: CryptoStateStore,
        deviceId: String,
    ): String?

    /** Read a pending staged Megolm plaintext without advancing its receive ratchet. */
    external fun nativeReadPendingMegolmDecryption(
        store: CryptoStateStore,
        deviceId: String,
        receiptId: String,
    ): String?

    /** Clear native staged plaintext only after encrypted Room and replay records commit. */
    external fun nativeFinalizeInboundMegolmDecryption(
        store: CryptoStateStore,
        deviceId: String,
        receiptId: String,
    ): Boolean

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

    /**
     * Start an isolated, process-local SAS verification attempt.
     *
     * Identity inputs must come from the authenticated local device and a
     * previously signature-verified remote device bundle. This method does not
     * persist trust or communicate over Firebase.
     *
     * Returns JSON containing an opaque handle and this device's commitment.
     */
    external fun nativeSasCreate(
        initiatorUserId: String,
        initiatorDeviceId: String,
        initiatorEd25519Key: String,
        responderUserId: String,
        responderDeviceId: String,
        responderEd25519Key: String,
        transactionId: String,
        localIsInitiator: Boolean,
    ): String?

    external fun nativeSasAcceptPeerCommitment(handle: String, commitment: String): Boolean
    external fun nativeSasRevealPublicKey(handle: String): String?
    external fun nativeSasAcceptPeerPublicKey(handle: String, publicKey: String): Boolean

    /** JSON contains seven emoji indices and three decimal comparison values. */
    external fun nativeSasDisplayCodes(handle: String): String?

    external fun nativeSasConfirmMatch(handle: String, matched: Boolean): Boolean
    external fun nativeSasCreateLocalMac(handle: String): String?
    external fun nativeSasVerifyPeerMac(handle: String, mac: String): Boolean
    external fun nativeSasIsComplete(handle: String): Boolean

    /** Cancelling drops the native ephemeral secret; handles are process-local. */
    external fun nativeSasCancel(handle: String): Boolean

}
