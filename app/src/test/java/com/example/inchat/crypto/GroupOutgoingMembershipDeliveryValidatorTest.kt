package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupOutgoingMembershipDeliveryValidatorTest {
    private val commit = EncryptedGroupOutgoingCommitEntity(
        ownerUserId = "alice",
        groupId = "group-1",
        messageId = "message-1",
        senderDeviceId = "alice-phone",
        membershipEpoch = 7L,
        megolmSessionId = "megolm-session",
        messageIndex = 4L,
        sentAtMillis = 10_000L,
        recipientDeviceCount = 1,
    )
    private val delivery = QueuedOlmDeviceDelivery(
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        messageId = "message-1",
        deliveryId = "delivery-id",
        recipientUserId = "bob",
        recipientDeviceId = "bob-phone",
        envelope = "ciphertext",
    )

    @Test
    fun exactCommittedMembershipEpochAllowsDelivery() {
        assertTrue(isCommittedGroupDeliveryCurrent(
            commit, delivery, "alice", "alice-phone", membership(epoch = 7L),
        ))
    }

    @Test
    fun changedMembershipEpochRejectsDeliveryEvenWhenRecipientRemainsMember() {
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery, "alice", "alice-phone", membership(epoch = 8L),
        ))
    }

    @Test
    fun readdedRecipientUnderNewEpochCannotReceiveOldCiphertext() {
        val readded = membership(epoch = 9L, members = listOf("alice", "bob"))
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery, "alice", "alice-phone", readded,
        ))
    }

    @Test
    fun removedSenderAndMismatchedDeliveryIdentityFailClosed() {
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery, "alice", "alice-phone",
            membership(epoch = 7L, members = listOf("bob", "carol")),
        ))
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery.copy(senderDeviceId = "other-device"),
            "alice", "alice-phone", membership(epoch = 7L),
        ))
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery.copy(messageId = "other-message"),
            "alice", "alice-phone", membership(epoch = 7L),
        ))
    }


    @Test
    fun malformedCommittedCryptoMetadataFailsClosed() {
        val malformedCommits = listOf(
            commit.copy(recipientDeviceCount = 0),
            commit.copy(recipientDeviceCount = MAX_OLM_DEVICE_DELIVERY_TARGETS + 1),
            commit.copy(megolmSessionId = ""),
            commit.copy(messageIndex = -1L),
            commit.copy(sentAtMillis = -1L),
            commit.copy(membershipEpoch = 0L),
        )
        malformedCommits.forEach { malformed ->
            assertFalse(isCommittedGroupDeliveryCurrent(
                malformed, delivery, "alice", "alice-phone", membership(epoch = 7L),
            ))
        }
    }

    @Test
    fun wrongGroupAndWrongAuthenticatedSenderFailClosed() {
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery, "mallory", "alice-phone", membership(epoch = 7L),
        ))
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery, "alice", "other-device", membership(epoch = 7L),
        ))
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery, "alice", "alice-phone",
            membership(epoch = 7L).copy(groupId = "other-group"),
        ))
    }

    @Test
    fun malformedMembershipSnapshotFailsClosed() {
        val malformed = membership(epoch = 7L).copy(
            members = mapOf("alice" to "admin", "bob" to "unknown-role"),
        )
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery, "alice", "alice-phone", malformed,
        ))
    }

    @Test
    fun deliveryToRemovedRecipientFailsClosedEvenAtCommittedEpoch() {
        assertFalse(isCommittedGroupDeliveryCurrent(
            commit, delivery, "alice", "alice-phone",
            membership(epoch = 7L, members = listOf("alice", "carol")),
        ))
    }

    private fun membership(
        epoch: Long,
        members: List<String> = listOf("alice", "bob"),
    ) = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = epoch,
        members = members.associateWith { "member" },
    )
}
