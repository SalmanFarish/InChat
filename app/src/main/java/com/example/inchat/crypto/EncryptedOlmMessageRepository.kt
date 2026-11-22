package com.example.inchat.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.google.firebase.auth.FirebaseAuth

/**
 * Reads encrypted_olm_messages and decrypts text only in process memory on an IO dispatcher.
 *
 * This repository is deliberately not connected to ChatRepository or the existing chat UI.
 */
internal class EncryptedOlmMessageRepository(
    private val dao: EncryptedOlmMessageDao,
    private val vault: EncryptedOlmMessageVault = EncryptedOlmMessageVault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authenticatedUserId: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid },
) {
    fun observeMessages(
        ownerUserId: String,
        conversationId: String,
    ): Flow<List<DecryptedOlmMessage>> {
        validateScope(ownerUserId, conversationId)
        return dao.observeMessages(ownerUserId, conversationId)
            .map { encryptedRows ->
                requireAuthenticatedOwner(ownerUserId)
                val decrypted = encryptedRows.map(vault::decrypt)
                requireAuthenticatedOwner(ownerUserId)
                decrypted
            }
            .flowOn(ioDispatcher)
    }

    suspend fun getMessage(
        ownerUserId: String,
        conversationId: String,
        messageId: String,
    ): DecryptedOlmMessage? {
        validateScope(ownerUserId, conversationId)
        require(MESSAGE_ID_PATTERN.matches(messageId)) {
            "Encrypted message ID has an invalid format"
        }
        return withContext(ioDispatcher) {
            val row = dao.getMessage(ownerUserId, conversationId, messageId)
            requireAuthenticatedOwner(ownerUserId)
            val decrypted = row?.let(vault::decrypt)
            requireAuthenticatedOwner(ownerUserId)
            decrypted
        }
    }

    /**
     * Persist the sender's own local timeline copy using the same Keystore AES-GCM
     * envelope as inbound messages. This is a storage primitive only; callers must
     * still coordinate it with the durable Olm outbox before enabling live sending.
     */
    suspend fun saveOutgoingMessage(message: DecryptedOlmMessage) = withContext(ioDispatcher) {
        validateScope(message.ownerUserId, message.conversationId)
        require(message.isOutgoing) {
            "Outgoing encrypted message owner must match the authenticated sender"
        }
        require(message.recipientUserId == message.conversationPeerUserId) {
            "Outgoing direct message recipient must match the conversation peer"
        }
        require(message.senderUserId != message.recipientUserId) {
            "An outgoing direct message must target another account"
        }
        requireAuthenticatedOwner(message.ownerUserId)

        val encrypted = vault.encrypt(message)
        val inserted = dao.insertMessageIfAbsent(encrypted)
        requireAuthenticatedOwner(message.ownerUserId)
        if (inserted == -1L) {
            // A replay with identical authenticated metadata and plaintext is safe.
            // A reused ID with different content or routing must fail closed.
            val existing = dao.getMessage(
                message.ownerUserId,
                message.conversationId,
                message.messageId,
            ) ?: error("Encrypted outgoing message disappeared after an insert conflict")
            val decrypted = vault.decrypt(existing)
            require(
                decrypted.copy(text = "") == message.copy(text = "") &&
                    decrypted.text == message.text
            ) {
                "Outgoing encrypted message ID is already bound to different content or metadata"
            }
        }
        requireAuthenticatedOwner(message.ownerUserId)
    }

    private fun validateScope(ownerUserId: String, conversationId: String) {
        require(ACCOUNT_ID_PATTERN.matches(ownerUserId)) {
            "Encrypted message owner account ID has an invalid format"
        }
        requireAuthenticatedOwner(ownerUserId)
        require(
            conversationId.length in 3..MAX_CONVERSATION_ID_LENGTH &&
                CONVERSATION_ID_PATTERN.matches(conversationId),
        ) {
            "Encrypted message conversation ID has an invalid format"
        }
    }

    private fun requireAuthenticatedOwner(ownerUserId: String) {
        require(authenticatedUserId() == ownerUserId) {
            "Encrypted messages can only be read by their authenticated owner account"
        }
    }

    private companion object {
        const val MAX_CONVERSATION_ID_LENGTH = 260
        val ACCOUNT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val CONVERSATION_ID_PATTERN = Regex("[A-Za-z0-9_-]{3,260}")
    }
}
