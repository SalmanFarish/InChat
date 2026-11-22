package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Durable group replay protection and encrypted-content persistence. */
@Dao
abstract class EncryptedGroupMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReceiptIgnore(
        receipt: EncryptedGroupMessageReceiptEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMessageIgnore(
        message: EncryptedGroupMessageEntity,
    ): Long

    /**
     * The replay key and encrypted message are one transaction. A duplicate
     * replay key is a no-op; a message-ID collision rolls the transaction back.
     */
    @Transaction
    open suspend fun commitDecryptedMessage(
        receipt: EncryptedGroupMessageReceiptEntity,
        message: EncryptedGroupMessageEntity,
    ): Boolean {
        require(
            receipt.ownerUserId == message.ownerUserId &&
                receipt.groupId == message.groupId &&
                receipt.membershipEpoch == message.membershipEpoch &&
                receipt.messageId == message.messageId &&
                receipt.sentAtMillis == message.sentAtMillis &&
                receipt.senderUserId == message.senderUserId &&
                receipt.senderDeviceId == message.senderDeviceId &&
                receipt.megolmSessionId == message.megolmSessionId &&
                receipt.messageIndex == message.messageIndex
        ) { "Group replay receipt does not match encrypted message metadata" }
        if (insertReceiptIgnore(receipt) == -1L) {
            val existing = getReplayReceipt(
                receipt.ownerUserId,
                receipt.groupId,
                receipt.senderUserId,
                receipt.senderDeviceId,
                receipt.megolmSessionId,
                receipt.messageIndex,
            )
            check(existing == receipt) {
                "Megolm replay index is already committed with different authenticated metadata"
            }
            return false
        }
        check(insertMessageIgnore(message) != -1L) {
            "Group message ID already exists with a different replay receipt"
        }
        return true
    }

    @Query(
        "SELECT * FROM encrypted_group_message_receipts " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId " +
            "AND senderUserId = :senderUserId AND senderDeviceId = :senderDeviceId " +
            "AND megolmSessionId = :sessionId AND messageIndex = :messageIndex LIMIT 1"
    )
    abstract suspend fun getReplayReceipt(
        ownerUserId: String,
        groupId: String,
        senderUserId: String,
        senderDeviceId: String,
        sessionId: String,
        messageIndex: Long,
    ): EncryptedGroupMessageReceiptEntity?

    /** Insert the sender's own already-encrypted local timeline copy (no inbound replay receipt). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOutgoingMessage(message: EncryptedGroupMessageEntity): Long

    /**
     * Atomically publish a newly inserted sender timeline row and remove its
     * encrypted pending intent. A crash can therefore leave neither a visible
     * echo without its intent nor an intent whose echo was already inserted.
     *
     * Existing rows are deliberately not cleared here: the repository must
     * decrypt and compare the existing row before it may remove the intent.
     */
    @Transaction
    open suspend fun commitOutgoingMessageAndDeletePending(
        message: EncryptedGroupMessageEntity,
        ownerUserId: String,
        groupId: String,
        messageId: String,
    ): Boolean {
        require(
            message.ownerUserId == ownerUserId &&
                message.groupId == groupId &&
                message.messageId == messageId
        ) { "Outgoing message and pending intent scopes do not match" }
        val marker = getOutgoingGroupCommit(ownerUserId, groupId, messageId)
            ?: error("Committed group message marker is missing")
        require(
            marker.senderDeviceId == message.senderDeviceId &&
                marker.membershipEpoch == message.membershipEpoch &&
                marker.megolmSessionId == message.megolmSessionId &&
                marker.messageIndex == message.messageIndex &&
                marker.sentAtMillis == message.sentAtMillis
        ) { "Sender timeline row does not match its durable group commit marker" }
        if (insertOutgoingMessage(message) == -1L) return false
        check(deletePendingMessage(ownerUserId, groupId, messageId) == 1) {
            "Pending outgoing group intent disappeared before local commit"
        }
        check(deleteOutgoingGroupCommit(ownerUserId, groupId, messageId) == 1) {
            "Durable group commit marker disappeared before local commit"
        }
        return true
    }

    @Query(
        "SELECT * FROM encrypted_group_outgoing_commits " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId " +
            "ORDER BY sentAtMillis ASC, messageId ASC"
    )
    abstract suspend fun getOutgoingGroupCommits(
        ownerUserId: String,
        groupId: String,
    ): List<EncryptedGroupOutgoingCommitEntity>

    @Query(
        "SELECT * FROM encrypted_group_outgoing_commits " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId AND messageId = :messageId LIMIT 1"
    )
    abstract suspend fun getOutgoingGroupCommit(
        ownerUserId: String,
        groupId: String,
        messageId: String,
    ): EncryptedGroupOutgoingCommitEntity?

    @Query(
        "DELETE FROM encrypted_group_outgoing_commits " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId AND messageId = :messageId"
    )
    abstract suspend fun deleteOutgoingGroupCommit(
        ownerUserId: String,
        groupId: String,
        messageId: String,
    ): Int

    @Transaction
    open suspend fun clearCommittedOutgoingPending(
        message: EncryptedGroupMessageEntity,
    ) {
        val marker = getOutgoingGroupCommit(message.ownerUserId, message.groupId, message.messageId)
            ?: error("Committed group message marker is missing")
        require(
            marker.senderDeviceId == message.senderDeviceId &&
                marker.membershipEpoch == message.membershipEpoch &&
                marker.megolmSessionId == message.megolmSessionId &&
                marker.messageIndex == message.messageIndex &&
                marker.sentAtMillis == message.sentAtMillis
        ) { "Sender timeline row does not match its durable group commit marker" }
        check(deletePendingMessage(message.ownerUserId, message.groupId, message.messageId) == 1) {
            "Pending outgoing group intent disappeared before local commit"
        }
        check(deleteOutgoingGroupCommit(message.ownerUserId, message.groupId, message.messageId) == 1) {
            "Durable group commit marker disappeared before local commit"
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPendingMessage(message: EncryptedGroupPendingMessageEntity): Long

    @Query("SELECT * FROM encrypted_group_pending_messages WHERE ownerUserId = :ownerUserId ORDER BY sentAtMillis ASC, groupId ASC, messageId ASC")
    abstract suspend fun getPendingMessagesForOwner(ownerUserId: String): List<EncryptedGroupPendingMessageEntity>

    @Query("SELECT * FROM encrypted_group_pending_messages WHERE ownerUserId = :ownerUserId AND groupId = :groupId ORDER BY sentAtMillis ASC, messageId ASC")
    abstract suspend fun getPendingMessages(ownerUserId: String, groupId: String): List<EncryptedGroupPendingMessageEntity>

    @Query("SELECT * FROM encrypted_group_pending_messages WHERE ownerUserId = :ownerUserId AND groupId = :groupId AND messageId = :messageId LIMIT 1")
    abstract suspend fun getPendingMessage(ownerUserId: String, groupId: String, messageId: String): EncryptedGroupPendingMessageEntity?

    @Query("DELETE FROM encrypted_group_pending_messages WHERE ownerUserId = :ownerUserId AND groupId = :groupId AND messageId = :messageId")
    abstract suspend fun deletePendingMessage(ownerUserId: String, groupId: String, messageId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM encrypted_group_messages)")
    abstract suspend fun hasStoredMessages(): Boolean

    @Query(
        "SELECT * FROM encrypted_group_messages " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId " +
            "ORDER BY sentAtMillis ASC, messageId ASC"
    )
    abstract fun observeMessages(
        ownerUserId: String,
        groupId: String,
    ): Flow<List<EncryptedGroupMessageEntity>>

    @Query(
        "SELECT * FROM encrypted_group_messages " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId AND messageId = :messageId LIMIT 1"
    )
    abstract suspend fun getMessage(
        ownerUserId: String,
        groupId: String,
        messageId: String,
    ): EncryptedGroupMessageEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM encrypted_group_message_receipts " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId " +
            "AND senderUserId = :senderUserId AND senderDeviceId = :senderDeviceId " +
            "AND megolmSessionId = :sessionId AND messageIndex = :messageIndex)"
    )
    abstract suspend fun hasReplay(
        ownerUserId: String,
        groupId: String,
        senderUserId: String,
        senderDeviceId: String,
        sessionId: String,
        messageIndex: Long,
    ): Boolean
}
