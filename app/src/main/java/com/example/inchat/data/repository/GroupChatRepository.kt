package com.example.inchat.data.repository

import com.example.inchat.data.model.Conversation
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

class GroupChatRepository {

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

    suspend fun createGroup(
        currentUserId: String,
        groupName: String,
        memberIds: List<String>
    ): Result<String> {

        return try {

            val firebaseUser = auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match group creator."
                    )
                )
            }

            val cleanName = groupName.trim()

            if (cleanName.isBlank() || cleanName.length > 50) {
                return Result.failure(
                    IllegalArgumentException(
                        "Group name must be between 1 and 50 characters."
                    )
                )
            }

            val uniqueMembers =
                (listOf(currentUserId) + memberIds)
                    .filter { it.isNotBlank() }
                    .distinct()

            if (uniqueMembers.size < 2) {
                return Result.failure(
                    IllegalArgumentException(
                        "Select at least one other person."
                    )
                )
            }

            if (uniqueMembers.size > 50) {
                return Result.failure(
                    IllegalArgumentException(
                        "A group can contain at most 50 members."
                    )
                )
            }

            val groupId =
                database
                    .getReference("chats")
                    .push()
                    .key
                    ?: return Result.failure(
                        IllegalStateException(
                            "Could not create group ID."
                        )
                    )

            val members =
                uniqueMembers.associateWith { uid ->
                    if (uid == currentUserId) "admin" else "member"
                }

            val groupData =
                mapOf<String, Any>(
                    "chatId" to groupId,
                    "type" to "group",
                    "name" to cleanName,
                    "createdBy" to currentUserId,
                    "createdAt" to ServerValue.TIMESTAMP,
                    "members" to members
                )

            database
                .getReference("chats")
                .child(groupId)
                .setValue(groupData)
                .await()

            val membershipUpdates =
                uniqueMembers.associate { uid ->
                    "groupMemberships/$uid/$groupId" to true
                }

            database.reference
                .updateChildren(membershipUpdates)
                .await()

            Result.success(groupId)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroup(groupId: String): Group? {

        if (groupId.isBlank()) {
            return null
        }

        return parseGroup(
            database
                .getReference("chats")
                .child(groupId)
                .get()
                .await()
        )
    }

    fun observeMyGroupIds(currentUserId: String): Flow<List<String>> =
        callbackFlow {

            if (
                currentUserId.isBlank() ||
                auth.currentUser?.uid != currentUserId
            ) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }

            val ref =
                database
                    .getReference("groupMemberships")
                    .child(currentUserId)

            val listener =
                object : com.google.firebase.database.ValueEventListener {

                    override fun onDataChange(snapshot: DataSnapshot) {
                        trySend(
                            snapshot.children
                                .mapNotNull { it.key }
                                .filter { it.isNotBlank() }
                                .distinct()
                        )
                    }

                    override fun onCancelled(
                        error: com.google.firebase.database.DatabaseError
                    ) {
                        close(error.toException())
                    }
                }

            ref.addValueEventListener(listener)

            awaitClose {
                ref.removeEventListener(listener)
            }
        }

    fun observeGroupConversations(
        currentUserId: String
    ): Flow<List<Conversation>> =
        observeMyGroupIds(currentUserId)
            .flatMapLatest { groupIds ->
                if (groupIds.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        groupIds.map {
                            observeGroupConversation(it)
                        }
                    ) { summaries ->
                        summaries
                            .filterNotNull()
                            .sortedByDescending {
                                it.lastTimestamp
                            }
                    }
                }
            }

    suspend fun removeMyGroupMembership(
        currentUserId: String,
        groupId: String
    ): Result<Unit> {

        return try {

            val firebaseUser = auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match group member."
                    )
                )
            }

            if (
                currentUserId.isBlank() ||
                groupId.isBlank()
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid group membership."
                    )
                )
            }

            database
                .getReference("groupMemberships")
                .child(currentUserId)
                .child(groupId)
                .removeValue()
                .await()

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun observeGroupConversation(
        groupId: String
    ): Flow<Conversation?> =
        combine(
            observeGroup(groupId),
            observeLatestMessage(groupId)
        ) { group, latestMessage ->

            if (group == null) {
                null
            } else {
                Conversation(
                    chatId = group.chatId,
                    chatType = "group",
                    groupName = group.name,
                    groupPhotoUrl = "",
                    memberCount = group.members.size.toLong(),
                    lastMessage = latestMessage?.text.orEmpty(),
                    lastTimestamp =
                        latestMessage?.timestamp
                            ?: group.createdAt,
                    lastSenderId =
                        latestMessage?.senderId.orEmpty(),
                    lastSenderNickname =
                        latestMessage?.senderNickname.orEmpty(),
                    unreadCount = 0L
                )
            }
        }

    private fun observeLatestMessage(
        groupId: String
    ): Flow<Message?> =
        callbackFlow {

            val messagesRef =
                database
                    .getReference("chats")
                    .child(groupId)
                    .child("messages")
                    .orderByChild("timestamp")
                    .limitToLast(1)

            val listener =
                object : com.google.firebase.database.ValueEventListener {

                    override fun onDataChange(snapshot: DataSnapshot) {
                        val latest =
                            snapshot.children
                                .mapNotNull {
                                    it.getValue(Message::class.java)
                                }
                                .maxByOrNull {
                                    it.timestamp
                                }

                        trySend(latest)
                    }

                    override fun onCancelled(
                        error: com.google.firebase.database.DatabaseError
                    ) {
                        close(error.toException())
                    }
                }

            messagesRef.addValueEventListener(listener)

            awaitClose {
                messagesRef.removeEventListener(listener)
            }
        }

    fun observeGroup(groupId: String): Flow<Group?> =
        callbackFlow {

            val ref =
                database
                    .getReference("chats")
                    .child(groupId)

            val listener =
                object : com.google.firebase.database.ValueEventListener {

                    override fun onDataChange(snapshot: DataSnapshot) {
                        trySend(parseGroup(snapshot))
                    }

                    override fun onCancelled(
                        error: com.google.firebase.database.DatabaseError
                    ) {
                        close(error.toException())
                    }
                }

            ref.addValueEventListener(listener)

            awaitClose {
                ref.removeEventListener(listener)
            }
        }

    suspend fun sendMessage(
        groupId: String,
        messageId: String,
        text: String,
        senderId: String,
        senderNickname: String,
        replyTo: ReplyTo? = null
    ): Result<String> {

        return try {

            val firebaseUser = auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != senderId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match message sender."
                    )
                )
            }

            val cleanText = text.trim()

            if (
                groupId.isBlank() ||
                messageId.isBlank() ||
                senderId.isBlank() ||
                senderNickname.isBlank() ||
                cleanText.isBlank()
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid group message data."
                    )
                )
            }

            if (cleanText.length > 5000) {
                return Result.failure(
                    IllegalArgumentException(
                        "Message is too long."
                    )
                )
            }

            val group =
                parseGroup(
                    database
                        .getReference("chats")
                        .child(groupId)
                        .get()
                        .await()
                )

            if (
                group == null ||
                senderId !in group.members.keys
            ) {
                return Result.failure(
                    IllegalStateException(
                        "You are not a member of this group."
                    )
                )
            }

            val messageData =
                mutableMapOf<String, Any>(
                    "id" to messageId,
                    "senderId" to senderId,
                    "senderNickname" to senderNickname,
                    "text" to cleanText,
                    "timestamp" to ServerValue.TIMESTAMP
                )

            replyTo?.let { reply ->
                messageData["replyTo"] =
                    mapOf(
                        "messageId" to reply.messageId,
                        "senderId" to reply.senderId,
                        "senderNickname" to reply.senderNickname,
                        "text" to reply.text
                    )
            }

            database
                .getReference("chats")
                .child(groupId)
                .child("messages")
                .child(messageId)
                .setValue(messageData)
                .await()

            Result.success(messageId)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseGroup(snapshot: DataSnapshot): Group? {

        if (
            !snapshot.exists() ||
            snapshot.child("type")
                .getValue(String::class.java) != "group"
        ) {
            return null
        }

        val members =
            snapshot
                .child("members")
                .children
                .mapNotNull { child ->
                    val uid = child.key
                    val role =
                        child.getValue(String::class.java)

                    if (uid.isNullOrBlank() || role.isNullOrBlank()) {
                        null
                    } else {
                        uid to role
                    }
                }
                .toMap()

        return Group(
            chatId =
                snapshot
                    .child("chatId")
                    .getValue(String::class.java)
                    .orEmpty()
                    .ifBlank { snapshot.key.orEmpty() },
            type = "group",
            name =
                snapshot
                    .child("name")
                    .getValue(String::class.java)
                    .orEmpty(),
            createdBy =
                snapshot
                    .child("createdBy")
                    .getValue(String::class.java)
                    .orEmpty(),
            createdAt =
                snapshot
                    .child("createdAt")
                    .getValue(Long::class.java)
                    ?: 0L,
            members = members
        )
    }
}