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

/** Device-local AES-GCM vault for messages waiting on group-key acknowledgements. */
internal class EncryptedGroupPendingMessageVault(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    fun encrypt(owner: String, group: String, id: String, sentAt: Long, text: String): EncryptedGroupPendingMessageEntity {
        validate(owner, group, id, sentAt, text)
        val plain = text.toByteArray(StandardCharsets.UTF_8)
        try {
            synchronized(LOCK) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val nonce = cipher.iv
                cipher.updateAAD(aad(owner, group, id, sentAt))
                val encrypted = cipher.doFinal(plain)
                try {
                    return EncryptedGroupPendingMessageEntity(
                        owner, group, id, sentAt, VERSION,
                        Base64.encodeToString(nonce, FLAGS),
                        Base64.encodeToString(encrypted, FLAGS),
                    )
                } finally {
                    nonce.fill(0)
                    encrypted.fill(0)
                }
            }
        } finally {
            plain.fill(0)
        }
    }

    fun decrypt(record: EncryptedGroupPendingMessageEntity): String {
        require(record.contentEncryptionVersion == VERSION) { "Unsupported pending group-message version" }
        validate(record.ownerUserId, record.groupId, record.messageId, record.sentAtMillis, "x")
        val nonce = decode(record.textNonceBase64)
        val encrypted = decode(record.textCiphertextBase64)
        require(nonce.size == NONCE_BYTES && encrypted.size in TAG_BYTES..MAX_CIPHER_BYTES)
        val plain = try {
            synchronized(LOCK) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(aad(record.ownerUserId, record.groupId, record.messageId, record.sentAtMillis))
                cipher.doFinal(encrypted)
            }
        } finally {
            nonce.fill(0)
            encrypted.fill(0)
        }
        try {
            require(plain.size <= MAX_TEXT_BYTES)
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain)).toString()
        } finally {
            plain.fill(0)
        }
    }

    private fun validate(owner: String, group: String, id: String, sentAt: Long, text: String) {
        require(ID.matches(owner) && ID.matches(group) && ID.matches(id) && sentAt >= 0L)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        try { require(text.isNotBlank() && bytes.size <= MAX_TEXT_BYTES) }
        finally { bytes.fill(0) }
    }

    private fun aad(owner: String, group: String, id: String, sentAt: Long): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeUTF("InChat encrypted pending Megolm group intent")
            it.writeInt(VERSION)
            it.writeUTF(owner)
            it.writeUTF(group)
            it.writeUTF(id)
            it.writeLong(sentAt)
        }
        return output.toByteArray()
    }

    private fun decode(value: String): ByteArray {
        require(value.isNotBlank() && value.length <= MAX_BASE64)
        val decoded = Base64.decode(value, FLAGS)
        require(Base64.encodeToString(decoded, FLAGS) == value)
        return decoded
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (store.containsAlias(keyAlias)) return store.getKey(keyAlias, null) as? SecretKey
            ?: throw GeneralSecurityException("Pending group-message key is invalid")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(
            keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!store.containsAlias(keyAlias)) throw GeneralSecurityException("Pending group-message key is missing")
        return store.getKey(keyAlias, null) as? SecretKey
            ?: throw GeneralSecurityException("Pending group-message key is invalid")
    }

    companion object {
        const val VERSION = 1
        const val DEFAULT_KEY_ALIAS = "com.example.inchat.crypto.pending-group-message.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val MAX_TEXT_BYTES = 64 * 1024
        private const val MAX_CIPHER_BYTES = MAX_TEXT_BYTES + TAG_BYTES
        private const val MAX_BASE64 = 90_000
        private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP
        private val LOCK = Any()
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
