package com.example.inchat.crypto

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Decrypted direct-message intent exists only briefly in process memory. */
internal data class PendingDirectMessageIntent(
    val ownerUserId: String,
    val conversationId: String,
    val conversationPeerUserId: String,
    val messageId: String,
    val sentAtMillis: Long,
    val plaintext: String,
)

/** Prevents the immediate and periodic WorkManager chains from retrying the same pending intents concurrently. */
internal object EncryptedDirectPendingMessageRetryLock {
    val mutex = Mutex()
}

internal class EncryptedDirectPendingMessageRepository(
    private val dao: EncryptedDirectPendingMessageDao,
    private val vault: EncryptedDirectPendingMessageVault = EncryptedDirectPendingMessageVault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authenticatedUserId: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid },
) {
    /** Persist a new local send intent before any network operation begins. */
    suspend fun savePendingOutgoingIntent(
        ownerUserId: String,
        conversationId: String,
        peerUserId: String,
        messageId: String,
        sentAtMillis: Long,
        plaintext: String,
    ): Boolean = withContext(ioDispatcher) {
        validateScope(ownerUserId, conversationId, peerUserId, messageId, sentAtMillis)
        require(plaintext.isNotBlank()) { "Pending direct message cannot be empty" }
        requireAuthenticatedOwner(ownerUserId)
        val encrypted = vault.encrypt(
            ownerUserId,
            conversationId,
            peerUserId,
            messageId,
            sentAtMillis,
            plaintext,
        )
        requireAuthenticatedOwner(ownerUserId)
        val inserted = dao.insertPendingMessage(encrypted)
        requireAuthenticatedOwner(ownerUserId)
        if (inserted != -1L) {
            true
        } else {
            val existing = dao.getPendingMessage(ownerUserId, conversationId, messageId)
                ?: error("Pending direct-message insert conflicted without an existing row")
            require(
                existing.conversationPeerUserId == peerUserId &&
                    existing.sentAtMillis == sentAtMillis &&
                    vault.decrypt(existing) == plaintext
            ) {
                "Pending direct message ID already belongs to different content or metadata"
            }
            false
        }
    }

    /** Read all account-scoped pending intents for the background delivery worker. */
    suspend fun pendingOutgoingIntents(ownerUserId: String): List<PendingDirectMessageIntent> =
        withContext(ioDispatcher) {
            require(ID.matches(ownerUserId)) { "Pending direct-message owner ID is invalid" }
            requireAuthenticatedOwner(ownerUserId)
            dao.getPendingMessagesForOwner(ownerUserId).map { record ->
                requireAuthenticatedOwner(ownerUserId)
                record.toIntent()
            }
        }

    /** Live encrypted local timeline rows waiting for delivery in one conversation. */
    fun observePendingOutgoingIntents(
        ownerUserId: String,
        conversationId: String,
        peerUserId: String,
    ): Flow<List<PendingDirectMessageIntent>> {
        validateScope(ownerUserId, conversationId, peerUserId, "pending-observer", 0L)
        requireAuthenticatedOwner(ownerUserId)
        return dao.observePendingMessages(ownerUserId, conversationId)
            .map { records ->
                requireAuthenticatedOwner(ownerUserId)
                records.map { record ->
                    require(
                        record.ownerUserId == ownerUserId &&
                            record.conversationId == conversationId &&
                            record.conversationPeerUserId == peerUserId
                    ) { "Pending direct-message intent escaped its requested conversation scope" }
                    record.toIntent()
                }.also { requireAuthenticatedOwner(ownerUserId) }
            }
            .flowOn(ioDispatcher)
    }

    suspend fun isPendingOutgoingIntent(
        ownerUserId: String,
        conversationId: String,
        messageId: String,
    ): Boolean = withContext(ioDispatcher) {
        require(ID.matches(ownerUserId) && ID.matches(messageId))
        require(conversationId.matches(CONVERSATION_ID))
        requireAuthenticatedOwner(ownerUserId)
        val exists = dao.getPendingMessage(ownerUserId, conversationId, messageId) != null
        requireAuthenticatedOwner(ownerUserId)
        exists
    }

    /** A pending intent is deleted only after its complete encrypted fan-out is committed. */
    suspend fun deletePendingOutgoingIntent(
        ownerUserId: String,
        conversationId: String,
        messageId: String,
    ): Int = withContext(ioDispatcher) {
        require(ID.matches(ownerUserId) && ID.matches(messageId))
        require(conversationId.matches(CONVERSATION_ID))
        requireAuthenticatedOwner(ownerUserId)
        val deleted = dao.deletePendingMessage(ownerUserId, conversationId, messageId)
        requireAuthenticatedOwner(ownerUserId)
        deleted
    }

    suspend fun deleteConversationForMe(ownerUserId: String, conversationId: String): Int =
        withContext(ioDispatcher) {
            require(ID.matches(ownerUserId) && conversationId.matches(CONVERSATION_ID))
            requireAuthenticatedOwner(ownerUserId)
            val deleted = dao.deleteConversationForOwner(ownerUserId, conversationId)
            requireAuthenticatedOwner(ownerUserId)
            deleted
        }

    private fun EncryptedDirectPendingMessageEntity.toIntent() = PendingDirectMessageIntent(
        ownerUserId = ownerUserId,
        conversationId = conversationId,
        conversationPeerUserId = conversationPeerUserId,
        messageId = messageId,
        sentAtMillis = sentAtMillis,
        plaintext = vault.decrypt(this),
    )

    private fun validateScope(
        ownerUserId: String,
        conversationId: String,
        peerUserId: String,
        messageId: String,
        sentAtMillis: Long,
    ) {
        require(ID.matches(ownerUserId) && ID.matches(peerUserId) && ownerUserId != peerUserId) {
            "Pending direct-message participant ID is invalid"
        }
        require(
            conversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(
                ownerUserId,
                peerUserId,
            )
        ) { "Pending direct-message conversation does not match its participants" }
        require(ID.matches(messageId) && sentAtMillis >= 0L) {
            "Pending direct-message ID or timestamp is invalid"
        }
    }

    private fun requireAuthenticatedOwner(ownerUserId: String) {
        require(authenticatedUserId() == ownerUserId) {
            "Pending encrypted messages can only be accessed by their authenticated owner"
        }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
        val CONVERSATION_ID = Regex("[A-Za-z0-9_-]{3,260}")
    }
}
