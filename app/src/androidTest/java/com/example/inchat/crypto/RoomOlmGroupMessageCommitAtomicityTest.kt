package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomOlmGroupMessageCommitAtomicityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, LocalChatDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.olmDeliveryQueueDao()
    private val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(database, dao))

    @After fun closeDatabase() = database.close()

    @Test
    fun groupCommitMarkerAndCompleteFanoutAreInsertedAtomicallyAndNotResurrected() {
        val commit = commit()
        val deliveries = listOf(prepared("bob-phone"), prepared("carol-tablet"))
        assertEquals(2, queue.enqueue(commit.ownerUserId, commit.senderDeviceId,
            commit.messageId, deliveries, commit))
        assertEquals(commit.toEntity(), dao.getOutgoingGroupCommit(
            commit.ownerUserId, commit.groupId, commit.messageId))

        // Both deliveries have been acknowledged and removed. Replaying the
        // producer after native-stage finalization trouble must not recreate them.
        while (true) {
            val row = dao.findNextDue(commit.ownerUserId, commit.senderDeviceId, Long.MAX_VALUE)
                ?.takeIf { it.messageId == commit.messageId } ?: break
            assertEquals(1, dao.removeAcknowledgedDelivery(
                row.deliveryId, row.messageId, row.recipientUserId, row.recipientDeviceId,
                commit.ownerUserId, commit.senderDeviceId))
        }
        assertTrue(dao.getTargetStatesForDistribution(
            commit.ownerUserId, commit.senderDeviceId, commit.messageId).isEmpty())

        assertEquals(0, queue.enqueue(commit.ownerUserId, commit.senderDeviceId,
            commit.messageId, deliveries, commit))
        assertEquals(commit.toEntity(), dao.getOutgoingGroupCommit(
            commit.ownerUserId, commit.groupId, commit.messageId))
        assertTrue(dao.getTargetStatesForDistribution(
            commit.ownerUserId, commit.senderDeviceId, commit.messageId).isEmpty())
    }

    @Test
    fun conflictingExistingMarkerRejectsReplayWithoutChangingFanout() {
        val original = commit()
        val deliveries = listOf(prepared("bob-phone"), prepared("carol-tablet"))
        assertEquals(2, queue.enqueue(original.ownerUserId, original.senderDeviceId,
            original.messageId, deliveries, original))
        val before = dao.getTargetStatesForDistribution(
            original.ownerUserId, original.senderDeviceId, original.messageId)

        var rejected = false
        try {
            queue.enqueue(original.ownerUserId, original.senderDeviceId, original.messageId,
                deliveries, original.copy(messageIndex = original.messageIndex + 1L))
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(original.toEntity(), dao.getOutgoingGroupCommit(
            original.ownerUserId, original.groupId, original.messageId))
        assertEquals(before, dao.getTargetStatesForDistribution(
            original.ownerUserId, original.senderDeviceId, original.messageId))
    }

    @Test
    fun conflictingPreexistingDeliveryRollsBackNewCommitMarker() {
        val commit = commit()
        val first = prepared("bob-phone")
        assertEquals(1, queue.enqueue(commit.ownerUserId, commit.senderDeviceId,
            commit.messageId, listOf(first)))

        var rejected = false
        try {
            queue.enqueue(commit.ownerUserId, commit.senderDeviceId, commit.messageId,
                listOf(first, prepared("carol-tablet")), commit)
        } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertNull(dao.getOutgoingGroupCommit(commit.ownerUserId, commit.groupId, commit.messageId))
        assertEquals(1, dao.getTargetStatesForDistribution(
            commit.ownerUserId, commit.senderDeviceId, commit.messageId).size)
    }

    private fun commit() = GroupOutgoingMessageCommit(
        ownerUserId = "alice", groupId = "group-123", messageId = "atomic-group-message",
        senderDeviceId = "alice-phone", membershipEpoch = 4L,
        megolmSessionId = "abcdef1234567890", messageIndex = 21L,
        sentAtMillis = 1_800_000_000_100L, recipientDeviceCount = 2,
    )

    private fun prepared(deviceId: String): PreparedOlmDelivery {
        val sessionId = "session-$deviceId"
        val envelope = JSONObject().put("encryptionVersion", 1).put("algorithm", "olm")
            .put("senderDeviceId", "alice-phone").put("recipientDeviceId", deviceId)
            .put("sessionId", sessionId).put("ciphertext",
                Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.URL_SAFE or Base64.NO_WRAP))
            .toString()
        return PreparedOlmDelivery("bob", deviceId, sessionId, envelope)
    }
}
