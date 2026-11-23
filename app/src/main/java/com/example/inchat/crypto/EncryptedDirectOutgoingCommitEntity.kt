package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index

/** Durable non-secret identity marker for a fully committed direct Olm fan-out. */
@Entity(tableName = "encrypted_direct_outgoing_commits", primaryKeys = ["ownerUserId", "conversationId", "messageId"], indices = [Index(value = ["ownerUserId", "senderDeviceId"])])
data class EncryptedDirectOutgoingCommitEntity(val ownerUserId: String, val conversationId: String, val conversationPeerUserId: String, val messageId: String, val senderDeviceId: String, val sentAtMillis: Long, val recipientDeviceCount: Int)

internal fun DirectOutgoingMessageCommit.toEntity() = EncryptedDirectOutgoingCommitEntity(ownerUserId, conversationId, conversationPeerUserId, messageId, senderDeviceId, sentAtMillis, recipientDeviceCount)
