package com.example.inchat.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores an opaque native-crypto state blob encrypted with a non-exportable
 * Android Keystore AES-GCM key.
 *
 * The class deliberately knows nothing about Olm, messages, or Firebase.
 * Callers must pass only serialized crypto state, never message plaintext.
 * State files live in noBackupFilesDir because a restored ciphertext would
 * not have its device-bound Android Keystore key on another installation.
 */
internal class CryptoStateStore(
    context: Context,
    private val stateId: String,
) {
    private val appContext = context.applicationContext
    private val stateDirectory = File(appContext.noBackupFilesDir, DIRECTORY_NAME)
    private val stateFile = File(stateDirectory, "$stateId.bin")
    private val atomicFile = AtomicFile(stateFile)
    private val keyAlias = "$KEY_ALIAS_PREFIX$stateId.v1"

    init {
        require(STATE_ID_REGEX.matches(stateId)) {
            "stateId must contain only letters, numbers, hyphens, or underscores"
        }
    }

    /**
     * Run a complete native read-modify-write operation under the same process-wide
     * monitor used by read/write/delete. Olm calls load a state snapshot, advance
     * account/session ratchets, then persist it; locking only file I/O would allow
     * concurrent callers to overwrite one another's state updates.
     *
     * The monitor is reentrant because JNI calls back into readForNative/writeForNative
     * on this same thread.
     */
    internal fun <T> withExclusiveAccess(block: () -> T): T =
        synchronized(KEYSTORE_LOCK) { block() }

    /** Encrypt and atomically persist an opaque state blob. */
    fun write(plaintext: ByteArray) {
        require(plaintext.isNotEmpty()) { "Crypto state must not be empty" }
        require(plaintext.size <= MAX_STATE_BYTES) { "Crypto state is too large" }

        synchronized(KEYSTORE_LOCK) {
            val key = getOrCreateKeyForWrite()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            check(iv != null && iv.size == GCM_IV_BYTES) {
                "Android Keystore returned an unexpected AES-GCM IV"
            }

            cipher.updateAAD(associatedData())
            val ciphertext = cipher.doFinal(plaintext)
            val envelope = ByteArray(HEADER.size + iv.size + ciphertext.size)
            System.arraycopy(HEADER, 0, envelope, 0, HEADER.size)
            System.arraycopy(iv, 0, envelope, HEADER.size, iv.size)
            System.arraycopy(
                ciphertext,
                0,
                envelope,
                HEADER.size + iv.size,
                ciphertext.size,
            )

            if (!stateDirectory.exists() && !stateDirectory.mkdirs()) {
                throw IOException("Could not create crypto state directory")
            }

            val output = atomicFile.startWrite()
            try {
                output.write(envelope)
                atomicFile.finishWrite(output)
            } catch (failure: Throwable) {
                atomicFile.failWrite(output)
                throw failure
            }
        }
    }

    /**
     * Native-only persistence entry point. The JNI caller's temporary byte array
     * is wiped after the authenticated write attempt, including failure paths.
     */
    fun writeForNative(plaintext: ByteArray) {
        try {
            write(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Native-only read entry point. JNI copies the returned bytes into Rust and
     * clears this Java array immediately after copying it.
     */
    fun readForNative(): ByteArray? = read()

    /**
     * Read and authenticate the state blob.
     *
     * Returns null only when no state file exists. Missing keys, malformed
     * envelopes, and authentication failures are errors and are never treated
     * as an empty/new account.
     */
    fun read(): ByteArray? = synchronized(KEYSTORE_LOCK) {
        val stored = try {
            atomicFile.openRead().use(::readBounded)
        } catch (_: FileNotFoundException) {
            return@synchronized null
        }

        if (stored.size < HEADER.size + GCM_IV_BYTES + GCM_TAG_BYTES) {
            throw IOException("Crypto state envelope is truncated")
        }
        if (!stored.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw IOException("Crypto state envelope has an unsupported format")
        }

        val key = getExistingKeyForRead()
        val ivStart = HEADER.size
        val iv = stored.copyOfRange(ivStart, ivStart + GCM_IV_BYTES)
        val ciphertext = stored.copyOfRange(ivStart + GCM_IV_BYTES, stored.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData())
        cipher.doFinal(ciphertext)
    }

    /** Remove this state file and its device-bound key. */
    fun delete() {
        synchronized(KEYSTORE_LOCK) {
            atomicFile.delete()
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }
    }

    private fun getOrCreateKeyForWrite(): SecretKey {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw GeneralSecurityException("Stored crypto key is not an AES secret key")
        }

        // Never silently replace a missing key: doing so would make an existing
        // ciphertext undecryptable and could hide a device-keystore failure.
        if (hasStateFile()) {
            throw GeneralSecurityException("Crypto state exists but its Android Keystore key is missing")
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKeyForRead(): SecretKey {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(keyAlias)) {
            throw GeneralSecurityException("Crypto state exists but its Android Keystore key is missing")
        }
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw GeneralSecurityException("Stored crypto key is not an AES secret key")
    }

    private fun hasStateFile(): Boolean =
        stateFile.exists() || File(stateFile.path + ".bak").exists()

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun associatedData(): ByteArray =
        HEADER + stateId.toByteArray(Charsets.UTF_8)

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_ENVELOPE_BYTES) {
                throw IOException("Crypto state envelope is too large")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DIRECTORY_NAME = "inchat-crypto"
        const val KEY_ALIAS_PREFIX = "com.example.inchat.crypto.state."
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MAX_STATE_BYTES = 8 * 1024 * 1024
        const val MAX_ENVELOPE_BYTES = MAX_STATE_BYTES + 64

        val HEADER = byteArrayOf(
            'I'.code.toByte(),
            'C'.code.toByte(),
            'S'.code.toByte(),
            'T'.code.toByte(),
            1,
        )
        val STATE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val KEYSTORE_LOCK = Any()
    }
}
