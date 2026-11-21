package com.example.inchat.crypto

import java.security.MessageDigest
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Commits one native-staged Megolm plaintext to the isolated encrypted group
 * store. The replay key and AES-GCM ciphertext are inserted in one Room
 * transaction; callers must finalize the native staged receipt only afterwards.
 */
internal class EncryptedGroupMessageRepository(
    private val dao: EncryptedGroupMessageDao,
    private val vault: EncryptedGroupMessageVault = EncryptedGroupMessageVault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authenticatedUserId: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid },
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
     * Membership-gated read surface for encrypted group timelines. The supplied
     * snapshot must come from the authenticated Worker-backed membership source;
     * legacy group-member listeners must not be used to construct it.
     */
    fun observeMessagesForMember(
        ownerUserId: String,
        groupId: String,
        membershipSnapshots: Flow<GroupCryptoMembershipSnapshot?>,
    ): Flow<List<DecryptedGroupMessage>> {
        require(ID.matches(ownerUserId)) { "Group message owner account ID is invalid" }
        require(ID.matches(groupId)) { "Group ID has an invalid format" }
        requireAuthenticatedOwner(ownerUserId)
        return combine(
            dao.observeMessages(ownerUserId, groupId),
            membershipSnapshots,
        ) { records, membership ->
            requireAuthenticatedOwner(ownerUserId)
            requireReadableMembership(ownerUserId, groupId, membership)
            val decrypted = records.map(vault::decrypt)
            requireAuthenticatedOwner(ownerUserId)
            decrypted
        }.flowOn(ioDispatcher)
    }

    suspend fun getMessageForMember(
        ownerUserId: String,
        membership: GroupCryptoMembershipSnapshot,
        messageId: String,
    ): DecryptedGroupMessage? {
        requireReadableMembership(ownerUserId, membership.groupId, membership)
        requireAuthenticatedOwner(ownerUserId)
        return getMessage(ownerUserId, membership.groupId, messageId)
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
        require(ID.matches(groupId)) { "Group message group ID is invalid" }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
        val MESSAGE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val RECEIPT_ID = Regex("[A-Fa-f0-9]{64}")
        const val MAX_CIPHERTEXT_BYTES = 1024 * 1024
        val SHA256_HEX = Regex("[A-Fa-f0-9]{64}")
    }
}
