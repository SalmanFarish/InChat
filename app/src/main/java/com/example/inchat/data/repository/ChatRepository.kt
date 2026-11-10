package com.example.inchat.data.repository

import android.util.Log
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ChatRepository {

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

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
         * Firebase Realtime Database disk persistence remains the
         * source of truth across app restarts.
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
    }

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
    fun getCachedMessages(chatId: String): List<Message>? =
        messageCache[chatId]

    fun clearOlderMessagePages(chatId: String) {
        if (chatId.isBlank()) return
        olderMessageCache.remove(chatId)
        latestMessageCache[chatId]?.let { latest ->
            messageCache[chatId] = latest
        }
    }

    private fun updateCachedMessage(
        chatId: String,
        transform: (Message) -> Message?
    ) {
        messageCache.computeIfPresent(chatId) { _, messages ->
            messages.mapNotNull(transform).sortedBy { it.timestamp }
        }
        olderMessageCache.computeIfPresent(chatId) { _, messages ->
            messages.mapNotNull(transform).sortedBy { it.timestamp }
        }
        latestMessageCache.computeIfPresent(chatId) { _, messages ->
            messages.mapNotNull(transform).sortedBy { it.timestamp }
        }
    }

    private suspend fun refreshDirectConversationPreview(chatId: String) {
        try {
            val chatSnapshot = database.getReference("chats")
                .child(chatId)
                .get()
                .await()

            if (
                !chatSnapshot.exists() ||
                chatSnapshot.child("type").getValue(String::class.java) == "group"
            ) {
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


    /*
     * =========================================================
     * MESSAGE LISTENER
     * =========================================================
     */
    fun getMessagesFlow(
        chatId: String
    ): Flow<List<Message>> =
        callbackFlow {

            val messagesRef =
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
                    .orderByChild(
                        "timestamp"
                    )
                    .limitToLast(
                        200
                    )


            /*
             * Reuse the last known in-memory snapshot immediately.
             * The live Firebase listener below remains authoritative
             * and will replace this snapshot as soon as fresh data
             * is available.
             */
            messageCache[chatId]
                ?.let { cachedMessages ->
                    trySend(
                        cachedMessages
                    )
                }

            val listener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot:
                        DataSnapshot
                    ) {

                        val messageList =
                            ArrayList<Message>(
                                snapshot.childrenCount
                                    .toInt()
                            )

                        for (
                        childSnapshot in
                        snapshot.children
                        ) {

                            val message =
                                childSnapshot
                                    .getValue(
                                        Message::class.java
                                    )

                            if (
                                message != null &&
                                message.id.isNotBlank()
                            ) {

                                messageList.add(
                                    message
                                )
                            }
                        }

                        messageList.sortBy {
                            it.timestamp
                        }

                        val immutableLatestMessages = messageList.toList()
                        latestMessageCache[chatId] = immutableLatestMessages

                        val cachedOlderMessages =
                            if (immutableLatestMessages.isEmpty()) {
                                olderMessageCache.remove(chatId)
                                emptyList()
                            } else {
                                val firstLatestTimestamp =
                                    immutableLatestMessages.first().timestamp
                                olderMessageCache[chatId].orEmpty()
                                    .filter { it.timestamp < firstLatestTimestamp }
                            }

                        val combinedMessages =
                            (cachedOlderMessages + immutableLatestMessages)
                                .distinctBy { it.id }
                                .sortedBy { it.timestamp }

                        messageCache[chatId] = combinedMessages
                        trySend(combinedMessages)
                    }

                    override fun onCancelled(
                        error:
                        DatabaseError
                    ) {
                        Log.e(
                            TAG,
                            "Realtime Database list listener cancelled for $chatId: ${error.message}"
                        )
                        trySend(emptyList())
                        close()
                    }
                }

            messagesRef
                .addValueEventListener(
                    listener
                )

            awaitClose {

                messagesRef
                    .removeEventListener(
                        listener
                    )
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

            val page = snapshot.children
                .mapNotNull { it.getValue(Message::class.java) }
                .filter { it.id.isNotBlank() }
                .sortedBy { it.timestamp }

            if (page.isNotEmpty()) {
                olderMessageCache.compute(chatId) { _, cached ->
                    (cached.orEmpty() + page).distinctBy { it.id }.sortedBy { it.timestamp }
                }
                messageCache.compute(chatId) { _, cached ->
                    (cached.orEmpty() + page).distinctBy { it.id }.sortedBy { it.timestamp }
                }
            }

            Result.success(page)
        } catch (error: Exception) {
            Log.e(TAG, "Could not load older messages for $chatId", error)
            Result.failure(error)
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

                return Result.success(
                    false
                )
            }

            reactionRef
                .setValue(
                    reaction
                )
                .await()

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
            refreshDirectConversationPreview(chatId)

            Result.success(Unit)

        } catch (e: Exception) {

            Result.failure(
                e
            )
        }
    }
}