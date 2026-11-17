package com.example.inchat.crypto

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** One public Olm prekey, signed by the device's Ed25519 identity key. */
internal data class SignedOlmPreKey(
    val keyKind: String,
    val keyId: String,
    val publicKey: String,
    val signature: String,
)

/** Public-only device bundle. No private key or Olm pickle is represented here. */
internal data class PublicDeviceKeyBundle(
    val deviceId: String,
    val ed25519IdentityKey: String,
    val curve25519IdentityKey: String,
    val curve25519IdentityKeySignature: String,
    val fallbackKey: SignedOlmPreKey?,
    val oneTimeKeys: List<SignedOlmPreKey>,
    /** Server timestamp updated while this installation is active. */
    val lastSeenAtMillis: Long? = null,
)

/** Durable, non-secret metadata used to resume a transfer after process death. */
internal data class PendingDeviceIdentityTransfer(
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val transactionId: String?,
    val ownerUserId: String?,
    val sourceIdentityFingerprint: String?,
    val exported: Boolean,
    val acknowledged: Boolean,
    val retiring: Boolean,
    val retired: Boolean,
)

/**
 * Owns the installation-scoped identifier and the matching native Olm account.
 *
 * The identifier and encrypted native account live in noBackupFilesDir. A normal
 * process restart keeps the same device identity; uninstall/reinstall creates a
 * new installation identity instead of attempting to reuse an unavailable
 * Android Keystore key.
 *
 * This repository is not connected to login, Firebase, or live conversations yet.
 */
internal class DeviceIdentityRepository(context: Context) {
    private val appContext = context.applicationContext
    private val identityDirectory = File(appContext.noBackupFilesDir, DIRECTORY_NAME)
    private val deviceIdFile = AtomicFile(File(identityDirectory, DEVICE_ID_FILE))
    private val transferJournal = DeviceTransferJournal(appContext)

    /** Create the local native identity if needed and return its public key bundle. */
    fun ensurePublicKeyBundle(): PublicDeviceKeyBundle {
        val deviceId = getOrCreateDeviceId()
        transferJournal.requireUnlocked(deviceId)
        val stateStore = CryptoStateStore(appContext, "device-$deviceId")
        val encodedBundle = NativeCryptoBridge.nativeEnsureDeviceIdentity(stateStore, deviceId)
            ?: throw IllegalStateException("Native Olm device identity could not be loaded")
        return parsePublicBundle(encodedBundle, deviceId)
    }

    /** Return this installation's stable device ID without exposing private state. */
    fun currentDeviceId(): String = getOrCreateDeviceId()

    /** Re-check the transfer lock even when a caller has cached this identity. */
    fun requireIdentityUsable() {
        transferJournal.requireUnlocked(getOrCreateDeviceId())
    }

    /**
     * Persistently lock this source identity before exporting it. Bind the transfer
     * to the signed-in account and the source Ed25519 identity fingerprint so the
     * destination can be checked before the old directory entry is revoked.
     */
    fun beginIdentityTransfer(
        targetDeviceId: String,
        ownerUserId: String,
        transactionId: String,
    ): String {
        require(Regex("[A-Za-z0-9_-]{16,64}").matches(transactionId)) {
            "Device transfer transaction ID has an invalid format"
        }
        val sourceBundle = ensurePublicKeyBundle()
        val sourceDeviceId = sourceBundle.deviceId
        require(targetDeviceId != sourceDeviceId) {
            "A device identity cannot be transferred to itself"
        }
        val identityFingerprint = fingerprint(sourceBundle.ed25519IdentityKey)
        transferJournal.begin(
            sourceDeviceId,
            targetDeviceId,
            transactionId,
            ownerUserId,
            identityFingerprint,
        )
        return transactionId
    }

    /** Cancel only a pending transfer belonging to this installation. */
    fun cancelIdentityTransfer() {
        transferJournal.cancel(getOrCreateDeviceId())
    }

    /** Metadata-only snapshot for recovering a pending transfer after process death. */
    fun pendingIdentityTransfer(): PendingDeviceIdentityTransfer? =
        transferJournal.pending()?.let { pending ->
            PendingDeviceIdentityTransfer(
                sourceDeviceId = pending.sourceDeviceId,
                targetDeviceId = pending.targetDeviceId,
                transactionId = pending.transactionId,
                ownerUserId = pending.ownerUserId,
                sourceIdentityFingerprint = pending.sourceIdentityFingerprint,
                exported = pending.exported,
                acknowledged = pending.acknowledged,
                retiring = pending.retiring,
                retired = pending.retired,
            )
        }

    fun hasPendingIdentityTransfer(): Boolean = transferJournal.pending() != null

    /**
     * Low-level transfer primitive. Do not expose it in UI until authenticated
     * pairing and the retirement workflow are integrated end to end.
     *
     * The export is marked durable before its bytes are returned to a transport.
     * The one-time key and nonce are wiped after the native call.
     */
    fun exportEncryptedIdentity(
        targetDeviceId: String,
        transactionId: String,
        transferKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        return try {
            val sourceDeviceId = getOrCreateDeviceId()
            require(targetDeviceId != sourceDeviceId) {
                "A device identity cannot be transferred to itself"
            }
            transferJournal.requirePendingTarget(sourceDeviceId, targetDeviceId, transactionId)
            val payload = NativeCryptoBridge.nativeExportDeviceIdentity(
                CryptoStateStore(appContext, "device-$sourceDeviceId"),
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                transferKey,
                nonce,
            ) ?: throw IllegalStateException("Could not encrypt the device identity transfer")

            try {
                // Persist this before returning bytes to any transport. After this
                // point the payload may have been delivered, so cancellation is unsafe.
                transferJournal.markExported(sourceDeviceId, targetDeviceId, transactionId)
            } catch (failure: Throwable) {
                payload.fill(0)
                throw failure
            }
            payload
        } finally {
            transferKey.fill(0)
            nonce.fill(0)
        }
    }

    /**
     * Low-level transfer primitive. After import, the destination should create
     * a context-bound signed receipt. The source must verify it and complete a separate
     * retirement protocol before either side treats migration as finished.
     * Native code refuses to overwrite an existing identity.
     */
    fun importEncryptedIdentity(
        sourceDeviceId: String,
        transactionId: String,
        transferKey: ByteArray,
        encryptedPayload: ByteArray,
    ) {
        try {
            val targetDeviceId = getOrCreateDeviceId()
            require(sourceDeviceId != targetDeviceId) {
                "A device identity cannot be transferred to itself"
            }
            check(
                NativeCryptoBridge.nativeImportDeviceIdentity(
                    CryptoStateStore(appContext, "device-$targetDeviceId"),
                    sourceDeviceId,
                    targetDeviceId,
                    transactionId,
                    transferKey,
                    encryptedPayload,
                ),
            ) {
                "Could not authenticate or import the device identity transfer"
            }
        } finally {
            transferKey.fill(0)
            encryptedPayload.fill(0)
        }
    }

    /**
     * Sign a context-bound receipt with the imported identity private key. The
     * source can verify it using its local public key. Since both stores retain
     * the same identity until retirement, this is not independent destination proof.
     */
    fun createIdentityTransferAcknowledgement(
        sourceDeviceId: String,
        transactionId: String,
    ): ByteArray {
        val targetDeviceId = getOrCreateDeviceId()
        require(sourceDeviceId != targetDeviceId) {
            "A device identity cannot acknowledge its own transfer"
        }
        return NativeCryptoBridge.nativeCreateDeviceTransferAcknowledgement(
            CryptoStateStore(appContext, "device-$targetDeviceId"),
            sourceDeviceId,
            targetDeviceId,
            transactionId,
        ) ?: throw IllegalStateException(
            "Destination identity is not ready to acknowledge this transfer",
        )
    }

    /**
     * Verify the context-bound signature against the source's still-local identity.
     * The accepted receipt digest is durably recorded; the signature is not independent
     * proof of a distinct destination because the identity key was copied during transfer.
     */
    fun acknowledgeIdentityTransfer(
        targetDeviceId: String,
        transactionId: String,
        acknowledgement: ByteArray,
    ): Boolean {
        return try {
            val sourceDeviceId = getOrCreateDeviceId()
            val receiptSha256 = MessageDigest.getInstance("SHA-256")
                .digest(acknowledgement)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val existing = transferJournal.pending()
            if (existing?.acknowledged == true) {
                return transferJournal.hasRecordedAcknowledgement(
                    sourceDeviceId,
                    targetDeviceId,
                    transactionId,
                    receiptSha256,
                )
            }
            transferJournal.requirePending(sourceDeviceId, targetDeviceId, transactionId)
            val verified = NativeCryptoBridge.nativeVerifyDeviceTransferAcknowledgement(
                CryptoStateStore(appContext, "device-$sourceDeviceId"),
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                acknowledgement,
            )
            if (verified) {
                transferJournal.markAcknowledged(
                    sourceDeviceId,
                    targetDeviceId,
                    transactionId,
                    receiptSha256,
                )
            }
            verified
        } finally {
            acknowledgement.fill(0)
        }
    }

    /**
     * Retire this source only after the coordinator has confirmed that the
     * destination bundle is published and the source directory entry is gone.
     * The journal records the irreversible decision before the Keystore key is
     * deleted, so a process death can safely resume the deletion.
     */
    fun retireIdentityTransfer(targetDeviceId: String, transactionId: String) {
        val sourceDeviceId = getOrCreateDeviceId()
        val pending = requireNotNull(transferJournal.pending()) {
            "No device transfer is pending"
        }
        require(
            pending.sourceDeviceId == sourceDeviceId &&
                pending.targetDeviceId == targetDeviceId &&
                pending.transactionId == transactionId &&
                pending.acknowledged &&
                pending.ownerUserId != null &&
                pending.sourceIdentityFingerprint != null
        ) {
            "Source retirement requires the verified, account-bound transfer"
        }

        transferJournal.markRetiring(sourceDeviceId, targetDeviceId, transactionId)
        CryptoStateStore(appContext, "device-$sourceDeviceId").delete()
        transferJournal.markRetired(sourceDeviceId, targetDeviceId, transactionId)
    }

    /**
     * Acknowledge publication only after Firebase confirms the public bundle upload.
     * If this fails, the same unpublished keys are returned on the next attempt.
     */
    fun markPublicKeyBundlePublished() {
        val deviceId = getOrCreateDeviceId()
        transferJournal.requireUnlocked(deviceId)
        val stateStore = CryptoStateStore(appContext, "device-$deviceId")
        check(NativeCryptoBridge.nativeMarkDeviceKeysPublished(stateStore, deviceId)) {
            "Native Olm device keys could not be marked as published"
        }
    }

    private fun getOrCreateDeviceId(): String = synchronized(DEVICE_ID_LOCK) {
        if (!identityDirectory.exists() && !identityDirectory.mkdirs()) {
            throw IOException("Could not create the InChat crypto directory")
        }

        try {
            val savedId = deviceIdFile.openRead().bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            if (!DEVICE_ID_REGEX.matches(savedId)) {
                throw IOException("Saved InChat device ID is malformed")
            }
            savedId
        } catch (_: FileNotFoundException) {
            // The device ID and encrypted Olm state are written separately. If the
            // ID file disappears while a UUID-named state file survives, generating
            // a new ID would orphan the old private identity and publish a duplicate.
            // Fail closed instead; a genuinely cleared app has neither file.
            if (hasOrphanedDeviceIdentityState()) {
                throw IOException(
                    "Encrypted device identity state exists, but its device ID is missing. " +
                        "Refusing to create a replacement identity.",
                )
            }

            val newId = UUID.randomUUID().toString()
            val output = deviceIdFile.startWrite()
            try {
                output.write(newId.toByteArray(Charsets.UTF_8))
                deviceIdFile.finishWrite(output)
            } catch (failure: Throwable) {
                deviceIdFile.failWrite(output)
                throw failure
            }
            newId
        }
    }

    /**
     * Detect an identity state whose matching device-id file has gone missing.
     * Only UUID-backed production identity files are considered; instrumentation
     * tests and other CryptoStateStore users may use unrelated state IDs.
     */
    private fun hasOrphanedDeviceIdentityState(): Boolean {
        val uuidStateFile = Regex(
            """device-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}[.]bin(?:[.]bak)?""",
        )
        return identityDirectory.listFiles()?.any { file ->
            uuidStateFile.matches(file.name)
        } == true
    }

    private fun fingerprint(identityKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(identityKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun parsePublicBundle(
        encoded: String,
        expectedDeviceId: String,
    ): PublicDeviceKeyBundle {
        val root = JSONObject(encoded)
        require(root.getInt("schemaVersion") == 1) {
            "Unsupported public Olm device-key bundle version"
        }
        val deviceId = root.getString("deviceId")
        require(deviceId == expectedDeviceId) {
            "Native Olm bundle belongs to a different device ID"
        }

        val identityKeys = root.getJSONObject("identityKeys")
        val fallback = root.optJSONObject("fallbackKey")?.let(::parseSignedPreKey)
        val oneTimeArray = root.getJSONArray("oneTimeKeys")
        val oneTimeKeys = buildList {
            for (index in 0 until oneTimeArray.length()) {
                add(parseSignedPreKey(oneTimeArray.getJSONObject(index)))
            }
        }

        return PublicDeviceKeyBundle(
            deviceId = deviceId,
            ed25519IdentityKey = identityKeys.getString("ed25519"),
            curve25519IdentityKey = identityKeys.getString("curve25519"),
            curve25519IdentityKeySignature = identityKeys.getString("curve25519Signature").also {
                require(it.isNotBlank()) { "Native Olm bundle has no identity-key signature" }
            },
            fallbackKey = fallback,
            oneTimeKeys = oneTimeKeys,
        )
    }

    private fun parseSignedPreKey(value: JSONObject): SignedOlmPreKey {
        val key = SignedOlmPreKey(
            keyKind = value.getString("keyKind"),
            keyId = value.getString("keyId"),
            publicKey = value.getString("key"),
            signature = value.getString("signature"),
        )
        require(key.keyKind == "one_time" || key.keyKind == "fallback") {
            "Native Olm bundle contains an unknown prekey kind"
        }
        require(
            key.keyId.isNotBlank() &&
                key.publicKey.isNotBlank() &&
                key.signature.isNotBlank(),
        ) {
            "Native Olm bundle contains an incomplete signed prekey"
        }
        return key
    }

    private companion object {
        const val DIRECTORY_NAME = "inchat-crypto"
        const val DEVICE_ID_FILE = "device-id"
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val DEVICE_ID_LOCK = Any()
    }
}
