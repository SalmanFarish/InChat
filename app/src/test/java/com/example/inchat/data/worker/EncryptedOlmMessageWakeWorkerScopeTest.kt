package com.example.inchat.data.worker

import com.example.inchat.crypto.OlmAuthenticatedMessagePayloadCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedOlmMessageWakeWorkerScopeTest {

    @Test
    fun acceptsSenderOwnedCompanionDeviceUsingTheActualDirectConversationPeer() {
        val conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "bob")

        assertTrue(
            isValidEncryptedOlmDirectWakeupScope(
                ownerUserId = "alice",
                recipientUserId = "alice",
                conversationId = conversationId,
                conversationPeerUserId = "bob",
            ),
        )
    }

    @Test
    fun acceptsADeviceOwnedByTheOtherDirectParticipant() {
        val conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "bob")

        assertTrue(
            isValidEncryptedOlmDirectWakeupScope(
                ownerUserId = "alice",
                recipientUserId = "bob",
                conversationId = conversationId,
                conversationPeerUserId = "bob",
            ),
        )
    }

    @Test
    fun rejectsARecipientOutsideTheAuthenticatedDirectConversation() {
        val conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "bob")

        assertFalse(
            isValidEncryptedOlmDirectWakeupScope(
                ownerUserId = "alice",
                recipientUserId = "mallory",
                conversationId = conversationId,
                conversationPeerUserId = "bob",
            ),
        )
    }

    @Test
    fun rejectsAConversationPeerThatIsTheSenderItself() {
        assertFalse(
            isValidEncryptedOlmDirectWakeupScope(
                ownerUserId = "alice",
                recipientUserId = "alice",
                conversationId = "alice_alice",
                conversationPeerUserId = "alice",
            ),
        )
    }

    @Test
    fun rejectsAConversationIdThatDoesNotMatchItsPeer() {
        val conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "carol")

        assertFalse(
            isValidEncryptedOlmDirectWakeupScope(
                ownerUserId = "alice",
                recipientUserId = "alice",
                conversationId = conversationId,
                conversationPeerUserId = "bob",
            ),
        )
    }

    @Test
    fun acceptsOnlyAValidDirectPreviewScope() {
        val conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "bob")
        assertTrue(
            isValidEncryptedOlmPreviewScope(
                ownerUserId = "alice",
                recipientUserId = "bob",
                conversationId = conversationId,
                conversationPeerUserId = "bob",
                groupId = null,
                membershipEpoch = 0L,
            ),
        )
        assertFalse(
            isValidEncryptedOlmPreviewScope(
                ownerUserId = "alice",
                recipientUserId = "mallory",
                conversationId = conversationId,
                conversationPeerUserId = "bob",
                groupId = null,
                membershipEpoch = 0L,
            ),
        )
        assertFalse(
            isValidEncryptedOlmPreviewScope(
                ownerUserId = "alice",
                recipientUserId = "bob",
                conversationId = conversationId,
                conversationPeerUserId = "bob",
                groupId = null,
                membershipEpoch = 1L,
            ),
        )
    }

    @Test
    fun acceptsOnlyAValidGroupPreviewScope() {
        assertTrue(
            isValidEncryptedOlmPreviewScope(
                ownerUserId = "alice",
                recipientUserId = "bob",
                conversationId = "group-1",
                conversationPeerUserId = "",
                groupId = "group-1",
                membershipEpoch = 7L,
            ),
        )
        assertFalse(
            isValidEncryptedOlmPreviewScope(
                ownerUserId = "alice",
                recipientUserId = "alice",
                conversationId = "group-1",
                conversationPeerUserId = "",
                groupId = "group-1",
                membershipEpoch = 7L,
            ),
        )
        assertFalse(
            isValidEncryptedOlmPreviewScope(
                ownerUserId = "alice",
                recipientUserId = "bob",
                conversationId = "group-1",
                conversationPeerUserId = "bob",
                groupId = "group-1",
                membershipEpoch = 7L,
            ),
        )
        assertFalse(
            isValidEncryptedOlmPreviewScope(
                ownerUserId = "alice",
                recipientUserId = "bob",
                conversationId = "group-1",
                conversationPeerUserId = "",
                groupId = "group-1",
                membershipEpoch = 0L,
            ),
        )
    }

}
