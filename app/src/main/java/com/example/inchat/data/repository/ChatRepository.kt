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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class ChatRepository {

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

    companion object {

        private const val TAG =
            "ChatRepository"

        private const val NOTIFICATION_WORKER_URL =
            "https://icy-base-2bc4.s91670002.workers.dev/send"

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

            if (
                currentUserId.isBlank() ||
                otherUserId.isBlank() ||
                currentUserId ==
                otherUserId
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid chat participants"
                    )
                )
            }

            val chatId =
                getChatRoomId(
                    currentUserId,
                    otherUserId
                )

            val participantA =
                if (
                    currentUserId < otherUserId
                ) {

                    currentUserId

                } else {

                    otherUserId
                }

            val participantB =
                if (
                    currentUserId < otherUserId
                ) {

                    otherUserId

                } else {

                    currentUserId
                }

            val updates =
                mapOf(

                    "chats/$chatId/participantA" to
                            participantA,

                    "chats/$chatId/participantB" to
                            participantB
                )

            database.reference
                .updateChildren(
                    updates
                )
                .await()

            Result.success(
                chatId
            )

        } catch (e: Exception) {

            Result.failure(
                e
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

            messagesRef.keepSynced(
                true
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

                        val immutableMessages =
                            messageList.toList()

                        messageCache[chatId] =
                            immutableMessages

                        trySend(
                            immutableMessages
                        )
                    }

                    override fun onCancelled(
                        error:
                        DatabaseError
                    ) {

                        close(
                            error.toException()
                        )
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

            conversationsRef.keepSynced(
                true
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
                                conversation.otherUserId.isNotBlank() &&
                                conversation.otherUsername.isNotBlank()
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

                        close(
                            error.toException()
                        )
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

            readRef.keepSynced(
                true
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

                        close(
                            error.toException()
                        )
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

    /*
     * =========================================================
     * MARK CONVERSATION READ
     * =========================================================
     */
    suspend fun markConversationRead(
        currentUserId: String,
        chatId: String
    ): Result<Unit> {

        return try {

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
                mapOf<String, Any>(

                    "userChats/$currentUserId/$chatId/unreadCount" to
                            0L,

                    "chats/$chatId/readBy/$currentUserId" to
                            ServerValue.TIMESTAMP
                )

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
        replyTo: ReplyTo? = null
    ): Result<String> {

        return try {

            if (
                chatId.isBlank() ||
                messageId.isBlank() ||
                text.isBlank() ||
                senderId.isBlank() ||
                receiverId.isBlank() ||
                senderId ==
                receiverId
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid message data"
                    )
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

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid reply data"
                    )
                )
            }

            val timestamp =
                ServerValue.TIMESTAMP

            val messageData =
                mutableMapOf<String, Any>(

                    "id" to
                            messageId,

                    "senderId" to
                            senderId,

                    "senderNickname" to
                            senderNickname,

                    "text" to
                            text,

                    "timestamp" to
                            timestamp
                )

            if (
                replyTo != null
            ) {

                messageData[
                    "replyTo"
                ] =
                    mapOf(

                        "messageId" to
                                replyTo.messageId,

                        "senderId" to
                                replyTo.senderId,

                        "senderNickname" to
                                replyTo.senderNickname,

                        "text" to
                                replyTo.text
                    )
            }

            val senderConversation =
                mapOf(

                    "chatId" to
                            chatId,

                    "otherUserId" to
                            receiverId,

                    "otherUsername" to
                            receiverNickname,

                    "lastMessage" to
                            text,

                    "lastTimestamp" to
                            timestamp,

                    "lastSenderId" to
                            senderId,

                    "unreadCount" to
                            0L
                )

            val receiverConversation =
                mapOf(

                    "chatId" to
                            chatId,

                    "otherUserId" to
                            senderId,

                    "otherUsername" to
                            senderNickname,

                    "lastMessage" to
                            text,

                    "lastTimestamp" to
                            timestamp,

                    "lastSenderId" to
                            senderId,

                    "unreadCount" to
                            ServerValue.increment(
                                1
                            )
                )

            val updates =
                mapOf(

                    "chats/$chatId/messages/$messageId" to
                            messageData,

                    "userChats/$senderId/$chatId" to
                            senderConversation,

                    "userChats/$receiverId/$chatId" to
                            receiverConversation
                )

            database.reference
                .updateChildren(
                    updates
                )
                .await()

            notifyWorker(
                chatId =
                    chatId,

                messageId =
                    messageId,

                senderId =
                    senderId,

                receiverId =
                    receiverId,

                senderName =
                    senderNickname,

                message =
                    text
            )

            Result.success(
                messageId
            )

        } catch (e: Exception) {

            Result.failure(
                e
            )
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

            if (
                senderId != userId
            ) {

                return Result.failure(
                    IllegalStateException(
                        "You can only edit your own messages."
                    )
                )
            }

            /*
             * Do not rewrite the whole message object.
             *
             * Updating only these two children preserves
             * replies, reactions and all other message data.
             */
            val updates =
                mapOf<String, Any>(

                    "text" to
                            cleanText,

                    "edited" to
                            true
                )

            messageRef
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
     * NOTIFICATION WORKER
     * =========================================================
     */
    private suspend fun notifyWorker(
        chatId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        senderName: String,
        message: String
    ) {

        try {

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null
            ) {

                Log.w(
                    TAG,
                    "Notification skipped: no authenticated user"
                )

                return
            }

            if (
                firebaseUser.uid !=
                senderId
            ) {

                Log.w(
                    TAG,
                    "Notification skipped: sender ID mismatch"
                )

                return
            }

            val tokenResult =
                firebaseUser
                    .getIdToken(
                        false
                    )
                    .await()

            val firebaseIdToken =
                tokenResult.token

            if (
                firebaseIdToken.isNullOrBlank()
            ) {

                Log.w(
                    TAG,
                    "Notification skipped: Firebase ID token unavailable"
                )

                return
            }

            val requestBody =
                JSONObject().apply {

                    put(
                        "chatId",
                        chatId
                    )

                    put(
                        "messageId",
                        messageId
                    )

                    put(
                        "senderId",
                        senderId
                    )

                    put(
                        "receiverId",
                        receiverId
                    )

                    put(
                        "senderName",
                        senderName
                    )

                    put(
                        "message",
                        message
                    )
                }.toString()

            val response =
                withContext(
                    Dispatchers.IO
                ) {

                    val connection =
                        URL(
                            NOTIFICATION_WORKER_URL
                        )
                            .openConnection()
                                as HttpURLConnection

                    try {

                        connection.requestMethod =
                            "POST"

                        connection.connectTimeout =
                            10_000

                        connection.readTimeout =
                            15_000

                        connection.doOutput =
                            true

                        connection.setRequestProperty(
                            "Authorization",
                            "Bearer $firebaseIdToken"
                        )

                        connection.setRequestProperty(
                            "Content-Type",
                            "application/json"
                        )

                        connection.setRequestProperty(
                            "Accept",
                            "application/json"
                        )

                        connection
                            .outputStream
                            .use { outputStream ->

                                outputStream.write(
                                    requestBody
                                        .toByteArray(
                                            Charsets.UTF_8
                                        )
                                )
                            }

                        val responseCode =
                            connection
                                .responseCode

                        val stream =
                            if (
                                responseCode in
                                200..299
                            ) {

                                connection
                                    .inputStream

                            } else {

                                connection
                                    .errorStream
                            }

                        val responseText =
                            stream
                                ?.bufferedReader()
                                ?.use {
                                    it.readText()
                                }
                                .orEmpty()

                        Log.d(
                            TAG,
                            "Notification Worker response: HTTP $responseCode"
                        )

                        responseText

                    } finally {

                        connection.disconnect()
                    }
                }

            if (
                response.isNotBlank()
            ) {

                Log.d(
                    TAG,
                    "Notification Worker result: $response"
                )
            }

        } catch (e: Exception) {

            /*
             * Notification failure never fails the message.
             */
            Log.e(
                TAG,
                "Notification Worker request failed",
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
}