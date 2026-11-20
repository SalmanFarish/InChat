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

/**
 * Encrypts local Olm message text with a non-exportable Android Keystore AES-GCM key.
 *
 * Routing and identity metadata are authenticated as AAD so ciphertext cannot be
 * moved to another account, conversation, message, sender, or recipient device.
 * The key is device-local and is never exported or sent to Firebase.
 */
internal class EncryptedOlmMessageVault(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    init {
        require(KEY_ALIAS_PATTERN.matches(keyAlias)) {
            "Encrypted Olm message key alias has an invalid format"
        }
    }

    /** Encrypt the message body before any Room write can persist it. */
    fun encrypt(
        message: DecryptedOlmMessage,
        allowKeyCreation: Boolean = true,
    ): EncryptedOlmMessageEntity {
        validateMessageMetadata(message)
        val plaintext = message.text.toByteArray(StandardCharsets.UTF_8)
        require(message.text.isNotEmpty()) {
            "Encrypted Olm message text cannot be empty"
        }
        require(plaintext.size <= MAX_TEXT_BYTES) {
            "Encrypted Olm message text exceeds the storage limit"
        }

        try {
            return synchronized(KEYSTORE_LOCK) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    if (allowKeyCreation) getOrCreateKey() else getExistingKey(),
                )
                val nonce = cipher.iv
                check(nonce != null && nonce.size == GCM_NONCE_BYTES) {
                    "Android Keystore returned an unexpected AES-GCM nonce"
                }
                cipher.updateAAD(associatedData(message))
                val ciphertext = cipher.doFinal(plaintext)
                try {
                    EncryptedOlmMessageEntity(
                        ownerUserId = message.ownerUserId,
                        conversationId = message.conversationId,
                        messageId = message.messageId,
                        conversationPeerUserId = message.conversationPeerUserId,
                        senderUserId = message.senderUserId,
                        senderDeviceId = message.senderDeviceId,
                        recipientUserId = message.recipientUserId,
                        recipientDeviceId = message.recipientDeviceId,
                        sentAtMillis = message.sentAtMillis,
                        contentEncryptionVersion = CONTENT_ENCRYPTION_VERSION,
                        textNonceBase64 = Base64.encodeToString(
                            nonce,
                            BASE64_FLAGS,
                        ),
                        textCiphertextBase64 = Base64.encodeToString(
                            ciphertext,
                            BASE64_FLAGS,
                        ),
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

    /** Decrypt only after the record's metadata has been validated by the caller. */
    fun decrypt(record: EncryptedOlmMessageEntity): DecryptedOlmMessage {
        require(record.contentEncryptionVersion == CONTENT_ENCRYPTION_VERSION) {
            "Unsupported encrypted Olm message content version"
        }
        val metadata = record.toMetadata()
        validateMessageMetadata(metadata)

        val nonce = decodeCanonicalBase64(record.textNonceBase64, "message nonce")
        val ciphertext = decodeCanonicalBase64(record.textCiphertextBase64, "message ciphertext")
        require(nonce.size == GCM_NONCE_BYTES) {
            "Encrypted Olm message nonce has an invalid size"
        }
        require(ciphertext.size in GCM_TAG_BYTES..(MAX_TEXT_BYTES + GCM_TAG_BYTES)) {
            "Encrypted Olm message ciphertext has an invalid size"
        }

        val plaintext = try {
            synchronized(KEYSTORE_LOCK) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getExistingKey(),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                cipher.updateAAD(associatedData(metadata))
                cipher.doFinal(ciphertext)
            }
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }

        try {
            require(plaintext.size <= MAX_TEXT_BYTES) {
                "Decrypted Olm message text exceeds the storage limit"
            }
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString()
            return metadata.copy(text = text)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw GeneralSecurityException(
                    "Stored encrypted Olm message key is not an AES secret key",
                )
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
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** Never silently recreate a missing key while reading existing ciphertext. */
    private fun getExistingKey(): SecretKey {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(keyAlias)) {
            throw GeneralSecurityException(
                "Encrypted Olm message data exists but its Android Keystore key is missing",
            )
        }
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw GeneralSecurityException(
                "Stored encrypted Olm message key is not an AES secret key",
            )
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun validateMessageMetadata(message: DecryptedOlmMessage) {
        require(validAccountId(message.ownerUserId)) { "Message owner account ID is invalid" }
        require(validConversationId(message.conversationId)) { "Message conversation ID is invalid" }
        require(validMessageId(message.messageId)) { "Message ID is invalid" }
        require(validAccountId(message.conversationPeerUserId)) { "Conversation peer account ID is invalid" }
        require(validAccountId(message.senderUserId)) { "Message sender account ID is invalid" }
        require(DEVICE_ID_PATTERN.matches(message.senderDeviceId)) { "Message sender device ID is invalid" }
        require(validAccountId(message.recipientUserId)) { "Message recipient account ID is invalid" }
        require(DEVICE_ID_PATTERN.matches(message.recipientDeviceId)) { "Message recipient device ID is invalid" }
        require(message.ownerUserId == message.recipientUserId) {
            "Encrypted message owner must match its recipient account"
        }
        require(message.sentAtMillis >= 0L) { "Message timestamp cannot be negative" }
    }

    private fun EncryptedOlmMessageEntity.toMetadata() = DecryptedOlmMessage(
        ownerUserId = ownerUserId,
        conversationId = conversationId,
        messageId = messageId,
        conversationPeerUserId = conversationPeerUserId,
        senderUserId = senderUserId,
        senderDeviceId = senderDeviceId,
        recipientUserId = recipientUserId,
        recipientDeviceId = recipientDeviceId,
        sentAtMillis = sentAtMillis,
        text = "",
    )

    private fun associatedData(message: DecryptedOlmMessage): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUTF(AAD_CONTEXT)
            data.writeInt(CONTENT_ENCRYPTION_VERSION)
            data.writeUTF(message.ownerUserId)
            data.writeUTF(message.conversationId)
            data.writeUTF(message.messageId)
            data.writeUTF(message.conversationPeerUserId)
            data.writeUTF(message.senderUserId)
            data.writeUTF(message.senderDeviceId)
            data.writeUTF(message.recipientUserId)
            data.writeUTF(message.recipientDeviceId)
            data.writeLong(message.sentAtMillis)
        }
        return output.toByteArray()
    }

    private fun decodeCanonicalBase64(value: String, field: String): ByteArray {
        require(value.isNotBlank() && value.length <= MAX_BASE64_LENGTH) {
            "Encrypted Olm message $field is invalid"
        }
        val decoded = try {
            Base64.decode(value, BASE64_FLAGS)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Encrypted Olm message $field is not valid Base64", error)
        }
        require(Base64.encodeToString(decoded, BASE64_FLAGS) == value) {
            "Encrypted Olm message $field is not canonical Base64"
        }
        return decoded
    }

    private fun validAccountId(value: String): Boolean =
        ACCOUNT_ID_PATTERN.matches(value)

    private fun validConversationId(value: String): Boolean =
        value.length in 3..MAX_CONVERSATION_ID_LENGTH &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun validMessageId(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_MESSAGE_ID_LENGTH &&
            value.all { it.isLetterOrDigit() || it in "._:-" }

    companion object {
        internal const val DEFAULT_KEY_ALIAS =
            "com.example.inchat.crypto.encrypted-olm-message.v1"
        internal const val CONTENT_ENCRYPTION_VERSION = 1
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val GCM_NONCE_BYTES = 12
        private const val MAX_TEXT_BYTES = 64 * 1024
        private const val MAX_BASE64_LENGTH = ((MAX_TEXT_BYTES + GCM_TAG_BYTES) * 4 / 3) + 16
        private const val MAX_CONVERSATION_ID_LENGTH = 260
        private const val MAX_MESSAGE_ID_LENGTH = 256
        private const val AAD_CONTEXT = "InChat encrypted Olm local message"
        private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP
        private val ACCOUNT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val KEY_ALIAS_PATTERN = Regex(
            "com[.]example[.]inchat[.]crypto[.]encrypted-olm-message[.][A-Za-z0-9_-]{1,64}",
        )
        private val KEYSTORE_LOCK = Any()
    }
}
