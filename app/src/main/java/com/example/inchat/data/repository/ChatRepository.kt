package com.example.inchat.data.repository

import android.util.Log
import com.example.inchat.InChatApplication
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.local.toCachedEntity
import com.example.inchat.data.local.toMessage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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

        // Chat IDs verified during this process do not need another
        // network existence check every time the user reopens them.
        private val knownChatIds =
            ConcurrentHashMap.newKeySet<String>()

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

            if (knownChatIds.contains(chatId)) {
                return Result.success(chatId)
            }

            val participantA =
                if (currentUserId < otherUserId) currentUserId else otherUserId
            val participantB =
                if (currentUserId < otherUserId) otherUserId else currentUserId

            val chatRef = database.getReference("chats").child(chatId)

            fun matchesExistingParticipants(
                snapshot: DataSnapshot
            ): Boolean {
                val existingA = snapshot.child("participantA")
                    .getValue(String::class.java)
                val existingB = snapshot.child("participantB")
                    .getValue(String::class.java)
                return snapshot.exists() &&
                    existingA == participantA &&
                    existingB == participantB
            }

            /*
             * Read first so an existing chat (including its messages,
             * appearance and receipt data) is never overwritten.
             * A brand-new chat may deny this read because its participants
             * are not yet recorded; in that case we proceed to creation.
             */
            val existingSnapshot = try {
                chatRef.get().await()
            } catch (_: Exception) {
                null
            }

            if (existingSnapshot != null && existingSnapshot.exists()) {
                if (!matchesExistingParticipants(existingSnapshot)) {
                    throw IllegalStateException(
                        "The existing chat record has different participants."
                    )
                }
                knownChatIds.add(chatId)
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
                        "participantA" to participantA,
                        "participantB" to participantB
                    )
                ).await()
            } catch (createError: Exception) {
                // Two clients can open the same chat at the same time.
                // If the other client created it first, verify the winner.
                val racedSnapshot = chatRef.get().await()
                if (!matchesExistingParticipants(racedSnapshot)) {
                    throw createError
                }
            }

            knownChatIds.add(chatId)
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
        messageCache.computeIfPresent(key) { _, messages ->
            messages.mapNotNull(transform).sortedBy { it.timestamp }
        }
        olderMessageCache.computeIfPresent(key) { _, messages ->
            messages.mapNotNull(transform).sortedBy { it.timestamp }
        }
        latestMessageCache.computeIfPresent(key) { _, messages ->
            messages.mapNotNull(transform).sortedBy { it.timestamp }
        }
    }

    private suspend fun refreshDirectConversationPreview(chatId: String) {
        try {
            val chatSnapshot = database.getReference("chats")
                .child(chatId)
                .get()
                .await()

            if (!chatSnapshot.exists()) {
                return
            }

            if (
                chatSnapshot.child("type").getValue(String::class.java) == "group"
            ) {
                CloudflareNotificationRepository()
                    .syncConversationPreview(chatId)
                    .onFailure { previewError ->
                        Log.w(
                            TAG,
                            "Group preview could not be refreshed after a message edit/delete.",
                            previewError
                        )
                    }
                return
            }

            val participantA = chatSnapshot.child("participantA")
                .getValue(String::class.java)
                .orEmpty()
            val participantB = chatSnapshot.child("participantB")
                .getValue(String::class.java)
                .orEmpty()

            if (participantA.isBlank() || participantB.isBlank()) {
                return
            }

            val latestSnapshot = database.getReference("chats")
                .child(chatId)
                .child("messages")
                .orderByChild("timestamp")
                .limitToLast(1)
                .get()
                .await()

            val latest = latestSnapshot.children
                .mapNotNull { it.getValue(Message::class.java) }
                .maxByOrNull { it.timestamp }

            val previewText =
                latest?.text?.takeIf { it.isNotBlank() } ?: "No messages yet"
            val previewTimestamp = latest?.timestamp ?: 0L
            // Existing userChats validation requires lastSenderId to be a participant.
            val previewSenderId = latest?.senderId ?: participantA

            val previewUpdates = mapOf<String, Any>(
                "lastMessage" to previewText,
                "lastTimestamp" to previewTimestamp,
                "lastSenderId" to previewSenderId
            )

            for (uid in listOf(participantA, participantB)) {
                try {
                    // Partial updates preserve each participant's own unread count
                    // and do not recreate an inbox entry they removed.
                    database.getReference("userChats")
                        .child(uid)
                        .child(chatId)
                        .updateChildren(previewUpdates)
                        .await()
                } catch (error: Exception) {
                    Log.w(
                        TAG,
                        "Could not refresh inbox preview for $uid/$chatId",
                        error
                    )
                }

                conversationCache.computeIfPresent(uid) { _, conversations ->
                    conversations.map { conversation ->
                        if (conversation.chatId == chatId) {
                            conversation.copy(
                                lastMessage = previewText,
                                lastTimestamp = previewTimestamp,
                                lastSenderId = previewSenderId
                            )
                        } else {
                            conversation
                        }
                    }
                }
            }
        } catch (error: Exception) {
            // The message edit/delete already succeeded. A preview refresh
            // failure must not make the UI report the message operation failed.
            Log.w(
                TAG,
                "Could not refresh direct conversation preview for $chatId",
                error
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
            // Direct-chat preview metadata is owned by this user. Group preview
            // fields are maintained by the group sync path, so only unreadCount
            // is updated there.
            try {
                val chatSnapshot = database.getReference("chats")
                    .child(chatId)
                    .get()
                    .await()
                if (chatSnapshot.child("type").getValue(String::class.java) != "group") {
                    database.getReference("userChats")
                        .child(ownerUserId)
                        .child(chatId)
                        .updateChildren(
                            mapOf(
                                "lastMessage" to "No messages yet",
                                "lastTimestamp" to clearedAt,
                                "lastSenderId" to ""
                            )
                        )
                        .await()
                }
            } catch (previewError: Exception) {
                Log.w(TAG, "Chat history cleared, but the inbox preview could not be refreshed.", previewError)
            }

            // Clearing a conversation also resets this user's unread badge.
            database.getReference("userChats")
                .child(ownerUserId)
                .child(chatId)
                .child("unreadCount")
                .setValue(0L)
                .await()

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
     * Save an outgoing message before handing it to Firebase. Firebase's
     * persistent write queue remains responsible for delivery; Room keeps
     * the message visible and its pending indicator across process death.
     */
    suspend fun cacheOutgoingMessage(chatId: String, message: Message) {
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        require(ownerUserId.isNotBlank()) { "You are not authenticated." }
        require(chatId.isNotBlank() && message.id.isNotBlank()) { "Invalid outgoing message." }

        localMessageDao.upsertMessage(
            message.toCachedEntity(
                ownerUserId = ownerUserId,
                chatId = chatId,
                isPending = true
            )
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
    suspend fun getRemoteMessage(chatId: String, messageId: String): Message? {
        require(chatId.isNotBlank() && messageId.isNotBlank()) {
            "Invalid message lookup."
        }
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        require(ownerUserId.isNotBlank()) { "You are not authenticated." }

        val snapshot = database.getReference("chats")
            .child(chatId)
            .child("messages")
            .child(messageId)
            .get()
            .await()

        if (!snapshot.exists()) return null

        return snapshot.getValue(Message::class.java)
            ?: throw IllegalStateException("Remote message record could not be read.")
    }

    /**
     * A Firebase write task does not survive ViewModel recreation, while
     * Realtime Database's disk-persisted write queue does. Once connected,
     * read each pending ID from Firebase and clear its local pending marker
     * only when the remote message is present and belongs to this account.
     */
    suspend fun reconcilePendingOutgoingMessages(
        chatId: String,
        receiverId: String,
        receiverNickname: String
    ) {
        val ownerUserId = auth.currentUser?.uid.orEmpty()
        if (
            ownerUserId.isBlank() ||
            chatId.isBlank() ||
            receiverId.isBlank() ||
            receiverNickname.isBlank()
        ) return

        val pending = localMessageDao.getPendingMessages(ownerUserId, chatId)
        for (localMessage in pending) {
            try {
                val messageRef = database.getReference("chats")
                    .child(chatId)
                    .child("messages")
                    .child(localMessage.messageId)
                val snapshot = messageRef.get().await()
                val remoteMessage = snapshot.getValue(Message::class.java)

                when {
                    remoteMessage != null &&
                        remoteMessage.id == localMessage.messageId &&
                        remoteMessage.senderId == ownerUserId -> {
                        localMessageDao.markMessageSent(
                            ownerUserId = ownerUserId,
                            chatId = chatId,
                            messageId = localMessage.messageId
                        )
                    }

                    !snapshot.exists() -> {
                        // The process may have stopped after Room saved the
                        // message but before Firebase received the write.
                        // Reuse the same ID so this retry cannot create a duplicate.
                        val localMessageModel = localMessage.toMessage()
                        val retryResult = sendMessage(
                            chatId = chatId,
                            messageId = localMessage.messageId,
                            text = localMessageModel.text,
                            senderId = ownerUserId,
                            senderNickname = localMessageModel.senderNickname,
                            receiverId = receiverId,
                            receiverNickname = receiverNickname,
                            replyTo = localMessageModel.replyTo,
                            attachment = localMessageModel.attachment
                        )
                        retryResult.onSuccess {
                            localMessageDao.markMessageSent(
                                ownerUserId = ownerUserId,
                                chatId = chatId,
                                messageId = localMessage.messageId
                            )
                        }.onFailure { error ->
                            Log.w(TAG, "Pending message retry was not accepted: ${localMessage.messageId}", error)
                        }
                    }

                    else -> {
                        Log.w(
                            TAG,
                            "Pending message ID exists remotely with unexpected sender/data: ${localMessage.messageId}"
                        )
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not reconcile pending message ${localMessage.messageId}", error)
            }
        }
    }

    /*
     * =========================================================
     * MESSAGE LISTENER
     * =========================================================
     */
    fun getMessagesFlow(chatId: String): Flow<List<Message>> =
        combine(
            getRawMessagesFlow(chatId),
            observeMessageVisibility(chatId)
        ) { messages, visibility ->
            messages.filter(visibility::allows)
        }

    private fun getRawMessagesFlow(chatId: String): Flow<List<Message>> =
        callbackFlow {
            val producerScope = this
            val ownerUserId = auth.currentUser?.uid.orEmpty()
            if (ownerUserId.isBlank() || chatId.isBlank()) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }

            val key = cacheKey(chatId, ownerUserId)

            // Room is the first source for the UI. It survives process death.
            val localMessagesJob = launch(Dispatchers.IO) {
                localMessageDao.observeMessages(ownerUserId, chatId).collect { entities ->
                    val localMessages = entities.map { it.toMessage() }
                    messageCache[key] = localMessages
                    trySend(localMessages)
                }
            }

            // Keep the in-process copy as an additional fast path.
            messageCache[key]?.let { trySend(it) }

            val messagesRef = database.getReference("chats")
                .child(chatId)
                .child("messages")
                .orderByChild("timestamp")
                .limitToLast(200)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val latestMessages = snapshot.children.mapNotNull { child ->
                        child.getValue(Message::class.java)
                            ?.takeIf { it.id.isNotBlank() }
                    }.sortedBy { it.timestamp }

                    val immutableLatestMessages = latestMessages.toList()
                    latestMessageCache[key] = immutableLatestMessages

                    val cachedOlderMessages = if (immutableLatestMessages.isEmpty()) {
                        olderMessageCache.remove(key)
                        emptyList()
                    } else {
                        val firstLatestTimestamp = immutableLatestMessages.first().timestamp
                        olderMessageCache[key].orEmpty()
                            .filter { it.timestamp < firstLatestTimestamp }
                    }

                    val combinedMessages = (cachedOlderMessages + immutableLatestMessages)
                        .distinctBy { it.id }
                        .sortedBy { it.timestamp }

                    messageCache[key] = combinedMessages
                    trySend(combinedMessages)

                    // Replace only the range represented by Firebase's latest-200 query.
                    // Previously loaded older pages remain in Room.
                    producerScope.launch(Dispatchers.IO) {
                        try {
                            localMessageDao.replaceLatestSnapshot(
                                ownerUserId = ownerUserId,
                                chatId = chatId,
                                messages = immutableLatestMessages.map {
                                    it.toCachedEntity(ownerUserId, chatId)
                                }
                            )
                        } catch (error: Exception) {
                            Log.e(TAG, "Could not cache Firebase messages for $chatId", error)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Keep Room's saved history visible if Firebase cannot refresh it.
                    Log.e(
                        TAG,
                        "Realtime Database list listener cancelled for $chatId: ${error.message}"
                    )
                }
            }

            messagesRef.addValueEventListener(listener)

            awaitClose {
                localMessagesJob.cancel()
                messagesRef.removeEventListener(listener)
            }
        }

    suspend fun getOlderMessages(
        chatId: String,
        beforeTimestamp: Long,
        pageSize: Int = OLDER_MESSAGE_PAGE_SIZE
    ): Result<List<Message>> {
        if (chatId.isBlank() || beforeTimestamp <= 0L || pageSize !in 1..100) {
            return Result.failure(IllegalArgumentException("Invalid message history request."))
        }

        return try {
            val snapshot = database.getReference("chats").child(chatId)
                .child("messages").orderByChild("timestamp")
                .endAt((beforeTimestamp - 1L).toDouble())
                .limitToLast(pageSize).get().await()

            val rawPage = snapshot.children
                .mapNotNull { it.getValue(Message::class.java) }
                .filter { it.id.isNotBlank() }
                .sortedBy { it.timestamp }

            val ownerUserId = auth.currentUser?.uid.orEmpty()
            if (ownerUserId.isBlank()) {
                return Result.failure(IllegalStateException("You are not authenticated."))
            }
            val visibility = readMessageVisibility(chatId, ownerUserId)
            val page = rawPage.filter(visibility::allows)

            if (rawPage.isNotEmpty()) {
                val key = cacheKey(chatId, ownerUserId)
                olderMessageCache.compute(key) { _, cached ->
                    (cached.orEmpty() + rawPage).distinctBy { it.id }.sortedBy { it.timestamp }
                }
                messageCache.compute(key) { _, cached ->
                    (cached.orEmpty() + rawPage).distinctBy { it.id }.sortedBy { it.timestamp }
                }
                localMessageDao.upsertMessages(
                    rawPage.map { it.toCachedEntity(ownerUserId, chatId) }
                )
            }

            Result.success(page)
        } catch (error: Exception) {
            Log.e(TAG, "Could not load older messages for $chatId", error)
            Result.failure(error)
        }
    }

    /*
     * A direct entry in the signed-in user's userChats index is written
     * by the chat message flow only after the direct chat record exists.
     * Cache that confirmation so opening a conversation from Home does
     * not need another Firebase existence read before attaching listeners.
     *
     * Validate the deterministic chat ID against both participants before
     * trusting the index. New conversations opened from Search still go
     * through ensureChat() and its normal create/verify path.
     */
    private fun rememberIndexedDirectChats(
        currentUserId: String,
        conversations: List<Conversation>
    ) {
        if (currentUserId.isBlank()) return

        conversations.forEach { conversation ->
            if (
                conversation.chatType != "group" &&
                conversation.otherUserId.isNotBlank() &&
                conversation.chatId == getChatRoomId(
                    currentUserId,
                    conversation.otherUserId
                )
            ) {
                knownChatIds.add(conversation.chatId)
            }
        }
    }

    /**
     * Warm the local message cache for the most recently active chats while
     * the user is on Home, so opening one is not a cold Firebase fetch.
     * Only the latest 30 messages from up to 8 chats are prefetched.
     */
    suspend fun preloadRecentMessages(
        ownerUserId: String,
        conversations: List<Conversation>,
        maxChats: Int = 8
    ) {
        if (
            ownerUserId.isBlank() ||
            auth.currentUser?.uid != ownerUserId ||
            maxChats !in 1..12
        ) return

        for (conversation in conversations
            .sortedByDescending { it.lastTimestamp }
            .take(maxChats)
        ) {
            val chatId = conversation.chatId
            if (chatId.isBlank()) continue

            val key = cacheKey(chatId, ownerUserId)
            if (!prefetchedChatIds.add(key)) continue

            try {
                // If this chat is already cached locally, there is nothing to warm.
                if (localMessageDao.hasAnyMessages(ownerUserId, chatId)) {
                    continue
                }

                val snapshot = database.getReference("chats")
                    .child(chatId)
                    .child("messages")
                    .orderByChild("timestamp")
                    .limitToLast(30)
                    .get()
                    .await()

                val latestMessages = snapshot.children.mapNotNull { child ->
                    child.getValue(Message::class.java)
                        ?.takeIf { it.id.isNotBlank() }
                }

                if (latestMessages.isNotEmpty()) {
                    localMessageDao.upsertMessages(
                        latestMessages.map { it.toCachedEntity(ownerUserId, chatId) }
                    )
                }
            } catch (error: Exception) {
                prefetchedChatIds.remove(key)
                Log.d(TAG, "Background message warm-up skipped for $chatId: ${error.message}")
            }
        }
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
                    rememberIndexedDirectChats(
                        currentUserId = currentUserId,
                        conversations = cachedConversations
                    )
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

                        rememberIndexedDirectChats(
                            currentUserId = currentUserId,
                            conversations = immutableConversations
                        )

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
    fun generateMessageId(
        chatId: String
    ): String? {

        return database
            .getReference(
                "chats"
            )
            .child(
                chatId
            )
            .child(
                "messages"
            )
            .push()
            .key
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
                database
                    .getReference(
                        "chats"
                    )
                    .child(
                        chatId
                    )
                    .child(
                        "readBy"
                    )
                    .child(
                        otherUserId
                    )


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
            database.getReference("chats").child(chatId)
                .child("readBy").child(currentUserId).removeValue().await()
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
                            0L
                )

            updates[
                "chats/$chatId/readBy/$currentUserId"
            ] =
                if (
                    sendReadReceipt
                ) {
                    ServerValue.TIMESTAMP
                } else {
                    null
                }

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
        onQueued: () -> Unit = {}
    ): Result<String> {

        return try {
            if (
                chatId.isBlank() ||
                messageId.isBlank() ||
                text.isBlank() ||
                senderId.isBlank() ||
                receiverId.isBlank() ||
                senderId == receiverId
            ) {
                return Result.failure(IllegalArgumentException("Invalid message data"))
            }

            val firebaseUser = auth.currentUser
            if (firebaseUser == null || firebaseUser.uid != senderId) {
                return Result.failure(
                    IllegalStateException("Authenticated user does not match message sender.")
                )
            }

            if (
                replyTo != null &&
                (
                    replyTo.messageId.isBlank() ||
                    replyTo.senderId.isBlank() ||
                    replyTo.senderNickname.isBlank() ||
                    replyTo.text.isBlank()
                )
            ) {
                return Result.failure(IllegalArgumentException("Invalid reply data"))
            }

            val messageData = mutableMapOf<String, Any>(
                "id" to messageId,
                "senderId" to senderId,
                "senderNickname" to senderNickname,
                "text" to text,
                "timestamp" to ServerValue.TIMESTAMP
            )

            if (attachment != null) {
                messageData["attachment"] = mapOf(
                    "type" to attachment.type,
                    "storageKey" to attachment.storageKey,
                    "fileName" to attachment.fileName,
                    "mimeType" to attachment.mimeType,
                    "sizeBytes" to attachment.sizeBytes
                )
            }

            if (replyTo != null) {
                messageData["replyTo"] = mapOf(
                    "messageId" to replyTo.messageId,
                    "senderId" to replyTo.senderId,
                    "senderNickname" to replyTo.senderNickname,
                    "text" to replyTo.text
                )
            }

            /*
             * Persist the message first. Conversation previews are secondary
             * metadata; they must not be part of the same atomic update because
             * a denial on either user's userChats node would reject the actual
             * message as well.
             */
            val messageWrite = database.getReference("chats")
                .child(chatId)
                .child("messages")
                .child(messageId)
                .setValue(messageData)

            // Realtime Database publishes the write to local listeners
            // immediately. The composer can clear before server acknowledgement.
            onQueued()
            messageWrite.await()

            CloudflareNotificationRepository()
                .notifyMessage(chatId, messageId)
                .onFailure { notificationError ->
                    Log.w(
                        TAG,
                        "Message was saved, but Cloudflare notification dispatch failed.",
                        notificationError
                    )
                }

            val senderConversation = mapOf(
                "chatId" to chatId,
                "otherUserId" to receiverId,
                "otherUsername" to receiverNickname,
                "lastMessage" to text,
                "lastTimestamp" to ServerValue.TIMESTAMP,
                "lastSenderId" to senderId,
                "unreadCount" to 0L
            )

            val receiverConversation = mapOf(
                "chatId" to chatId,
                "otherUserId" to senderId,
                "otherUsername" to senderNickname,
                "lastMessage" to text,
                "lastTimestamp" to ServerValue.TIMESTAMP,
                "lastSenderId" to senderId,
                "unreadCount" to ServerValue.increment(1)
            )

            // Conversation previews are secondary metadata. Queue them
            // without making the composer wait for two more server round trips.
            database.getReference("userChats")
                .child(senderId)
                .child(chatId)
                .setValue(senderConversation)
                .addOnFailureListener { metadataError ->
                    Log.w(TAG, "Message sent, but sender conversation preview was not updated", metadataError)
                }

            database.getReference("userChats")
                .child(receiverId)
                .child(chatId)
                .setValue(receiverConversation)
                .addOnFailureListener { metadataError ->
                    Log.w(TAG, "Message sent, but receiver conversation preview was not updated", metadataError)
                }

            Result.success(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Message write failed for chat $chatId", e)
            Result.failure(e)
        }
    }

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
    suspend fun editMessage(
        chatId: String,
        messageId: String,
        userId: String,
        newText: String
    ): Result<Unit> {

        return try {

            val cleanText =
                newText.trim()

            if (
                chatId.isBlank() ||
                messageId.isBlank() ||
                userId.isBlank() ||
                cleanText.isBlank()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid edit data"
                    )
                )
            }

            if (
                cleanText.length > 5000
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Message is too long"
                    )
                )
            }

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != userId
            ) {

                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match message owner."
                    )
                )
            }

            val messageRef =
                database
                    .getReference(
                        "chats"
                    )
                    .child(
                        chatId
                    )
                    .child(
                        "messages"
                    )
                    .child(
                        messageId
                    )

            /*
             * Read the current message first.
             */
            val snapshot =
                messageRef
                    .get()
                    .await()

            if (
                !snapshot.exists()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Message does not exist."
                    )
                )
            }

            val senderId =
                snapshot
                    .child(
                        "senderId"
                    )
                    .getValue(
                        String::class.java
                    )

            if (senderId != userId) {
                return Result.failure(
                    IllegalStateException("You can only edit your own messages.")
                )
            }

            val originalTimestamp =
                snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
            val editAge = FirebaseServerClock.now() - originalTimestamp
            if (originalTimestamp <= 0L || editAge < 0L ||
                editAge > MESSAGE_EDIT_WINDOW_MS
            ) {
                return Result.failure(
                    IllegalStateException("Messages can only be edited within 15 minutes of sending.")
                )
            }

            messageRef
                .updateChildren(
                    mapOf<String, Any>(
                        "text" to cleanText,
                        "edited" to true
                    )
                )
                .await()

            updateCachedMessage(chatId) { cached ->
                if (cached.id == messageId) cached.copy(text = cleanText, edited = true)
                else cached
            }
            localMessageDao.updateMessageText(
                ownerUserId = userId,
                chatId = chatId,
                messageId = messageId,
                text = cleanText
            )
            refreshDirectConversationPreview(chatId)

            Result.success(
                Unit
            )

        } catch (e: Exception) {

            Result.failure(
                e
            )
        }
    }

    private suspend fun persistReaction(
        chatId: String,
        messageId: String,
        userId: String,
        reaction: String?
    ) {
        val cached = localMessageDao.getMessage(userId, chatId, messageId) ?: return
        val updatedReactions = cached.toMessage().reactions.toMutableMap().apply {
            if (reaction == null) remove(userId) else put(userId, reaction)
        }
        localMessageDao.upsertMessage(
            cached.toMessage().copy(reactions = updatedReactions)
                .toCachedEntity(userId, chatId)
        )
        updateCachedMessage(chatId) { message ->
            message.copy(reactions = updatedReactions)
        }
    }

    /*
     * =========================================================
     * SET REACTION
     * =========================================================
     */
    suspend fun setReaction(
        chatId: String,
        messageId: String,
        userId: String,
        reaction: String
    ): Result<Unit> {

        return try {

            if (
                chatId.isBlank() ||
                messageId.isBlank() ||
                userId.isBlank() ||
                reaction.isBlank()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid reaction data"
                    )
                )
            }

            if (
                reaction !in
                SUPPORTED_REACTIONS
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Unsupported reaction"
                    )
                )
            }

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != userId
            ) {

                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match reaction user."
                    )
                )
            }

            val messageRef =
                database
                    .getReference(
                        "chats"
                    )
                    .child(
                        chatId
                    )
                    .child(
                        "messages"
                    )
                    .child(
                        messageId
                    )

            val messageSnapshot =
                messageRef
                    .get()
                    .await()

            if (
                !messageSnapshot.exists()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Message does not exist."
                    )
                )
            }

            messageRef
                .child(
                    "reactions"
                )
                .child(
                    userId
                )
                .setValue(
                    reaction
                )
                .await()

            persistReaction(chatId, messageId, userId, reaction)
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
     * REMOVE REACTION
     * =========================================================
     */
    suspend fun removeReaction(
        chatId: String,
        messageId: String,
        userId: String
    ): Result<Unit> {

        return try {

            if (
                chatId.isBlank() ||
                messageId.isBlank() ||
                userId.isBlank()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid reaction data"
                    )
                )
            }

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != userId
            ) {

                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match reaction user."
                    )
                )
            }

            database
                .getReference(
                    "chats"
                )
                .child(
                    chatId
                )
                .child(
                    "messages"
                )
                .child(
                    messageId
                )
                .child(
                    "reactions"
                )
                .child(
                    userId
                )
                .removeValue()
                .await()

            persistReaction(chatId, messageId, userId, null)
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
     * TOGGLE REACTION
     * =========================================================
     */
    suspend fun toggleReaction(
        chatId: String,
        messageId: String,
        userId: String,
        reaction: String
    ): Result<Boolean> {

        return try {

            if (
                chatId.isBlank() ||
                messageId.isBlank() ||
                userId.isBlank() ||
                reaction.isBlank()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid reaction data"
                    )
                )
            }

            if (
                reaction !in
                SUPPORTED_REACTIONS
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Unsupported reaction"
                    )
                )
            }

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != userId
            ) {

                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match reaction user."
                    )
                )
            }

            val messageRef =
                database
                    .getReference(
                        "chats"
                    )
                    .child(
                        chatId
                    )
                    .child(
                        "messages"
                    )
                    .child(
                        messageId
                    )

            val messageSnapshot =
                messageRef
                    .get()
                    .await()

            if (
                !messageSnapshot.exists()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Message does not exist."
                    )
                )
            }

            val reactionRef =
                messageRef
                    .child(
                        "reactions"
                    )
                    .child(
                        userId
                    )

            val currentSnapshot =
                reactionRef
                    .get()
                    .await()

            val currentReaction =
                currentSnapshot
                    .getValue(
                        String::class.java
                    )

            if (
                currentReaction ==
                reaction
            ) {

                reactionRef
                    .removeValue()
                    .await()
                persistReaction(chatId, messageId, userId, null)

                return Result.success(
                    false
                )
            }

            reactionRef
                .setValue(
                    reaction
                )
                .await()
            persistReaction(chatId, messageId, userId, reaction)

            Result.success(
                true
            )

        } catch (e: Exception) {

            Result.failure(
                e
            )
        }
    }

    /*
     * =========================================================
     * DELETE MESSAGE
     * =========================================================
     */
    suspend fun deleteMessage(
        chatId: String,
        messageId: String
    ): Result<Unit> {

        return try {

            if (
                chatId.isBlank() ||
                messageId.isBlank()
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid message data"
                    )
                )
            }

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null
            ) {
                return Result.failure(
                    IllegalStateException(
                        "You are not authenticated."
                    )
                )
            }

            val messageRef =
                database
                    .getReference(
                        "chats"
                    )
                    .child(
                        chatId
                    )
                    .child(
                        "messages"
                    )
                    .child(
                        messageId
                    )

            val messageSnapshot =
                messageRef
                    .get()
                    .await()

            if (
                !messageSnapshot.exists()
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Message does not exist."
                    )
                )
            }

            if (
                messageSnapshot
                    .child(
                        "senderId"
                    )
                    .getValue(
                        String::class.java
                    ) != firebaseUser.uid
            ) {
                return Result.failure(
                    IllegalStateException(
                        "You can only delete your own messages."
                    )
                )
            }

            messageRef.removeValue().await()

            updateCachedMessage(chatId) { cached ->
                if (cached.id == messageId) null else cached
            }
            localMessageDao.deleteMessage(
                ownerUserId = firebaseUser.uid,
                chatId = chatId,
                messageId = messageId
            )
            refreshDirectConversationPreview(chatId)

            Result.success(Unit)

        } catch (e: Exception) {

            Result.failure(
                e
            )
        }
    }
}