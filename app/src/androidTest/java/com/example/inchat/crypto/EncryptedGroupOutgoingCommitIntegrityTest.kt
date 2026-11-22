package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedGroupOutgoingCommitIntegrityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(
        context,
        com.example.inchat.data.local.LocalChatDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val keyAlias = EncryptedGroupMessageVault.DEFAULT_KEY_ALIAS + "-commit-" + UUID.randomUUID()
    private var activeAccount = "alice"
    private val repository = EncryptedGroupMessageRepository(
        dao = database.encryptedGroupMessageDao(),
        vault = EncryptedGroupMessageVault(keyAlias),
        authenticatedUserId = { activeAccount },
    )

    @After
    fun closeDatabase() {
        database.close()
        val store = java.security.KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
    }

    @Test
    fun timelineCommitRejectsEveryMismatchedDurableMarkerField() = runBlocking {
        val dao = database.encryptedGroupMessageDao()
        val cases = listOf(
            "device" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(senderDeviceId = "replacement-phone")
            },
            "epoch" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(membershipEpoch = marker.membershipEpoch + 1L)
            },
            "session" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(megolmSessionId = "fedcba0987654321")
            },
            "index" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(messageIndex = marker.messageIndex + 1L)
            },
            "timestamp" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(sentAtMillis = marker.sentAtMillis + 1L)
            },
        )

        for ((suffix, mutate) in cases) {
            val outgoing = outgoingMessage("marker-$suffix-mismatch")
            assertTrue(repository.savePendingOutgoingIntent(
                outgoing.ownerUserId,
                outgoing.groupId,
                outgoing.messageId,
                outgoing.sentAtMillis,
                outgoing.text,
            ))
            val expectedMarker = commitMarker(outgoing)
            assertEquals(
                1L,
                database.olmDeliveryQueueDao().insertOutgoingGroupCommitIfAbsent(
                    mutate(expectedMarker),
                ),
            )

            assertRejected {
                repository.saveOutgoingMessageAndClearPendingIntent(outgoing)
            }

            assertNull(
                "A mismatched marker must not publish a timeline echo ($suffix)",
                dao.getMessage(outgoing.ownerUserId, outgoing.groupId, outgoing.messageId),
            )
            assertTrue(
                "The encrypted pending intent must survive ($suffix)",
                dao.getPendingMessage(outgoing.ownerUserId, outgoing.groupId, outgoing.messageId) != null,
            )
            assertEquals(
                "The mismatched marker must remain available for diagnosis/recovery ($suffix)",
                mutate(expectedMarker),
                dao.getOutgoingGroupCommit(outgoing.ownerUserId, outgoing.groupId, outgoing.messageId),
            )
        }
    }

    @Test
    fun existingEchoCannotClearPendingIntentWhenMarkerMetadataDiffers() = runBlocking {
        val dao = database.encryptedGroupMessageDao()
        val cases = listOf(
            "device" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(senderDeviceId = "replacement-phone")
            },
            "epoch" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(membershipEpoch = marker.membershipEpoch + 1L)
            },
            "session" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(megolmSessionId = "fedcba0987654321")
            },
            "index" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(messageIndex = marker.messageIndex + 1L)
            },
            "timestamp" to { marker: EncryptedGroupOutgoingCommitEntity ->
                marker.copy(sentAtMillis = marker.sentAtMillis + 1L)
            },
        )

        for ((suffix, mutate) in cases) {
            val outgoing = outgoingMessage("echo-$suffix-mismatch")
            assertTrue(repository.saveOutgoingMessage(outgoing))
            assertTrue(repository.savePendingOutgoingIntent(
                outgoing.ownerUserId,
                outgoing.groupId,
                outgoing.messageId,
                outgoing.sentAtMillis,
                outgoing.text,
            ))
            val expectedMarker = commitMarker(outgoing)
            val mismatchedMarker = mutate(expectedMarker)
            assertEquals(
                1L,
                database.olmDeliveryQueueDao().insertOutgoingGroupCommitIfAbsent(mismatchedMarker),
            )

            assertRejected {
                repository.saveOutgoingMessageAndClearPendingIntent(outgoing)
            }

            assertEquals(
                "The already-committed echo must remain unchanged ($suffix)",
                outgoing,
                repository.getMessage(outgoing.ownerUserId, outgoing.groupId, outgoing.messageId),
            )
            assertTrue(
                "The pending intent must not be cleared ($suffix)",
                dao.getPendingMessage(outgoing.ownerUserId, outgoing.groupId, outgoing.messageId) != null,
            )
            assertEquals(
                "The mismatched marker must remain ($suffix)",
                mismatchedMarker,
                dao.getOutgoingGroupCommit(outgoing.ownerUserId, outgoing.groupId, outgoing.messageId),
            )
        }
    }

    private suspend fun assertRejected(block: suspend () -> Any?) {
        try {
            block()
            throw AssertionError("Expected mismatched durable commit metadata to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed commit validation.
        }
    }

    private fun outgoingMessage(messageId: String) = DecryptedGroupMessage(
        ownerUserId = "alice",
        groupId = "group-123",
        messageId = messageId,
        membershipEpoch = 4L,
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        megolmSessionId = "abcdef1234567890",
        messageIndex = 21L,
        sentAtMillis = 1_800_000_000_100L,
        text = "Committed outgoing message that must be restored exactly.",
    )

    private fun commitMarker(message: DecryptedGroupMessage) =
        EncryptedGroupOutgoingCommitEntity(
            ownerUserId = message.ownerUserId,
            groupId = message.groupId,
            messageId = message.messageId,
            senderDeviceId = message.senderDeviceId,
            membershipEpoch = message.membershipEpoch,
            megolmSessionId = message.megolmSessionId,
            messageIndex = message.messageIndex,
            sentAtMillis = message.sentAtMillis,
            recipientDeviceCount = 2,
        )
}
