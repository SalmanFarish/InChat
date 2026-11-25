package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedDirectPendingMessageRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(
        context,
        LocalChatDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val keyAlias = EncryptedDirectPendingMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
    private var activeAccount = "alice"
    private val vault = EncryptedDirectPendingMessageVault(keyAlias)
    private val repository = EncryptedDirectPendingMessageRepository(
        dao = database.encryptedDirectPendingMessageDao(),
        vault = vault,
        authenticatedUserId = { activeAccount },
    )

    @After
    fun closeDatabase() {
        database.close()
        deleteKey()
    }

    @Test
    fun pendingTextIsEncryptedAtRestAndRestoredAcrossRepositoryInstances() = runBlocking {
        val text = "Direct plaintext must never be stored in the local pending outbox."
        assertTrue(repository.savePendingOutgoingIntent("alice", "alice_bob", "bob", "message-1", 100L, text))
        val stored = database.encryptedDirectPendingMessageDao()
            .getPendingMessage("alice", "alice_bob", "message-1") ?: error("Pending row missing")

        assertEquals(EncryptedDirectPendingMessageVault.VERSION, stored.contentEncryptionVersion)
        assertFalse(stored.textCiphertextBase64.contains(text))
        assertFalse(stored.toString().contains(text))
        assertNotEquals(stored.textNonceBase64, vault.encrypt(
            "alice", "alice_bob", "bob", "message-1", 100L, text,
        ).textNonceBase64)

        val reopened = EncryptedDirectPendingMessageRepository(
            dao = database.encryptedDirectPendingMessageDao(),
            vault = EncryptedDirectPendingMessageVault(keyAlias),
            authenticatedUserId = { activeAccount },
        )
        assertEquals(text, reopened.pendingOutgoingIntents("alice").single().plaintext)
        assertEquals(text, reopened.observePendingOutgoingIntents("alice", "alice_bob", "bob").first().single().plaintext)
    }

    @Test
    fun metadataTamperingAndMessageIdCollisionAreRejected() = runBlocking {
        val text = "authenticated pending content"
        repository.savePendingOutgoingIntent("alice", "alice_bob", "bob", "message-2", 200L, text)
        val stored = database.encryptedDirectPendingMessageDao()
            .getPendingMessage("alice", "alice_bob", "message-2") ?: error("Pending row missing")

        var tamperRejected = false
        try {
            vault.decrypt(stored.copy(conversationPeerUserId = "mallory"))
        } catch (_: Exception) {
            // Either structural validation or the GCM tag must reject tampering.
            tamperRejected = true
        }
        assertTrue("Conversation metadata must be authenticated by AES-GCM", tamperRejected)

        var collisionRejected = false
        try {
            repository.savePendingOutgoingIntent(
                "alice", "alice_bob", "bob", "message-2", 201L, "different text",
            )
        } catch (expected: IllegalArgumentException) {
            collisionRejected = expected.message.orEmpty().contains("already belongs")
        }
        assertTrue("A reused pending message ID must not replace existing content", collisionRejected)
    }

    @Test
    fun accountSwitchBlocksPendingReadsAndWrites() = runBlocking {
        repository.savePendingOutgoingIntent("alice", "alice_bob", "bob", "message-3", 300L, "private")
        activeAccount = "mallory"

        var readRejected = false
        try {
            repository.pendingOutgoingIntents("alice")
        } catch (_: IllegalArgumentException) {
            readRejected = true
        }
        assertTrue(readRejected)

        var writeRejected = false
        try {
            repository.savePendingOutgoingIntent("alice", "alice_bob", "bob", "message-4", 400L, "private")
        } catch (_: IllegalArgumentException) {
            writeRejected = true
        }
        assertTrue(writeRejected)
        assertNull(database.encryptedDirectPendingMessageDao().getPendingMessage("alice", "alice_bob", "message-4"))
    }

    @Test
    fun removingPendingIntentIsConversationScopedAndIdempotent() = runBlocking {
        repository.savePendingOutgoingIntent("alice", "alice_bob", "bob", "message-5", 500L, "one")
        repository.savePendingOutgoingIntent("alice", "alice_carol", "carol", "message-6", 600L, "two")

        assertEquals(1, repository.deletePendingOutgoingIntent("alice", "alice_bob", "message-5"))
        assertEquals(0, repository.deletePendingOutgoingIntent("alice", "alice_bob", "message-5"))
        assertEquals(listOf("message-6"), repository.pendingOutgoingIntents("alice").map { it.messageId })
    }

    private fun deleteKey() {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
    }
}
