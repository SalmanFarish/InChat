package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomOlmDirectMessageCommitAtomicityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, LocalChatDatabase::class.java).allowMainThreadQueries().build()
    private val dao = database.olmDeliveryQueueDao()
    private val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(database, dao))
    @After fun closeDatabase() = database.close()

    @Test fun replayAfterAllAcknowledgementsDoesNotRecreateFanout() {
        val commit = DirectOutgoingMessageCommit("alice", "alice_bob", "bob", "direct-commit", "alice-phone", 100L, 2)
        val rows = listOf(prepared("bob", "bob-phone"), prepared("alice", "alice-tablet"))
        assertEquals(2, enqueue(commit, rows))
        val marker = dao.getOutgoingDirectCommit("alice", "alice_bob", "direct-commit")
        assertEquals(commit.toEntity(), marker)
        rows.forEach { item ->
            val row = dao.getByDeliveryId(deliveryId(commit, item))!!
            assertEquals(1, dao.removeAcknowledgedDelivery(row.deliveryId, row.messageId, row.recipientUserId, row.recipientDeviceId, "alice", "alice-phone"))
        }
        assertEquals(0, enqueue(commit, rows))
        assertEquals(marker, dao.getOutgoingDirectCommit("alice", "alice_bob", "direct-commit"))
        assertTrue(dao.getTargetStatesForDistribution("alice", "alice-phone", "direct-commit").isEmpty())
    }

    @Test fun conflictingCommitMetadataIsRejectedWithoutChangingOriginal() {
        val original = DirectOutgoingMessageCommit("alice", "alice_bob", "bob", "direct-conflict", "alice-phone", 100L, 2)
        val rows = listOf(prepared("bob", "bob-phone"), prepared("alice", "alice-tablet"))
        assertEquals(2, enqueue(original, rows))
        var rejected = false
        try { enqueue(original.copy(sentAtMillis = 101L), rows) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(original.toEntity(), dao.getOutgoingDirectCommit("alice", "alice_bob", "direct-conflict"))
        assertEquals(2, dao.getTargetStatesForDistribution("alice", "alice-phone", "direct-conflict").size)
    }

    @Test fun replayAfterRecipientDeviceCountChangesKeepsOriginalFanout() {
        val original = DirectOutgoingMessageCommit("alice", "alice_bob", "bob", "direct-device-change", "alice-phone", 100L, 2)
        val originalRows = listOf(prepared("bob", "bob-phone"), prepared("alice", "alice-tablet"))
        assertEquals(2, enqueue(original, originalRows))

        // A newly published recipient device must not make a replay look like
        // a different message or trigger fresh Olm session/prekey preparation.
        val expandedRows = originalRows + prepared("bob", "bob-laptop")
        assertEquals(0, enqueue(original.copy(recipientDeviceCount = 3), expandedRows))
        assertEquals(original.toEntity(), dao.getOutgoingDirectCommit("alice", "alice_bob", "direct-device-change"))
        assertEquals(2, dao.getTargetStatesForDistribution("alice", "alice-phone", "direct-device-change").size)
    }

    private fun enqueue(c: DirectOutgoingMessageCommit, rows: List<PreparedOlmDelivery>) = queue.enqueue(c.ownerUserId, c.senderDeviceId, c.messageId, rows, conversationId = c.conversationId, conversationPeerUserId = c.conversationPeerUserId, directMessageCommit = c)
    private fun deliveryId(c: DirectOutgoingMessageCommit, item: PreparedOlmDelivery): String {
        val raw = "${c.ownerUserId}\u0000${c.senderDeviceId}\u0000${c.messageId}\u0000${item.recipientUserId}\u0000${item.recipientDeviceId}".toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    }
    private fun prepared(user: String, device: String): PreparedOlmDelivery {
        val session = "session-$device"
        val envelope = EncryptedOlmEnvelopeCodec.encode(EncryptedOlmEnvelope("alice-phone", device, session, byteArrayOf(1, 2, 3)))
        return PreparedOlmDelivery(user, device, session, envelope)
    }
}
