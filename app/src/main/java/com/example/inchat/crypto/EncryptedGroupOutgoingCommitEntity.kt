package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index

/**
 * Durable, non-secret proof that a Megolm group message's complete Olm fan-out
 * was committed. The encrypted plaintext remains only in the pending-intent vault.
 */
@Entity(
    tableName = "encrypted_group_outgoing_commits",
    primaryKeys = ["ownerUserId", "groupId", "messageId"],
    indices = [Index(value = ["ownerUserId", "senderDeviceId"])],
)
data class EncryptedGroupOutgoingCommitEntity(
    val ownerUserId: String,
    val groupId: String,
    val messageId: String,
    val senderDeviceId: String,
    val membershipEpoch: Long,
    val megolmSessionId: String,
    val messageIndex: Long,
    val sentAtMillis: Long,
    val recipientDeviceCount: Int,
)

internal fun GroupOutgoingMessageCommit.toEntity() = EncryptedGroupOutgoingCommitEntity(
    ownerUserId = ownerUserId,
    groupId = groupId,
    messageId = messageId,
    senderDeviceId = senderDeviceId,
    membershipEpoch = membershipEpoch,
    megolmSessionId = megolmSessionId,
    messageIndex = messageIndex,
    sentAtMillis = sentAtMillis,
    recipientDeviceCount = recipientDeviceCount,
)
