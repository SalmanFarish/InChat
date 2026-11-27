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

class EncryptedGroupMessageVaultTest {
    @Test
    fun groupBodyIsEncryptedWithFreshNonceAndSurvivesVaultRecreation() {
        val alias = newAlias()
        try {
            val vault = EncryptedGroupMessageVault(alias)
            val plaintext = message()
            val encrypted = vault.encrypt(plaintext)
            val second = vault.encrypt(plaintext)

            assertEquals(EncryptedGroupMessageVault.CONTENT_VERSION, encrypted.contentEncryptionVersion)
            assertTrue("A received encrypted group message must retain a durable notification intent", encrypted.notificationPending)
            assertNotEquals(encrypted.textNonceBase64, second.textNonceBase64)
            assertFalse(encrypted.textCiphertextBase64.contains(plaintext.text))
            assertFalse(encrypted.toString().contains(plaintext.text))
            assertEquals(plaintext, EncryptedGroupMessageVault(alias).decrypt(encrypted))
            val outgoing = plaintext.copy(ownerUserId = "bob", senderUserId = "bob")
            assertFalse("A sender-owned group echo must not notify its owner", EncryptedGroupMessageVault(alias).encrypt(outgoing).notificationPending)
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun allGroupRoutingMetadataIsAuthenticatedAsGcmAssociatedData() {
        val alias = newAlias()
        val vault = EncryptedGroupMessageVault(alias)
        try {
            val encrypted = vault.encrypt(message())
            val tampered = listOf(
                encrypted.copy(ownerUserId = "mallory"),
                encrypted.copy(groupId = "different-group"),
                encrypted.copy(messageId = "different-message"),
                encrypted.copy(membershipEpoch = encrypted.membershipEpoch + 1L),
                encrypted.copy(senderUserId = "mallory"),
                encrypted.copy(senderDeviceId = "other-device"),
                encrypted.copy(megolmSessionId = "abcdef0123456789"),
                encrypted.copy(messageIndex = encrypted.messageIndex + 1L),
                encrypted.copy(sentAtMillis = encrypted.sentAtMillis + 1L),
            )
            tampered.forEach { expectSecurityFailure { vault.decrypt(it) } }
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun modifiedCiphertextCannotBeDecrypted() {
        val alias = newAlias()
        val vault = EncryptedGroupMessageVault(alias)
        try {
            val encrypted = vault.encrypt(message())
            val bytes = Base64.decode(
                encrypted.textCiphertextBase64,
                Base64.URL_SAFE or Base64.NO_WRAP,
            )
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            val modified = encrypted.copy(
                textCiphertextBase64 = Base64.encodeToString(
                    bytes,
                    Base64.URL_SAFE or Base64.NO_WRAP,
                ),
            )
            bytes.fill(0)
            expectSecurityFailure { vault.decrypt(modified) }
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun missingKeystoreKeyIsNotRecreatedAndUnknownVersionIsRejected() {
        val alias = newAlias()
        val vault = EncryptedGroupMessageVault(alias)
        val encrypted = vault.encrypt(message())
        try {
            deleteKey(alias)
            var missingKeyRejected = false
            try {
                vault.decrypt(encrypted)
            } catch (expected: GeneralSecurityException) {
                missingKeyRejected = expected.message.orEmpty().contains("key is missing")
            }
            assertTrue(missingKeyRejected)
            var encryptRejected = false
            try {
                vault.encrypt(message(), allowKeyCreation = false)
            } catch (expected: GeneralSecurityException) {
                encryptRejected = expected.message.orEmpty().contains("key is missing")
            }
            assertTrue("Existing group ciphertext must not be stranded by silent key rotation", encryptRejected)
            assertFalse(keyExists(alias))
        } finally {
            deleteKey(alias)
        }

        val versionAlias = newAlias()
        val versionVault = EncryptedGroupMessageVault(versionAlias)
        try {
            val record = versionVault.encrypt(message())
            try {
                versionVault.decrypt(record.copy(contentEncryptionVersion = 2))
                throw AssertionError("Unknown group content version should be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Unsupported"))
            }
        } finally {
            deleteKey(versionAlias)
        }
    }

    private fun message() = DecryptedGroupMessage(
        ownerUserId = "alice",
        groupId = "group-123",
        messageId = "message-123",
        membershipEpoch = 4L,
        senderUserId = "bob",
        senderDeviceId = "bob-phone",
        megolmSessionId = "1234567890abcdef",
        messageIndex = 17L,
        sentAtMillis = 1_800_000_000_000L,
        text = "Group plaintext must not be persisted in a Room text column.",
    )

    private fun newAlias() = EncryptedGroupMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()

    private fun keyExists(alias: String): Boolean {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        return store.containsAlias(alias)
    }

    private fun deleteKey(alias: String) {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun expectSecurityFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Tampered group message should fail authentication")
        } catch (_: GeneralSecurityException) {
            // Expected AES-GCM authentication failure.
        }
    }
}
