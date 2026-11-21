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
                encryptedRows.map(vault::decrypt)
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
            row?.let(vault::decrypt)
        }
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
