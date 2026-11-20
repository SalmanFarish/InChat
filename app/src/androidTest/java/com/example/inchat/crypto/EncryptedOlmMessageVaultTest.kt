package com.example.inchat.crypto

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedOlmMessageVaultTest {
    @Test
    fun bodyIsEncryptedWithFreshNonceAndSurvivesVaultRecreation() {
        val alias = newAlias()
        val firstVault = EncryptedOlmMessageVault(alias)
        try {
            val plaintext = message()
            val encrypted = firstVault.encrypt(plaintext)
            val secondEncrypted = firstVault.encrypt(plaintext)

            assertEquals(EncryptedOlmMessageVault.CONTENT_ENCRYPTION_VERSION, encrypted.contentEncryptionVersion)
            assertNotEquals(encrypted.textNonceBase64, secondEncrypted.textNonceBase64)
            assertFalse(encrypted.textCiphertextBase64.contains(plaintext.text))
            assertFalse(encrypted.toString().contains(plaintext.text))

            val reopenedVault = EncryptedOlmMessageVault(alias)
            assertEquals(plaintext, reopenedVault.decrypt(encrypted))
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun allMessageRoutingMetadataIsAuthenticatedAsGcmAssociatedData() {
        val alias = newAlias()
        val vault = EncryptedOlmMessageVault(alias)
        try {
            val encrypted = vault.encrypt(message())

            val tamperedRecords = listOf(
                encrypted.copy(ownerUserId = "charlie", recipientUserId = "charlie"),
                encrypted.copy(conversationId = "alice_someone-else"),
                encrypted.copy(messageId = "different-message"),
                encrypted.copy(conversationPeerUserId = "different-peer"),
                encrypted.copy(senderUserId = "mallory"),
                encrypted.copy(senderDeviceId = "different-sender-device"),
                encrypted.copy(ownerUserId = "charlie", recipientUserId = "charlie"),
                encrypted.copy(recipientDeviceId = "different-recipient-device"),
                encrypted.copy(sentAtMillis = encrypted.sentAtMillis + 1L),
            )
            tamperedRecords.forEach { changed ->
                expectSecurityFailure { vault.decrypt(changed) }
            }
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun modifiedCiphertextCannotBeDecrypted() {
        val alias = newAlias()
        val vault = EncryptedOlmMessageVault(alias)
        try {
            val encrypted = vault.encrypt(message())
            val bytes = Base64.decode(encrypted.textCiphertextBase64, Base64.URL_SAFE or Base64.NO_WRAP)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            val modified = encrypted.copy(
                textCiphertextBase64 = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP),
            )
            bytes.fill(0)
            expectSecurityFailure { vault.decrypt(modified) }
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun decryptionFailsClosedWhenDeviceKeystoreKeyIsMissing() {
        val alias = newAlias()
        val vault = EncryptedOlmMessageVault(alias)
        val encrypted = vault.encrypt(message())
        try {
            deleteKey(alias)
            var rejected = false
            try {
                vault.decrypt(encrypted)
            } catch (expected: GeneralSecurityException) {
                rejected = expected.message.orEmpty().contains("key is missing")
            }
            assertTrue("A missing device key must not be recreated while reading ciphertext", rejected)
            assertFalse(keyExists(alias))
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun unknownContentEncryptionVersionIsRejected() {
        val alias = newAlias()
        val vault = EncryptedOlmMessageVault(alias)
        try {
            val encrypted = vault.encrypt(message())
            try {
                vault.decrypt(encrypted.copy(contentEncryptionVersion = 2))
                throw AssertionError("Unsupported encrypted message version should be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Unsupported"))
            }
        } finally {
            deleteKey(alias)
        }
    }

    private fun message() = DecryptedOlmMessage(
        ownerUserId = "bob",
        conversationId = "alice_bob",
        messageId = "message-123",
        conversationPeerUserId = "alice",
        senderUserId = "alice",
        senderDeviceId = "sender-device",
        recipientUserId = "bob",
        recipientDeviceId = "recipient-device",
        sentAtMillis = 1_800_000_000_000L,
        text = "This message must never be stored as plaintext in Room.",
    )

    private fun newAlias(): String =
        EncryptedOlmMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID().toString()

    private fun keyExists(alias: String): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.containsAlias(alias)
    }

    private fun deleteKey(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun expectSecurityFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Tampered encrypted message should fail authentication")
        } catch (expected: GeneralSecurityException) {
            // Expected AES-GCM authentication failure.
        }
    }
}
