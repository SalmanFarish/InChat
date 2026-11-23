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

            val repository = EncryptedOlmMessageRepository(dao, vault, authenticatedUserId = { "bob" })
            val observed = repository.observeMessages("bob", "alice_bob").first()

            assertEquals(listOf("earlier", "later"), observed.map { it.messageId })
            assertEquals(listOf("first body", "second body"), observed.map { it.text })
            assertEquals(
                "second body",
                repository.getMessage("bob", "alice_bob", "later")?.text,
            )
            assertTrue(repository.observeMessages("bob", "alice_charlie").first().isEmpty())
            assertTrue(runCatching {
                repository.getMessage("mallory", "alice_bob", "earlier")
            }.isFailure)
            assertTrue(runCatching {
                repository.observeMessages("mallory", "alice_bob")
            }.isFailure)

            var activeAccount = "bob"
            val accountBoundRepository = EncryptedOlmMessageRepository(
                dao,
                vault,
                authenticatedUserId = { activeAccount },
            )
            val alreadyCreatedFlow = accountBoundRepository.observeMessages("bob", "alice_bob")
            assertEquals(2, alreadyCreatedFlow.first().size)
            activeAccount = "mallory"
            assertTrue("An existing observation must stop exposing plaintext after account switch", runCatching {
                alreadyCreatedFlow.first()
            }.isFailure)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            deleteKey(keyAlias)
        }
    }

    @Test
    fun accountSwitchDuringDecryptionPreventsReturningPlaintext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "encrypted-olm-account-switch-during-decrypt-${UUID.randomUUID()}.db"
        val keyAlias = EncryptedOlmMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val vault = EncryptedOlmMessageVault(keyAlias)
        try {
            val dao = database.encryptedOlmMessageDao()
            dao.upsertMessage(vault.encrypt(message("switch-during-decrypt", 100L, "must stay private")))

            var authReads = 0
            val singleReadRepository = EncryptedOlmMessageRepository(
                dao,
                vault,
                authenticatedUserId = {
                    authReads += 1
                    if (authReads <= 2) "bob" else "mallory"
                },
            )
            assertTrue(
                "A switch detected after decrypt must prevent returning plaintext",
                runCatching {
                    singleReadRepository.getMessage("bob", "alice_bob", "switch-during-decrypt")
                }.isFailure,
            )

            authReads = 0
            val observedRepository = EncryptedOlmMessageRepository(
                dao,
                vault,
                authenticatedUserId = {
                    authReads += 1
                    if (authReads <= 2) "bob" else "mallory"
                },
            )
            assertTrue(
                "An account switch during flow decryption must prevent emitting plaintext",
                runCatching {
                    observedRepository.observeMessages("bob", "alice_bob").first()
                }.isFailure,
            )
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

            val repository = EncryptedOlmMessageRepository(dao, vault, authenticatedUserId = { "bob" })
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


    @Test
    fun savesOutgoingSenderCopyEncryptedAndIdempotently() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "encrypted-olm-outgoing-${UUID.randomUUID()}.db"
        val keyAlias = EncryptedOlmMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val vault = EncryptedOlmMessageVault(keyAlias)
        try {
            val dao = database.encryptedOlmMessageDao()
            val repository = EncryptedOlmMessageRepository(dao, vault, authenticatedUserId = { "alice" })
            val outgoing = outgoingMessage("sender-copy", "private outgoing body")
            repository.saveOutgoingMessage(outgoing)
            repository.saveOutgoingMessage(outgoing)

            val rows = dao.observeMessages("alice", "alice_bob").first()
            assertEquals("A replay must not create a second sender timeline row", 1, rows.size)
            assertTrue("The stored row must not contain plaintext", !rows.single().textCiphertextBase64.contains(outgoing.text))
            assertEquals(outgoing, repository.getMessage("alice", "alice_bob", "sender-copy"))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            deleteKey(keyAlias)
        }
    }

    @Test
    fun outgoingMessageIdCollisionWithDifferentContentFailsWithoutReplacingOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "encrypted-olm-outgoing-collision-${UUID.randomUUID()}.db"
        val keyAlias = EncryptedOlmMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val vault = EncryptedOlmMessageVault(keyAlias)
        try {
            val dao = database.encryptedOlmMessageDao()
            val repository = EncryptedOlmMessageRepository(dao, vault, authenticatedUserId = { "alice" })
            val original = outgoingMessage("collision", "original body")
            repository.saveOutgoingMessage(original)
            assertTrue(
                "Reusing a committed outgoing ID with different content must be rejected",
                runCatching { repository.saveOutgoingMessage(original.copy(text = "replacement body")) }.isFailure,
            )
            assertEquals(original, repository.getMessage("alice", "alice_bob", "collision"))
            assertEquals(1, dao.observeMessages("alice", "alice_bob").first().size)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            deleteKey(keyAlias)
        }
    }

    @Test
    fun outgoingMessageCannotBeSavedForAnotherAuthenticatedAccount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "encrypted-olm-outgoing-owner-${UUID.randomUUID()}.db"
        val keyAlias = EncryptedOlmMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val vault = EncryptedOlmMessageVault(keyAlias)
        try {
            val dao = database.encryptedOlmMessageDao()
            val repository = EncryptedOlmMessageRepository(dao, vault, authenticatedUserId = { "mallory" })
            assertTrue(
                "An account switch must prevent writing a sender-owned message",
                runCatching { repository.saveOutgoingMessage(outgoingMessage("wrong-owner", "must not persist")) }.isFailure,
            )
            assertTrue(dao.observeMessages("alice", "alice_bob").first().isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            deleteKey(keyAlias)
        }
    }

    private fun outgoingMessage(messageId: String, text: String) = DecryptedOlmMessage(
        ownerUserId = "alice",
        conversationId = "alice_bob",
        messageId = messageId,
        conversationPeerUserId = "bob",
        senderUserId = "alice",
        senderDeviceId = "alice-device",
        recipientUserId = "bob",
        recipientDeviceId = "bob-device",
        sentAtMillis = 100L,
        text = text,
    )

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
