package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedMessageNotificationOutboxTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        LocalChatDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun directNotificationIntentSurvivesUntilPublishedAndIsAccountScoped() = runBlocking {
        val dao = database.encryptedOlmMessageDao()
        val inbound = directMessage(notificationPending = true)
        val outgoing = directMessage(
            messageId = "outgoing-message",
            ownerUserId = "alice",
            senderUserId = "alice",
            notificationPending = false,
        )
        assertTrue(dao.insertMessageIfAbsent(inbound) != -1L)
        assertTrue(dao.insertMessageIfAbsent(outgoing) != -1L)

        assertTrue(dao.hasPendingNotifications("bob"))
        assertFalse(dao.hasPendingNotifications("alice"))
        assertEquals(listOf(inbound), dao.getPendingNotificationMessages("bob", 10))

        assertEquals(1, dao.markNotificationPublished("bob", "alice_bob", "incoming-message"))
        assertFalse(dao.hasPendingNotifications("bob"))
        assertTrue(dao.getPendingNotificationMessages("bob", 10).isEmpty())
    }

    @Test
    fun groupNotificationIntentSurvivesUntilPublishedAndIsAccountScoped() = runBlocking {
        val dao = database.encryptedGroupMessageDao()
        val inbound = groupMessage(notificationPending = true)
        val outgoing = groupMessage(
            messageId = "outgoing-group-message",
            ownerUserId = "bob",
            senderUserId = "bob",
            notificationPending = false,
        )
        assertTrue(dao.insertOutgoingMessage(inbound) != -1L)
        assertTrue(dao.insertOutgoingMessage(outgoing) != -1L)

        assertTrue(dao.hasPendingNotifications("alice"))
        assertFalse(dao.hasPendingNotifications("bob"))
        assertEquals(listOf(inbound), dao.getPendingNotificationMessages("alice", 10))

        assertEquals(1, dao.markNotificationPublished("alice", "group-1", "incoming-group-message"))
        assertFalse(dao.hasPendingNotifications("alice"))
        assertTrue(dao.getPendingNotificationMessages("alice", 10).isEmpty())
    }

    private fun directMessage(
        messageId: String = "incoming-message",
        ownerUserId: String = "bob",
        senderUserId: String = "alice",
        notificationPending: Boolean,
    ) = EncryptedOlmMessageEntity(
        ownerUserId = ownerUserId,
        conversationId = "alice_bob",
        messageId = messageId,
        conversationPeerUserId = if (senderUserId == ownerUserId) "bob" else "alice",
        senderUserId = senderUserId,
        senderDeviceId = "alice-device",
        recipientUserId = "bob",
        recipientDeviceId = "bob-device",
        sentAtMillis = 100L,
        contentEncryptionVersion = 1,
        textNonceBase64 = "AAAAAAAAAAAAAAAA",
        textCiphertextBase64 = "AAAAAAAAAAAAAAAAAAAAAA",
        notificationPending = notificationPending,
    )

    private fun groupMessage(
        messageId: String = "incoming-group-message",
        ownerUserId: String = "alice",
        senderUserId: String = "bob",
        notificationPending: Boolean,
    ) = EncryptedGroupMessageEntity(
        ownerUserId = ownerUserId,
        groupId = "group-1",
        messageId = messageId,
        membershipEpoch = 2L,
        senderUserId = senderUserId,
        senderDeviceId = "bob-device",
        megolmSessionId = "1234567890abcdef",
        messageIndex = 4L,
        sentAtMillis = 100L,
        contentEncryptionVersion = 1,
        textNonceBase64 = "AAAAAAAAAAAAAAAA",
        textCiphertextBase64 = "AAAAAAAAAAAAAAAAAAAAAA",
        notificationPending = notificationPending,
    )
}
