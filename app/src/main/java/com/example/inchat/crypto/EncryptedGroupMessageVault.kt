package com.example.inchat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local AES-256-GCM vault for group message bodies. */
internal class EncryptedGroupMessageVault(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    init {
        require(KEY_ALIAS_PATTERN.matches(keyAlias)) { "Encrypted group-message key alias is invalid" }
    }

    fun encrypt(
        message: DecryptedGroupMessage,
        allowKeyCreation: Boolean = true,
    ): EncryptedGroupMessageEntity {
        validate(message)
        require(message.text.isNotEmpty()) { "Group message text cannot be empty" }
        val plaintext = message.text.toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= MAX_TEXT_BYTES) { "Group message text exceeds the storage limit" }
        try {
            return synchronized(KEYSTORE_LOCK) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, if (allowKeyCreation) getOrCreateKey() else getExistingKey())
                val nonce = cipher.iv
                check(nonce != null && nonce.size == NONCE_BYTES) {
                    "Android Keystore returned an unexpected AES-GCM nonce"
                }
                cipher.updateAAD(associatedData(message))
                val ciphertext = cipher.doFinal(plaintext)
                try {
                    EncryptedGroupMessageEntity(
                        ownerUserId = message.ownerUserId,
                        groupId = message.groupId,
                        messageId = message.messageId,
                        membershipEpoch = message.membershipEpoch,
                        senderUserId = message.senderUserId,
                        senderDeviceId = message.senderDeviceId,
                        megolmSessionId = message.megolmSessionId,
                        messageIndex = message.messageIndex,
                        sentAtMillis = message.sentAtMillis,
                        contentEncryptionVersion = CONTENT_VERSION,
                        textNonceBase64 = Base64.encodeToString(nonce, BASE64_FLAGS),
                        textCiphertextBase64 = Base64.encodeToString(ciphertext, BASE64_FLAGS),
                        notificationPending = message.senderUserId != message.ownerUserId,
                    )
                } finally {
                    nonce.fill(0)
                    ciphertext.fill(0)
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun decrypt(record: EncryptedGroupMessageEntity): DecryptedGroupMessage {
        require(record.contentEncryptionVersion == CONTENT_VERSION) {
            "Unsupported encrypted group-message content version"
        }
        val metadata = DecryptedGroupMessage(
            ownerUserId = record.ownerUserId,
            groupId = record.groupId,
            messageId = record.messageId,
            membershipEpoch = record.membershipEpoch,
            senderUserId = record.senderUserId,
            senderDeviceId = record.senderDeviceId,
            megolmSessionId = record.megolmSessionId,
            messageIndex = record.messageIndex,
            sentAtMillis = record.sentAtMillis,
            text = "",
        )
        validate(metadata)
        val nonce = decode(record.textNonceBase64)
        val ciphertext = decode(record.textCiphertextBase64)
        require(nonce.size == NONCE_BYTES) { "Group message nonce has an invalid size" }
        require(ciphertext.size in TAG_BYTES..(MAX_TEXT_BYTES + TAG_BYTES)) {
            "Group message ciphertext has an invalid size"
        }
        val plaintext = try {
            synchronized(KEYSTORE_LOCK) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(associatedData(metadata))
                cipher.doFinal(ciphertext)
            }
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }
        try {
            require(plaintext.size <= MAX_TEXT_BYTES) { "Decrypted group text exceeds the storage limit" }
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext)).toString()
            return metadata.copy(text = text)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validate(message: DecryptedGroupMessage) {
        require(ID.matches(message.ownerUserId) && ID.matches(message.senderUserId)) {
            "Group account ID is invalid"
        }
        require(ID.matches(message.groupId) && ID.matches(message.messageId)) {
            "Group or message ID is invalid"
        }
        require(DEVICE_ID.matches(message.senderDeviceId)) { "Group sender device ID is invalid" }
        require(SESSION_ID.matches(message.megolmSessionId)) { "Megolm session ID is invalid" }
        require(message.membershipEpoch > 0L && message.messageIndex in 0L..0xffff_ffffL) {
            "Group epoch or message index is invalid"
        }
        require(message.sentAtMillis >= 0L) { "Group message timestamp is invalid" }
    }

    private fun associatedData(message: DecryptedGroupMessage): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUTF(AAD_CONTEXT)
            data.writeInt(CONTENT_VERSION)
            data.writeUTF(message.ownerUserId)
            data.writeUTF(message.groupId)
            data.writeUTF(message.messageId)
            data.writeLong(message.membershipEpoch)
            data.writeUTF(message.senderUserId)
            data.writeUTF(message.senderDeviceId)
            data.writeUTF(message.megolmSessionId)
            data.writeLong(message.messageIndex)
            data.writeLong(message.sentAtMillis)
        }
        return output.toByteArray()
    }

    private fun decode(value: String): ByteArray {
        require(value.isNotBlank() && value.length <= MAX_BASE64_LENGTH) {
            "Encrypted group-message field is invalid"
        }
        val decoded = try { Base64.decode(value, BASE64_FLAGS) }
        catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Encrypted group-message field is not valid Base64", error)
        }
        require(Base64.encodeToString(decoded, BASE64_FLAGS) == value) {
            "Encrypted group-message field is not canonical Base64"
        }
        return decoded
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(keyAlias)) {
            return store.getKey(keyAlias, null) as? SecretKey
                ?: throw GeneralSecurityException("Stored group-message key is not AES")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!store.containsAlias(keyAlias)) {
            throw GeneralSecurityException("Encrypted group messages exist but their Keystore key is missing")
        }
        return store.getKey(keyAlias, null) as? SecretKey
            ?: throw GeneralSecurityException("Stored group-message key is not AES")
    }

    companion object {
        const val CONTENT_VERSION = 1
        const val DEFAULT_KEY_ALIAS = "com.example.inchat.crypto.encrypted-group-message.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val MAX_TEXT_BYTES = 64 * 1024
        private const val MAX_BASE64_LENGTH = ((MAX_TEXT_BYTES + TAG_BYTES) * 4 / 3) + 16
        private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP
        private const val AAD_CONTEXT = "InChat encrypted Megolm group message"
        private val KEYSTORE_LOCK = Any()
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
        private val KEY_ALIAS_PATTERN = Regex(
            "com[.]example[.]inchat[.]crypto[.]encrypted-group-message[.][A-Za-z0-9_-]{1,64}",
        )
    }
}
