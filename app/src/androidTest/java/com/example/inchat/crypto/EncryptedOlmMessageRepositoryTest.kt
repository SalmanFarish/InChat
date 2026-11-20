package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.GeneralSecurityException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedOlmMessageRepositoryTest {
    @Test
    fun observesOnlyRequestedAccountConversationAndDecryptsOnRead() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "encrypted-olm-repository-${UUID.randomUUID()}.db"
        val keyAlias = EncryptedOlmMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val vault = EncryptedOlmMessageVault(keyAlias)
        try {
            val dao = database.encryptedOlmMessageDao()
            dao.upsertMessage(vault.encrypt(message("later", 200L, "second body")))
            dao.upsertMessage(vault.encrypt(message("earlier", 100L, "first body")))
            dao.upsertMessage(
                vault.encrypt(
                    message(
                        messageId = "other-owner",
                        sentAtMillis = 150L,
                        text = "other account body",
                        ownerUserId = "charlie",
                        peerUserId = "charlie",
                    ),
                ),
            )

            val repository = EncryptedOlmMessageRepository(dao, vault)
            val observed = repository.observeMessages("bob", "alice_bob").first()

            assertEquals(listOf("earlier", "later"), observed.map { it.messageId })
            assertEquals(listOf("first body", "second body"), observed.map { it.text })
            assertEquals(
                "second body",
                repository.getMessage("bob", "alice_bob", "later")?.text,
            )
            assertTrue(repository.observeMessages("bob", "alice_charlie").first().isEmpty())
            assertEquals(null, repository.getMessage("mallory", "alice_bob", "earlier"))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            deleteKey(keyAlias)
        }
    }

    @Test
    fun corruptedStoredCiphertextFailsClosedWhenRead() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "encrypted-olm-corrupt-read-${UUID.randomUUID()}.db"
        val keyAlias = EncryptedOlmMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val vault = EncryptedOlmMessageVault(keyAlias)
        try {
            val dao = database.encryptedOlmMessageDao()
            val valid = vault.encrypt(message("corrupted", 100L, "body"))
            val bytes = Base64.decode(valid.textCiphertextBase64, Base64.URL_SAFE or Base64.NO_WRAP)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            val corrupted = valid.copy(
                textCiphertextBase64 = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP),
            )
            bytes.fill(0)
            dao.upsertMessage(corrupted)

            val repository = EncryptedOlmMessageRepository(dao, vault)
            var rejected = false
            try {
                repository.getMessage("bob", "alice_bob", "corrupted")
            } catch (_: GeneralSecurityException) {
                rejected = true
            }
            assertTrue("Corrupted encrypted content must never be returned as plaintext", rejected)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            deleteKey(keyAlias)
        }
    }

    private fun message(
        messageId: String,
        sentAtMillis: Long,
        text: String,
        ownerUserId: String = "bob",
        peerUserId: String = ownerUserId,
    ) = DecryptedOlmMessage(
        ownerUserId = ownerUserId,
        conversationId = if (ownerUserId == "bob") "alice_bob" else "alice_charlie",
        messageId = messageId,
        conversationPeerUserId = peerUserId,
        senderUserId = "alice",
        senderDeviceId = "sender-device",
        recipientUserId = ownerUserId,
        recipientDeviceId = "recipient-device",
        sentAtMillis = sentAtMillis,
        text = text,
    )

    private fun deleteKey(alias: String) {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
}
