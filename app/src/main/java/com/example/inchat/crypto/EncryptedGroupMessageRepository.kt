package com.example.inchat.crypto

import java.security.MessageDigest
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

/**
 * Commits one native-staged Megolm plaintext to the isolated encrypted group
 * store. The replay key and AES-GCM ciphertext are inserted in one Room
 * transaction; callers must finalize the native staged receipt only afterwards.
 */
internal data class PendingGroupMessageIntent(
    val ownerUserId: String,
    val groupId: String,
    val messageId: String,
    val sentAtMillis: Long,
    val plaintext: String,
)

internal class EncryptedGroupMessageRepository(
    private val dao: EncryptedGroupMessageDao,
    private val vault: EncryptedGroupMessageVault = EncryptedGroupMessageVault(),
    private val pendingVault: EncryptedGroupPendingMessageVault = EncryptedGroupPendingMessageVault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authenticatedUserId: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid },
    private val decryptGroupMessages: suspend (List<EncryptedGroupMessageEntity>) -> List<DecryptedGroupMessage> =
        { records -> records.map(vault::decrypt) },
) : EncryptedGroupMessageCommitter {
    override suspend fun commitStagedMessage(
        message: DecryptedGroupMessage,
        receipt: EncryptedGroupMessageReceiptEntity,
        originalCiphertext: ByteArray,
    ): Boolean {
        validateScope(message.ownerUserId, message.groupId)
        requireAuthenticatedOwner(message.ownerUserId)
        require(originalCiphertext.isNotEmpty() && originalCiphertext.size <= MAX_CIPHERTEXT_BYTES) {
            "Original Megolm ciphertext has an invalid size"
        }
        require(receipt.receivedAtMillis >= 0L) { "Group receipt timestamp is invalid" }
        require(receipt.receiptId.matches(RECEIPT_ID)) { "Group receipt ID is invalid" }
        require(receipt.ciphertextSha256.matches(SHA256_HEX)) {
            "Group ciphertext digest is invalid"
        }
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(originalCiphertext)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actualDigest.equals(receipt.ciphertextSha256, ignoreCase = true)) {
            "Group receipt ciphertext digest does not match the staged ciphertext"
        }
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
        ) { "Group receipt metadata does not match staged plaintext" }

        return withContext(ioDispatcher) {
            requireAuthenticatedOwner(message.ownerUserId)
            val allowKeyCreation = !dao.hasStoredMessages()
            val encrypted = vault.encrypt(message, allowKeyCreation)
            requireAuthenticatedOwner(message.ownerUserId)
            val committed = dao.commitDecryptedMessage(receipt, encrypted)
            // A switch during the Room transaction cannot expose this row to
            // another account; report failure so the original receipt can retry.
            requireAuthenticatedOwner(message.ownerUserId)
            committed
        }
    }

    /**
     * Persist the sender's own timeline copy after the ciphertext fan-out has
     * been durably queued. The message body is encrypted by the same local
     * vault as received messages and never enters the legacy chat cache.
     */
    suspend fun saveOutgoingMessage(message: DecryptedGroupMessage): Boolean {
        validateScope(message.ownerUserId, message.groupId)
        require(message.senderUserId == message.ownerUserId) {
            "Only the authenticated sender can save a local outgoing group message"
        }
        require(MESSAGE_ID.matches(message.messageId) && message.text.isNotEmpty()) {
            "Outgoing encrypted group message is invalid"
        }
        require(message.membershipEpoch > 0L && message.sentAtMillis >= 0L &&
            message.messageIndex >= 0L && message.megolmSessionId.isNotBlank() &&
            message.senderDeviceId.matches(DEVICE_ID)
        ) { "Outgoing encrypted group message metadata is invalid" }
        requireAuthenticatedOwner(message.ownerUserId)

        return withContext(ioDispatcher) {
            requireAuthenticatedOwner(message.ownerUserId)
            val encrypted = vault.encrypt(message, allowKeyCreation = !dao.hasStoredMessages())
            requireAuthenticatedOwner(message.ownerUserId)
            val inserted = dao.insertOutgoingMessage(encrypted)
            requireAuthenticatedOwner(message.ownerUserId)
            if (inserted != -1L) {
                true
            } else {
                val existing = dao.getMessage(message.ownerUserId, message.groupId, message.messageId)
                    ?: error("Outgoing encrypted group message conflicted without an existing row")
                require(vault.decrypt(existing) == message) {
                    "Outgoing encrypted group message ID already belongs to different content"
                }
                false
            }
        }
    }

    /**
     * Commit the sender's encrypted timeline echo and remove its pending intent
     * in one Room transaction. If an echo already exists, verify its decrypted
     * contents before deleting the still-pending intent.
     */
    suspend fun saveOutgoingMessageAndClearPendingIntent(message: DecryptedGroupMessage): Boolean {
        validateScope(message.ownerUserId, message.groupId)
        require(message.senderUserId == message.ownerUserId) {
            "Only the authenticated sender can save a local outgoing group message"
        }
        require(MESSAGE_ID.matches(message.messageId) && message.text.isNotEmpty()) {
            "Outgoing encrypted group message is invalid"
        }
        require(message.membershipEpoch > 0L && message.sentAtMillis >= 0L &&
            message.messageIndex >= 0L && message.megolmSessionId.isNotBlank() &&
            message.senderDeviceId.matches(DEVICE_ID)
        ) { "Outgoing encrypted group message metadata is invalid" }
        requireAuthenticatedOwner(message.ownerUserId)

        return withContext(ioDispatcher) {
            requireAuthenticatedOwner(message.ownerUserId)
            val encrypted = vault.encrypt(message, allowKeyCreation = !dao.hasStoredMessages())
            requireAuthenticatedOwner(message.ownerUserId)
            val insertedAndCleared = dao.commitOutgoingMessageAndDeletePending(
                message = encrypted,
                ownerUserId = message.ownerUserId,
                groupId = message.groupId,
                messageId = message.messageId,
            )
            requireAuthenticatedOwner(message.ownerUserId)
            if (insertedAndCleared) {
                true
            } else {
                val existing = dao.getMessage(message.ownerUserId, message.groupId, message.messageId)
                    ?: error("Outgoing encrypted group message conflicted without an existing row")
                require(vault.decrypt(existing) == message) {
                    "Outgoing encrypted group message ID already belongs to different content"
                }
                // An existing echo is not sufficient evidence to discard the
                // recovery intent: authenticate the pending plaintext too.
                // This protects against a mismatched/corrupted intent sharing
                // the same message ID and keeps the durable marker for repair.
                val pending = dao.getPendingMessage(
                    message.ownerUserId,
                    message.groupId,
                    message.messageId,
                ) ?: error("Committed outgoing group intent is missing")
                require(
                    pending.sentAtMillis == message.sentAtMillis &&
                        pendingVault.decrypt(pending) == message.text
                ) { "Pending outgoing group intent conflicts with its committed timeline echo" }
                requireAuthenticatedOwner(message.ownerUserId)
                dao.clearCommittedOutgoingPending(encrypted)
                requireAuthenticatedOwner(message.ownerUserId)
                false
            }
        }
    }

    /** Persist a waiting-to-send intent before attempting Megolm encryption. */
    suspend fun savePendingOutgoingIntent(
        ownerUserId: String,
        groupId: String,
        messageId: String,
        sentAtMillis: Long,
        plaintext: String,
    ): Boolean {
        validateScope(ownerUserId, groupId)
        require(MESSAGE_ID.matches(messageId) && sentAtMillis >= 0L)
        requireAuthenticatedOwner(ownerUserId)
        return withContext(ioDispatcher) {
            requireAuthenticatedOwner(ownerUserId)
            val encrypted = pendingVault.encrypt(ownerUserId, groupId, messageId, sentAtMillis, plaintext)
            requireAuthenticatedOwner(ownerUserId)
            val inserted = dao.insertPendingMessage(encrypted)
            if (inserted != -1L) true else {
                val existing = dao.getPendingMessage(ownerUserId, groupId, messageId)
                    ?: error("Pending group intent conflicted without an existing row")
                require(
                    existing.sentAtMillis == sentAtMillis &&
                        pendingVault.decrypt(existing) == plaintext
                ) { "Pending group message ID already belongs to different content" }
                false
            }
        }
    }

    suspend fun pendingOutgoingIntents(ownerUserId: String): List<PendingGroupMessageIntent> {
        require(ID.matches(ownerUserId))
        requireAuthenticatedOwner(ownerUserId)
        return withContext(ioDispatcher) {
            requireAuthenticatedOwner(ownerUserId)
            dao.getPendingMessagesForOwner(ownerUserId).map { record ->
                requireAuthenticatedOwner(ownerUserId)
                PendingGroupMessageIntent(
                    ownerUserId = record.ownerUserId,
                    groupId = record.groupId,
                    messageId = record.messageId,
                    sentAtMillis = record.sentAtMillis,
                    plaintext = pendingVault.decrypt(record),
                )
            }
        }
    }


    /**
     * Recover only sends whose complete Olm fan-out has a durable commit marker.
     * Pending intents without a marker are deliberately left untouched: they may
     * still be waiting for key acknowledgements and must never be re-encrypted
     * blindly because that could advance the Megolm ratchet a second time.
     */
    suspend fun recoverCommittedOutgoingIntents(
        ownerUserId: String,
        groupId: String,
    ): Int {
        validateScope(ownerUserId, groupId)
        requireAuthenticatedOwner(ownerUserId)
        return withContext(ioDispatcher) {
            requireAuthenticatedOwner(ownerUserId)
            var recovered = 0
            val commits = dao.getOutgoingGroupCommits(ownerUserId, groupId)
            var firstFailure: Exception? = null
            for (commit in commits) {
                requireAuthenticatedOwner(ownerUserId)
                try {
                    val pending = dao.getPendingMessage(ownerUserId, groupId, commit.messageId)
                        ?: continue
                    require(pending.sentAtMillis == commit.sentAtMillis) {
                        "Pending group intent does not match its committed send timestamp"
                    }
                    val plaintext = pendingVault.decrypt(pending)
                    requireAuthenticatedOwner(ownerUserId)
                    val message = DecryptedGroupMessage(
                        ownerUserId = ownerUserId,
                        groupId = groupId,
                        messageId = commit.messageId,
                        membershipEpoch = commit.membershipEpoch,
                        senderUserId = ownerUserId,
                        senderDeviceId = commit.senderDeviceId,
                        megolmSessionId = commit.megolmSessionId,
                        messageIndex = commit.messageIndex,
                        sentAtMillis = commit.sentAtMillis,
                        text = plaintext,
                    )
                    val existing = dao.getMessage(ownerUserId, groupId, commit.messageId)
                    if (existing != null) {
                        require(vault.decrypt(existing) == message) {
                            "Existing sender timeline row conflicts with its durable group commit"
                        }
                        requireAuthenticatedOwner(ownerUserId)
                        dao.clearCommittedOutgoingPending(existing)
                    } else {
                        val encrypted = vault.encrypt(message, allowKeyCreation = !dao.hasStoredMessages())
                        requireAuthenticatedOwner(ownerUserId)
                        check(dao.commitOutgoingMessageAndDeletePending(
                            message = encrypted,
                            ownerUserId = ownerUserId,
                            groupId = groupId,
                            messageId = commit.messageId,
                        )) { "Recovered sender timeline row could not be committed" }
                    }
                    requireAuthenticatedOwner(ownerUserId)
                    recovered++
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // An account switch is a hard boundary, not an item failure:
                    // never continue touching another account's recovery rows.
                    requireAuthenticatedOwner(ownerUserId)
                    if (firstFailure == null) firstFailure = failure
                }
            }
            firstFailure?.let { throw it }
            recovered
        }
    }

    suspend fun deletePendingOutgoingIntent(ownerUserId: String, groupId: String, messageId: String) {
        validateScope(ownerUserId, groupId)
        require(MESSAGE_ID.matches(messageId))
        requireAuthenticatedOwner(ownerUserId)
        withContext(ioDispatcher) {
            requireAuthenticatedOwner(ownerUserId)
            dao.deletePendingMessage(ownerUserId, groupId, messageId)
            requireAuthenticatedOwner(ownerUserId)
        }
    }

    /**
     * Membership-gated read surface for encrypted group timelines. The supplied
     * snapshot must come from the authenticated Worker-backed membership source;
     * legacy group-member listeners must not be used to construct it.
     *
     * Latest-transform cancellation is important here: a membership update
     * cancels an in-flight decrypt. Decryption itself may be synchronous and
     * non-cooperative, so the explicit activity check prevents that stale result
     * from being emitted when the decrypt operation returns.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMessagesForMember(
        ownerUserId: String,
        groupId: String,
        membershipSnapshots: Flow<GroupCryptoMembershipSnapshot?>,
    ): Flow<List<DecryptedGroupMessage>> {
        require(ID.matches(ownerUserId)) { "Group message owner account ID is invalid" }
        require(ID.matches(groupId)) { "Group message group ID is invalid" }
        requireAuthenticatedOwner(ownerUserId)
        return combine(
            dao.observeMessages(ownerUserId, groupId),
            membershipSnapshots,
        ) { records, membership -> records to membership }
            .transformLatest { (records, membership) ->
                // A sign-out/account switch must clear visible plaintext without
                // terminating the membership stream; switching back can safely
                // re-evaluate the latest atomic snapshot.
                if (authenticatedUserId() != ownerUserId) {
                    emit(emptyList())
                    return@transformLatest
                }
                // A missing, malformed, or revoked membership snapshot must
                // immediately remove plaintext from the live timeline, but it
                // must not terminate collection: a later valid Worker snapshot
                // can restore access without reopening the conversation.
                if (
                    membership == null ||
                    !membership.isValid() ||
                    membership.groupId != groupId ||
                    ownerUserId !in membership.memberUserIds
                ) {
                    emit(emptyList())
                    return@transformLatest
                }
                val decrypted = decryptGroupMessages(records)
                currentCoroutineContext().ensureActive()
                if (authenticatedUserId() != ownerUserId) {
                    emit(emptyList())
                    return@transformLatest
                }
                emit(decrypted)
            }
            .flowOn(ioDispatcher)
    }

    suspend fun getMessageForMember(
        ownerUserId: String,
        membership: GroupCryptoMembershipSnapshot,
        messageId: String,
    ): DecryptedGroupMessage? =
        getMessageForMember(ownerUserId, membership.groupId, flowOf(membership), messageId)

    /**
     * One-shot member read backed by a live membership stream. If membership
     * changes while the stored ciphertext is being decrypted, the newer
     * snapshot cancels the older transform before plaintext can be returned.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getMessageForMember(
        ownerUserId: String,
        groupId: String,
        membershipSnapshots: Flow<GroupCryptoMembershipSnapshot?>,
        messageId: String,
    ): DecryptedGroupMessage? {
        validateScope(ownerUserId, groupId)
        require(MESSAGE_ID.matches(messageId)) { "Group message ID is invalid" }
        requireAuthenticatedOwner(ownerUserId)
        return withContext(ioDispatcher) {
            requireAuthenticatedOwner(ownerUserId)
            val record = dao.getMessage(ownerUserId, groupId, messageId)
            combine(flowOf(record), membershipSnapshots) { stored, membership ->
                stored to membership
            }.transformLatest { (stored, membership) ->
                requireAuthenticatedOwner(ownerUserId)
                requireReadableMembership(ownerUserId, groupId, membership)
                val decrypted = stored?.let { decryptGroupMessages(listOf(it)).single() }
                currentCoroutineContext().ensureActive()
                requireAuthenticatedOwner(ownerUserId)
                emit(decrypted)
            }.first()
        }
    }

    private fun requireReadableMembership(
        ownerUserId: String,
        expectedGroupId: String,
        membership: GroupCryptoMembershipSnapshot?,
    ) {
        val current = requireNotNull(membership) { "Group membership snapshot is unavailable" }
        require(ID.matches(ownerUserId)) { "Group message owner account ID is invalid" }
        require(current.isValid() && current.groupId == expectedGroupId) { "Group membership snapshot is invalid" }
        require(ownerUserId in current.memberUserIds) {
            "Authenticated account is not a current member of this group"
        }
    }

    fun observeMessages(ownerUserId: String, groupId: String): Flow<List<DecryptedGroupMessage>> {
        validateScope(ownerUserId, groupId)
        requireAuthenticatedOwner(ownerUserId)
        return dao.observeMessages(ownerUserId, groupId)
            .map { records ->
                requireAuthenticatedOwner(ownerUserId)
                val decrypted = records.map(vault::decrypt)
                requireAuthenticatedOwner(ownerUserId)
                decrypted
            }
            .flowOn(ioDispatcher)
    }

    suspend fun getMessage(
        ownerUserId: String,
        groupId: String,
        messageId: String,
    ): DecryptedGroupMessage? {
        validateScope(ownerUserId, groupId)
        require(MESSAGE_ID.matches(messageId)) { "Group message ID is invalid" }
        requireAuthenticatedOwner(ownerUserId)
        return withContext(ioDispatcher) {
            requireAuthenticatedOwner(ownerUserId)
            val decrypted = dao.getMessage(ownerUserId, groupId, messageId)?.let(vault::decrypt)
            requireAuthenticatedOwner(ownerUserId)
            decrypted
        }
    }

    private fun validateScope(ownerUserId: String, groupId: String) {
        require(ID.matches(ownerUserId)) { "Group message owner account ID is invalid" }
        require(ID.matches(groupId)) { "Group ID has an invalid format" }
    }

    private fun requireAuthenticatedOwner(ownerUserId: String) {
        require(authenticatedUserId() == ownerUserId) {
            "Authenticated account does not own this encrypted group message"
        }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val MESSAGE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val RECEIPT_ID = Regex("[A-Fa-f0-9]{64}")
        const val MAX_CIPHERTEXT_BYTES = 1024 * 1024
        val SHA256_HEX = Regex("[A-Fa-f0-9]{64}")
    }
}
