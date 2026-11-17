package com.example.inchat.crypto

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
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

    /**
     * Persistently lock this source identity before exporting it. The lock survives
     * process death and blocks OlmSessionRepository through ensurePublicKeyBundle().
     */
    fun beginIdentityTransfer(targetDeviceId: String) {
        val sourceDeviceId = getOrCreateDeviceId()
        transferJournal.requireUnlocked(sourceDeviceId)
        transferJournal.begin(sourceDeviceId, targetDeviceId)
    }

    /** Cancel only a pending transfer belonging to this installation. */
    fun cancelIdentityTransfer() {
        transferJournal.cancel(getOrCreateDeviceId())
    }

    /** Exposes only whether this installation currently has a pending handoff. */
    fun hasPendingIdentityTransfer(): Boolean =
        transferJournal.pending()?.sourceDeviceId == getOrCreateDeviceId()

    /**
     * Low-level transfer primitive. Do not expose it in UI until pairing,
     * authenticated destination acknowledgement, and source retirement are
     * implemented as one complete handoff flow.
     *
     * The one-time key and nonce are wiped after the native call.
     */
    fun exportEncryptedIdentity(
        targetDeviceId: String,
        transferKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val sourceDeviceId = getOrCreateDeviceId()
        require(targetDeviceId != sourceDeviceId) {
            "A device identity cannot be transferred to itself"
        }
        transferJournal.requirePendingTarget(sourceDeviceId, targetDeviceId)
        return try {
            NativeCryptoBridge.nativeExportDeviceIdentity(
                CryptoStateStore(appContext, "device-$sourceDeviceId"),
                sourceDeviceId,
                targetDeviceId,
                transferKey,
                nonce,
            ) ?: throw IllegalStateException("Could not encrypt the device identity transfer")
        } finally {
            transferKey.fill(0)
            nonce.fill(0)
        }
    }

    /**
     * Low-level transfer primitive. The caller must verify the paired source
     * and complete the source-retirement handshake before treating this as a
     * finished migration. Native code refuses to overwrite an existing identity.
     */
    fun importEncryptedIdentity(
        sourceDeviceId: String,
        transferKey: ByteArray,
        encryptedPayload: ByteArray,
    ) {
        val targetDeviceId = getOrCreateDeviceId()
        require(sourceDeviceId != targetDeviceId) {
            "A device identity cannot be transferred to itself"
        }
        try {
            check(
                NativeCryptoBridge.nativeImportDeviceIdentity(
                    CryptoStateStore(appContext, "device-$targetDeviceId"),
                    sourceDeviceId,
                    targetDeviceId,
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
