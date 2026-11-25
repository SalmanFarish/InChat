package com.example.inchat.data.repository

import android.util.Log
import com.example.inchat.InChatApplication
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.local.toCachedEntity
import com.example.inchat.data.local.toMessage
import com.example.inchat.data.worker.MessageNotificationWorker
import com.example.inchat.data.model.Conversation
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ChatRepository {

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

    private val localMessageDao =
        LocalChatDatabase.getInstance(InChatApplication.instance).messageDao()

    companion object {

        private const val TAG =
            "ChatRepository"


        /*
         * Reactions currently supported by InChat.
         */
        val SUPPORTED_REACTIONS =
            setOf(
                "❤️",
                "😂",
                "👍",
                "😮",
                "😢",
                "😡"
            )

        /*
         * In-memory snapshots keep already-opened Home and Chat
         * screens immediately populated while Firebase refreshes
         * the latest server state in the background.
         *
         * Room provides the persistent local message cache across app
         * restarts; Firebase remains the remote synchronization source.
         */
        private val conversationCache =
            ConcurrentHashMap<String, List<Conversation>>()

        private val messageCache =
            ConcurrentHashMap<String, List<Message>>()

        private val latestMessageCache =
            ConcurrentHashMap<String, List<Message>>()

        private val olderMessageCache =
            ConcurrentHashMap<String, List<Message>>()

        const val MESSAGE_EDIT_WINDOW_MS = 15L * 60L * 1000L
        const val OLDER_MESSAGE_PAGE_SIZE = 50

        // Prevent repeated warm-up reads while Home emits inbox metadata updates.
        private val prefetchedChatIds =
            ConcurrentHashMap.newKeySet<String>()

        // Per-account, per-chat view state. This never changes the shared
        // messages node and is safe to reuse for direct and group chats.
        private val messageVisibilityCache =
            ConcurrentHashMap<String, MessageVisibilitySnapshot>()

    }

    private data class MessageVisibilitySnapshot(
        val clearedAt: Long = 0L,
        val hiddenMessageIds: Set<String> = emptySet()
    ) {
        fun allows(message: Message): Boolean =
            message.id !in hiddenMessageIds &&
                (clearedAt <= 0L || message.timestamp > clearedAt)
    }

    private fun cacheKey(
        chatId: String,
        ownerUserId: String = auth.currentUser?.uid.orEmpty()
    ): String = "$ownerUserId::$chatId"

    /*
     * =========================================================
     * CHAT ID
     * =========================================================
     */
    fun getChatRoomId(
        userId1: String,
        userId2: String
    ): String {

        return if (
            userId1 < userId2
        ) {

            "${userId1}_${userId2}"

        } else {

            "${userId2}_${userId1}"
        }
    }

    /*
     * =========================================================
     * ENSURE CHAT
     * =========================================================
     */
    suspend fun ensureChat(
        currentUserId: String,
        otherUserId: String
    ): Result<String> {

        return try {
            val firebaseUser = auth.currentUser
            if (firebaseUser == null || firebaseUser.uid != currentUserId) {
                return Result.failure(
                    IllegalStateException("Authenticated user does not match chat owner.")
                )
            }

            if (
                currentUserId.isBlank() ||
                otherUserId.isBlank() ||
                currentUserId == otherUserId
            ) {
                return Result.failure(
                    IllegalArgumentException("Invalid chat participants")
                )
            }

            val chatId = getChatRoomId(currentUserId, otherUserId)
            val chatRef = database.getReference("chats").child(chatId)

            suspend fun ensureDirectChatType() {
                val typeRef = chatRef.child("type")
                when (typeRef.get().await().getValue(String::class.java)) {
                    "direct" -> Unit
                    null -> {
                        try {
                            typeRef.setValue("direct").await()
                        } catch (writeError: Exception) {
                            if (typeRef.get().await().getValue(String::class.java) != "direct") {
                                throw writeError
                            }
                        }
                    }
                    else -> throw IllegalStateException("The existing conversation is not a direct chat.")
                }
            }

            val participantA =
                if (currentUserId < otherUserId) currentUserId else otherUserId
            val participantB =
                if (currentUserId < otherUserId) otherUserId else currentUserId

            // Verify only participant fields. A root read would download
            // the legacy messages subtree as well.
            val existingA = try {
                chatRef.child("participantA").get().await().getValue(String::class.java)
            } catch (_: Exception) {
                null
            }
            val existingB = try {
                chatRef.child("participantB").get().await().getValue(String::class.java)
            } catch (_: Exception) {
                null
            }
            if (existingA != null || existingB != null) {
                if (existingA != participantA || existingB != participantB) {
                    throw IllegalStateException("The existing chat record has different participants.")
                }
                ensureDirectChatType()
                return Result.success(chatId)
            }

            /*
             * Create the entire participant record at chats/$chatId.
             * This matches the parent-level .write rule for creating a
             * direct chat; child participant rules are intentionally
             * restricted to unchanged values on existing chats.
             */
            try {
                chatRef.setValue(
                    mapOf(
                        "type" to "direct",
                        "participantA" to participantA,
                        "participantB" to participantB
                    )
                ).await()
            } catch (createError: Exception) {
                // Two clients can open the same chat at the same time.
                // If the other client created it first, verify the winner.
                val racedA = chatRef.child("participantA").get().await()
                    .getValue(String::class.java)
                val racedB = chatRef.child("participantB").get().await()
                    .getValue(String::class.java)
                if (racedA != participantA || racedB != participantB) throw createError
                ensureDirectChatType()
            }

            Result.success(chatId)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create or verify direct chat", e)
            Result.failure(e)
        }
    }

    /*
     * Return the latest message snapshot already received by this process.
     * Null means this chat has not been loaded yet; an empty list is a
     * valid cached result for a conversation with no messages.
     */
    fun getCachedMessages(chatId: String): List<Message>? {
        val key = cacheKey(chatId)
        val visibility = messageVisibilityCache[key] ?: return null
        return messageCache[key].orEmpty().filter(visibility::allows)
    }

    fun clearOlderMessagePages(chatId: String) {
        if (chatId.isBlank()) return
        val key = cacheKey(chatId)
        olderMessageCache.remove(key)
        latestMessageCache[key]?.let { latest ->
            messageCache[key] = latest
        }
    }

    private fun updateCachedMessage(
        chatId: String,
        transform: (Message) -> Message?
    ) {
        val key = cacheKey(chatId)
        // Cache lists are already chronological, and current transforms only
        // edit message fields or remove an item without changing its timestamp.
        // Preserve that order instead of sorting up to three cached lists again.
        messageCache.computeIfPresent(key) { _, messages ->
            messages.mapNotNull(transform)
        }
        olderMessageCache.computeIfPresent(key) { _, messages ->
            messages.mapNotNull(transform)
        }
        latestMessageCache.computeIfPresent(key) { _, messages ->
            messages.mapNotNull(transform)
        }
    }

    private suspend fun refreshDirectConversationPreview(chatId: String) {
        CloudflareNotificationRepository()
            .syncConversationPreview(chatId)
            .onFailure { previewError ->
                Log.w(
                    TAG,
                    "Could not refresh conversation preview after a message edit/delete.",
                    previewError
                )
            }
    }


    private suspend fun readMessageVisibility(
        chatId: String,
        ownerUserId: String
    ): MessageVisibilitySnapshot {
        val snapshot = database.getReference("userMessageVisibility")
            .child(ownerUserId)
            .child(chatId)
            .get()
            .await()
        val hiddenIds = snapshot.child("hiddenMessageIds")
            .children
            .filter { it.getValue(Boolean::class.java) == true }
            .mapNotNull { it.key }
            .toSet()
        return MessageVisibilitySnapshot(
            clearedAt = snapshot.child("clearedAt").getValue(Long::class.java) ?: 0L,
            hiddenMessageIds = hiddenIds
        ).also { messageVisibilityCache[cacheKey(chatId, ownerUserId)] = it }
    }

    private fun observeMessageVisibility(chatId: String): Flow<MessageVisibilitySnapshot> {
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank() || chatId.isBlank()) {
            return flowOf(MessageVisibilitySnapshot())
        }

        return callbackFlow {
            val key = cacheKey(chatId, ownerUserId)
            val ref = database.getReference("userMessageVisibility")
                .child(ownerUserId)
                .child(chatId)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val hiddenIds = snapshot.child("hiddenMessageIds")
                        .children
                        .filter { it.getValue(Boolean::class.java) == true }
                        .mapNotNull { it.key }
                        .toSet()
                    val visibility = MessageVisibilitySnapshot(
                        clearedAt = snapshot.child("clearedAt")
                            .getValue(Long::class.java) ?: 0L,
                        hiddenMessageIds = hiddenIds
                    )
                    messageVisibilityCache[key] = visibility
                    trySend(visibility)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Message visibility listener cancelled for $chatId", error.toException())
                    val cached = messageVisibilityCache[key] ?: MessageVisibilitySnapshot()
                    trySend(cached)
                }
            }

            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }
    }

    suspend fun deleteMessageForMe(
        chatId: String,
        messageId: String
    ): Result<Unit> {
        return try {
            val ownerUserId = auth.currentUser?.uid.orEmpty()
            if (ownerUserId.isBlank()) {
                return Result.failure(IllegalStateException("You are not authenticated."))
            }
            if (chatId.isBlank() || messageId.isBlank()) {
                return Result.failure(IllegalArgumentException("Invalid message data."))
            }

            // The message is already present in the chat UI. Do not perform
            // a preflight read: this per-user marker can be queued offline, and
            // the database rules independently verify chat membership.
            database.getReference("userMessageVisibility")
                .child(ownerUserId)
                .child(chatId)
                .child("hiddenMessageIds")
                .child(messageId)
                .setValue(true)
                .await()

            val key = cacheKey(chatId, ownerUserId)
            val oldVisibility = messageVisibilityCache[key] ?: MessageVisibilitySnapshot()
            val updatedVisibility = oldVisibility.copy(
                hiddenMessageIds = oldVisibility.hiddenMessageIds + messageId
            )
            messageVisibilityCache[key] = updatedVisibility
            Result.success(Unit)
        } catch (error: Exception) {
            Log.e(TAG, "Could not hide message $messageId for current user", error)
            Result.failure(error)
        }
    }

    suspend fun clearChatForMe(
        chatId: String,
        pendingMessageIds: Set<String>
    ): Result<Unit> {
        return try {
            val ownerUserId = auth.currentUser?.uid.orEmpty()
            if (ownerUserId.isBlank()) {
                return Result.failure(IllegalStateException("You are not authenticated."))
            }
            if (chatId.isBlank()) {
                return Result.failure(IllegalArgumentException("Invalid chat."))
            }

            val visibilityRef = database.getReference("userMessageVisibility")
                .child(ownerUserId)
                .child(chatId)
            val updates = mutableMapOf<String, Any>(
                "clearedAt" to ServerValue.TIMESTAMP
            )
            // Pending local messages can have timestamp 0 until Firebase assigns
            // their server timestamp. Store only those IDs; the clearedAt cutoff
            // hides all persisted history without growing this marker map.
            pendingMessageIds
                .filter(String::isNotBlank)
                .forEach { id ->
                    updates["hiddenMessageIds/$id"] = true
                }

            visibilityRef.updateChildren(updates).await()
            val clearedAt = visibilityRef.child("clearedAt")
                .get().await().getValue(Long::class.java)
                ?: FirebaseServerClock.now()

            try {
                database.getReference("conversationPreferences")
                    .child(ownerUserId)
                    .child(chatId)
                    .child("clearedAt")
                    .setValue(clearedAt)
                    .await()
            } catch (preferenceError: Exception) {
                Log.w(TAG, "History cleared, but the Home preview preference could not be saved.", preferenceError)
            }

            val key = cacheKey(chatId, ownerUserId)
            val oldVisibility = messageVisibilityCache[key] ?: MessageVisibilitySnapshot()
            val updatedVisibility = oldVisibility.copy(
                clearedAt = maxOf(oldVisibility.clearedAt, clearedAt),
                hiddenMessageIds = oldVisibility.hiddenMessageIds + pendingMessageIds
            )
            messageVisibilityCache[key] = updatedVisibility
            var unreadResetWithGroupCursor = false
            // Direct-chat preview metadata is owned by this user. Group preview
            // fields are maintained by the group sync path, so only unreadCount
            // is updated there.
            val chatType = database.getReference("chats")
                .child(chatId)
                .child("type")
                .get()
                .await()
                .getValue(String::class.java)
            if (chatType == "group") {
                // This cursor update is correctness-critical: let failures
                // propagate instead of reporting a successful clear with no
                // read cursor, which would allow delayed Worker events to
                // restore the unread badge.
                database.reference.updateChildren(
                    mapOf(
                        "groupReads/$ownerUserId/$chatId" to clearedAt,
                        "chats/$chatId/groupReadCursors/$ownerUserId" to clearedAt,
                        "userChats/$ownerUserId/$chatId/unreadCount" to 0L
                    )
                ).await()
                unreadResetWithGroupCursor = true
            } else {
                // Direct-chat preview metadata is best-effort. Its existing
                // private read cursor is maintained separately below.
                try {
                    database.getReference("userChats")
                        .child(ownerUserId)
                        .child(chatId)
                        .updateChildren(
                            mapOf(
                                "lastMessage" to "No messages yet",
                                "lastTimestamp" to clearedAt,
                                "lastSenderId" to ownerUserId
                            )
                        )
                        .await()

                    database.getReference("chatReadState")
                        .child(ownerUserId)
                        .child(chatId)
                        .setValue(clearedAt)
                        .await()
                } catch (previewError: Exception) {
                    Log.w(TAG, "Chat history cleared, but the direct inbox preview could not be refreshed.", previewError)
                }
            }

            // Direct chats keep their existing separate read-cursor update;
            // group chats reset unread atomically with their groupReads cursor.
            if (!unreadResetWithGroupCursor) {
                database.getReference("userChats")
                    .child(ownerUserId)
                    .child(chatId)
                    .child("unreadCount")
                    .setValue(0L)
                    .await()
            }

            Result.success(Unit)
        } catch (error: Exception) {
            Log.e(TAG, "Could not clear chat $chatId for current user", error)
            Result.failure(error)
        }
    }

    /*
     * =========================================================
     * MESSAGE LISTENER
     * =========================================================
     */
    /**
     * Emits the locally stored history immediately, then keeps it in sync
     * with Firebase. The same flow is used for direct and group chats.
     */
    fun getLocalMessagesFlow(chatId: String): Flow<List<Message>> {
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank() || chatId.isBlank()) return flowOf(emptyList())

        return combine(
            localMessageDao.observeMessages(ownerUserId, chatId)
                .map { entities -> entities.map { it.toMessage() } },
            observeMessageVisibility(chatId)
        ) { messages, visibility ->
            messages.filter(visibility::allows)
        }
    }

    /**
     * Observe the local sending state for this account and conversation.
     * The IDs are stored separately from the transient ViewModel state so
     * pending indicators survive process recreation.
     */
    fun observePendingMessageIds(chatId: String): Flow<Set<String>> {
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank() || chatId.isBlank()) return flowOf(emptySet())

        return localMessageDao.observePendingMessageIds(ownerUserId, chatId)
            .map { it.toSet() }
    }

    /**
     * Legacy cache helper retained for migration/reconciliation only.
     * New direct-message sends must go through the encrypted Olm coordinator.
     * A missing legacy Firebase record is never delivered by the background worker.
     */
    /**
     * Legacy plaintext cache API retained only for source compatibility.
     * New direct messages must be committed by the encrypted Olm coordinator.
     */
    @Deprecated(
        message = "Plaintext outgoing-message caching is disabled; use encrypted Olm sending.",
    )
    suspend fun cacheOutgoingMessage(chatId: String, message: Message) {
        throw UnsupportedOperationException(
            "Plaintext outgoing-message caching is disabled. Use the encrypted Olm coordinator.",
        )
    }

    suspend fun markOutgoingMessageSent(chatId: String, messageId: String) {
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank() || chatId.isBlank() || messageId.isBlank()) return
        localMessageDao.markMessageSent(ownerUserId, chatId, messageId)
    }

    suspend fun removeFailedOutgoingMessage(chatId: String, messageId: String) {
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank() || chatId.isBlank() || messageId.isBlank()) return
        localMessageDao.deleteMessage(ownerUserId, chatId, messageId)
    }

    /**
     * Read locally queued messages for a conversation. Direct and group chats
     * share the same Room table and pending-state mechanism.
     */
    suspend fun getPendingMessages(chatId: String): List<Message> {
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank() || chatId.isBlank()) return emptyList()
        return localMessageDao.getPendingMessages(ownerUserId, chatId)
            .map { it.toMessage() }
    }

    /**
     * Check whether a pending message reached Firebase. A malformed existing
     * record throws instead of being treated as missing and overwritten.
     */
    /** Legacy API retained for compatibility; it looks only in local Room. */
    suspend fun getRemoteMessage(chatId: String, messageId: String): Message? {
        require(chatId.isNotBlank() && messageId.isNotBlank()) { "Invalid message lookup." }
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        require(ownerUserId.isNotBlank()) { "You are not authenticated." }
        return localMessageDao.getMessage(ownerUserId, chatId, messageId)?.toMessage()
    }

    /**
     * Reconcile legacy pending rows against Firebase. Existing remote records
     * are marked sent, but missing records are retried only through the caller's
     * encrypted sender. The legacy plaintext send method is never used here.
     */
    /**
     * Legacy plaintext pending rows remain local. The encrypted Room outbox
     * owns delivery retries; this compatibility method performs no RTDB reads.
     */
    @Deprecated("Legacy plaintext pending messages are not reconciled remotely.")
    suspend fun reconcilePendingOutgoingMessages(
        chatId: String,
        receiverId: String,
        receiverNickname: String,
        sendEncryptedMessage: suspend (Message) -> Result<Unit>,
    ) {
        // Preserve unsupported historical pending rows on this device.
    }

    /*
     * =========================================================
     * MESSAGE LISTENER
     * =========================================================
     */
    fun getMessagesFlow(chatId: String): Flow<List<Message>> =
        getLocalMessagesFlow(chatId)

    /**
     * Older messages are available only when already cached on this device.
     * The legacy Firebase history page is intentionally not fetched.
     */
    suspend fun getOlderMessages(
        chatId: String,
        beforeTimestamp: Long,
        pageSize: Int = OLDER_MESSAGE_PAGE_SIZE
    ): Result<List<Message>> {
        if (chatId.isBlank() || beforeTimestamp <= 0L || pageSize !in 1..100) {
            return Result.failure(IllegalArgumentException("Invalid message history request."))
        }
        return Result.success(emptyList())
    }

    /**
     * Warm the local message cache for the most recently active chats while
     * the user is on Home, so opening one is not a cold Firebase fetch.
     * Only the latest 30 messages from up to 8 chats are prefetched.
     */
    /** Room history is already local; no Firebase message prefetch is needed. */
    @Deprecated("Message history is sourced from local Room storage.")
    suspend fun preloadRecentMessages(
        ownerUserId: String,
        conversations: List<Conversation>,
        maxChats: Int = 8
    ) {
        // No-op by design.
    }

    /*
     * =========================================================
     * CONVERSATIONS
     * =========================================================
     */
    fun getConversationsFlow(
        currentUserId: String
    ): Flow<List<Conversation>> =
        callbackFlow {

            val conversationsRef:
                    Query =
                database
                    .getReference(
                        "userChats"
                    )
                    .child(
                        currentUserId
                    )
                    .orderByChild(
                        "lastTimestamp"
                    )
                    .limitToLast(
                        50
                    )


            /*
             * Reuse the last known in-memory snapshot immediately.
             * The live Firebase listener below remains authoritative
             * and will replace this snapshot as soon as fresh data
             * is available.
             */
            conversationCache[currentUserId]
                ?.let { cachedConversations ->
                    trySend(
                        cachedConversations
                    )
                }

            val listener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot:
                        DataSnapshot
                    ) {

                        val conversations =
                            mutableListOf<Conversation>()

                        for (
                        childSnapshot in
                        snapshot.children
                        ) {

                            val conversation =
                                childSnapshot
                                    .getValue(
                                        Conversation::class.java
                                    )

                            if (
                                conversation != null &&
                                conversation.chatId.isNotBlank() &&
                                (
                                    (
                                        conversation.chatType ==
                                                "group" &&
                                        conversation.groupName.isNotBlank()
                                    ) ||
                                    (
                                        conversation.chatType !=
                                                "group" &&
                                        conversation.otherUserId.isNotBlank() &&
                                        conversation.otherUsername.isNotBlank()
                                    )
                                )
                            ) {

                                conversations.add(
                                    conversation
                                )
                            }
                        }

                        val immutableConversations =
                            conversations
                                .sortedByDescending {
                                    it.lastTimestamp
                                }
                                .toList()

                        conversationCache[currentUserId] =
                            immutableConversations


                        trySend(
                            immutableConversations
                        )
                    }

                    override fun onCancelled(
                        error:
                        DatabaseError
                    ) {
                        Log.e(
                            TAG,
                            "Conversations listener cancelled for $currentUserId: ${error.message}"
                        )
                        trySend(emptyList())
                        close()
                    }
                }

            conversationsRef
                .addValueEventListener(
                    listener
                )

            awaitClose {

                conversationsRef
                    .removeEventListener(
                        listener
                    )
            }
        }

    /*
     * =========================================================
     * MESSAGE ID
     * =========================================================
     */
    fun generateMessageId(chatId: String): String? {
        if (chatId.isBlank()) return null
        return java.util.UUID.randomUUID().toString().replace("-", "")
    }

    /*
     * =========================================================
     * DELIVERY RECEIPT
     * =========================================================
     */
    fun getOtherUserDeliveredTimestampFlow(
        chatId: String,
        otherUserId: String
    ): Flow<Long> = callbackFlow {
        val deliveredRef = database
            .getReference("chats")
            .child(chatId)
            .child("deliveredBy")
            .child(otherUserId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.getValue(Long::class.java) ?: 0L)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Delivery listener cancelled for $chatId: ${error.message}")
                trySend(0L)
                close()
            }
        }
        deliveredRef.addValueEventListener(listener)
        awaitClose { deliveredRef.removeEventListener(listener) }
    }

    suspend fun markConversationDelivered(
        currentUserId: String,
        chatId: String,
        timestamp: Long
    ): Result<Unit> {
        return try {
            if (currentUserId.isBlank() || chatId.isBlank() || timestamp <= 0L) {
                return Result.failure(IllegalArgumentException("Invalid delivery state"))
            }
            val updates = mapOf<String, Any>(
                "chats/$chatId/deliveredBy/$currentUserId" to timestamp
            )
            database.reference.updateChildren(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /*
     * =========================================================
     * READ RECEIPT
     * =========================================================
     */
    fun getOtherUserReadTimestampFlow(
        chatId: String,
        otherUserId: String
    ): Flow<Long> =
        callbackFlow {

            val readRef =
                database.getReference("chatReadReceipts")
                    .child(chatId)
                    .child(otherUserId)


            val listener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot:
                        DataSnapshot
                    ) {

                        val timestamp =
                            snapshot.getValue(
                                Long::class.java
                            ) ?: 0L

                        trySend(
                            timestamp
                        )
                    }

                    override fun onCancelled(
                        error:
                        DatabaseError
                    ) {
                        Log.e(
                            TAG,
                            "Read receipt listener cancelled for $chatId: ${error.message}"
                        )
                        trySend(0L)
                        close()
                    }
                }

            readRef
                .addValueEventListener(
                    listener
                )

            awaitClose {

                readRef
                    .removeEventListener(
                        listener
                    )
            }
        }

    suspend fun clearReadReceipt(
        currentUserId: String,
        chatId: String
    ): Result<Unit> {
        return try {
            if (auth.currentUser?.uid != currentUserId ||
                currentUserId.isBlank() || chatId.isBlank()
            ) {
                return Result.failure(IllegalStateException("Invalid read-receipt owner or chat."))
            }
            database.getReference("chatReadReceipts")
                .child(chatId).child(currentUserId).removeValue().await()
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /*
     * =========================================================
     * MARK CONVERSATION READ
     * =========================================================
     */
    suspend fun markConversationRead(
        currentUserId: String,
        chatId: String,
        sendReadReceipt: Boolean = true
    ): Result<Unit> {

        return try {

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match read-state owner."
                    )
                )
            }

            if (
                currentUserId.isBlank() ||
                chatId.isBlank()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid read state"
                    )
                )
            }

            val updates =
                mutableMapOf<String, Any?>(

                    "userChats/$currentUserId/$chatId/unreadCount" to
                            0L,

                )

            updates[
                "chatReadReceipts/$chatId/$currentUserId"
            ] =
                if (
                    sendReadReceipt
                ) {
                    ServerValue.TIMESTAMP
                } else {
                    null
                }

            // Store read state separately from the inbox entry. A brand-new
            // direct conversation may not have a complete userChats record yet.
            // The Worker reads this private cursor before it increments unread.
            database.getReference("chatReadState")
                .child(currentUserId)
                .child(chatId)
                .setValue(ServerValue.TIMESTAMP)
                .await()

            database.reference
                .updateChildren(
                    updates
                )
                .await()

            Result.success(
                Unit
            )

        } catch (e: Exception) {

            Result.failure(
                e
            )
        }
    }

    /*
     * =========================================================
     * DELETE CONVERSATION
     * =========================================================
     */
    suspend fun deleteConversation(
        currentUserId: String,
        chatId: String
    ): Result<Unit> {

        return try {

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match conversation owner."
                    )
                )
            }

            if (
                currentUserId.isBlank() ||
                chatId.isBlank()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid conversation data"
                    )
                )
            }

            database
                .getReference(
                    "userChats"
                )
                .child(
                    currentUserId
                )
                .child(
                    chatId
                )
                .removeValue()
                .await()

            conversationCache.computeIfPresent(
                currentUserId
            ) { _, conversations ->
                conversations.filterNot {
                    it.chatId ==
                            chatId
                }
            }

            Result.success(
                Unit
            )

        } catch (e: Exception) {

            Result.failure(
                e
            )
        }
    }

    /*
     * =========================================================
     * SEND MESSAGE
     * =========================================================
     */
    /**
     * Legacy plaintext direct-send API retained only so older call sites fail
     * closed at runtime. New direct messages must use OlmDirectMessageSendCoordinator.
     */
    @Deprecated(
        message = "Plaintext direct-message sending is disabled; use encrypted Olm sending.",
    )
    suspend fun sendMessage(
        chatId: String,
        messageId: String,
        text: String,
        senderId: String,
        senderNickname: String,
        receiverId: String,
        receiverNickname: String,
        replyTo: ReplyTo? = null,
        attachment: com.example.inchat.data.model.Attachment? = null,
        onQueued: () -> Unit = {},
    ): Result<String> = Result.failure(
        UnsupportedOperationException(
            "Plaintext direct-message delivery is disabled. Use the encrypted Olm coordinator.",
        ),
    )

    /*
     * =========================================================
     * EDIT MESSAGE
     * =========================================================
     *
     * Only the text and edited flag are changed.
     *
     * The original:
     *
     * - message ID
     * - sender
     * - nickname
     * - timestamp
     * - reply information
     * - reactions
     *
     * remain untouched.
     */
    /** Legacy Firebase message edits are disabled; encrypted edits need their own protocol. */
    @Deprecated("Editing messages requires an encrypted edit protocol.")
    suspend fun editMessage(
        chatId: String,
        messageId: String,
        userId: String,
        newText: String
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Message editing is unavailable for encrypted messages.")
    )

    /** Legacy Firebase reactions are disabled until an encrypted reaction protocol exists. */
    @Deprecated("Message reactions require an encrypted reaction protocol.")
    suspend fun setReaction(
        chatId: String,
        messageId: String,
        userId: String,
        reaction: String
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Reactions are unavailable for encrypted messages.")
    )

    @Deprecated("Message reactions require an encrypted reaction protocol.")
    suspend fun removeReaction(
        chatId: String,
        messageId: String,
        userId: String
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Reactions are unavailable for encrypted messages.")
    )

    @Deprecated("Message reactions require an encrypted reaction protocol.")
    suspend fun toggleReaction(
        chatId: String,
        messageId: String,
        userId: String,
        reaction: String
    ): Result<Boolean> = Result.failure(
        UnsupportedOperationException("Reactions are unavailable for encrypted messages.")
    )

    /** Global deletion must not mutate the retired Firebase messages subtree. */
    @Deprecated("Global deletion requires an encrypted deletion protocol.")
    suspend fun deleteMessage(
        chatId: String,
        messageId: String
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Deleting messages for everyone is unavailable in the encrypted timeline.")
    )

}