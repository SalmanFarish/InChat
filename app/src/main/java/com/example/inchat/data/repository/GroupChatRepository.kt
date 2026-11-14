package com.example.inchat.data.repository

import android.util.Log

import com.example.inchat.InChatApplication
import com.example.inchat.data.worker.MessageNotificationWorker

import com.example.inchat.data.model.Conversation
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.tasks.await

class GroupChatRepository {

    private data class GroupInboxMetadata(
        val name: String,
        val memberCount: Long,
        val createdAt: Long
    )


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
            if (firebaseUser == null || firebaseUser.uid != currentUserId) {
                return Result.failure(
                    IllegalStateException("Authenticated user does not match group creator.")
                )
            }

            val cleanName = groupName.trim()
            if (cleanName.isBlank() || cleanName.length > 50) {
                return Result.failure(
                    IllegalArgumentException("Group name must be between 1 and 50 characters.")
                )
            }

            val uniqueMembers = (listOf(currentUserId) + memberIds)
                .filter { it.isNotBlank() }
                .distinct()

            if (uniqueMembers.size < 2) {
                return Result.failure(
                    IllegalArgumentException("Select at least one other person.")
                )
            }
            if (uniqueMembers.size > 50) {
                return Result.failure(
                    IllegalArgumentException("A group can contain at most 50 members.")
                )
            }

            val groupId = database.getReference("chats").push().key
                ?: return Result.failure(
                    IllegalStateException("Could not create group ID.")
                )

            val notifications = CloudflareNotificationRepository()
            val activityMessageId = notifications.manageGroupMembership(
                action = "create",
                chatId = groupId,
                groupName = cleanName,
                memberIds = uniqueMembers
            ).getOrThrow()

            dispatchGroupMembershipActivity(groupId, activityMessageId)
            syncGroupTopicMembers(groupId, uniqueMembers)

            Result.success(groupId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroup(groupId: String): Group? {
        if (groupId.isBlank()) return null

        // Reuse the metadata-only observer for one-shot reads as well.
        // A read from /chats/{groupId} would include its full messages subtree.
        return observeGroup(groupId).first()
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
                        // Firebase rules denials arrive here asynchronously.
                        // Do not throw from this callback: it runs on the main thread.
                        Log.e(
                            "GroupChatRepository",
                            "Group membership read denied/failed for $currentUserId " +
                                "(code=${error.code}, message=${error.message})"
                        )
                        trySend(emptyList())
                        close()
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
                            observeGroupConversation(
                                groupId = it,
                                currentUserId = currentUserId
                            )
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
        groupId: String,
        currentUserId: String = ""
    ): Flow<Conversation?> =
        combine(
            observeGroupInboxMetadata(groupId),
            observeLatestMessage(groupId),
            observeGroupUnreadCount(
                currentUserId = currentUserId,
                groupId = groupId
            ).onStart {
                /*
                 * Emit zero immediately, then replace it with the persisted
                 * unread counter when its listener receives the first value.
                 */
                emit(0L)
            }
        ) { group, latestMessage, unreadCount ->

            if (group == null) {
                null
            } else {
                Conversation(
                    chatId = groupId,
                    chatType = "group",
                    groupName = group.name,
                    memberCount = group.memberCount,
                    lastMessage = latestMessage?.text.orEmpty(),
                    lastTimestamp =
                        latestMessage?.timestamp
                            ?: group.createdAt,
                    lastSenderId =
                        latestMessage?.senderId.orEmpty(),
                    lastSenderNickname =
                        latestMessage?.senderNickname.orEmpty(),
                    unreadCount = unreadCount
                )
            }
        }

    private fun observeGroupInboxMetadata(
        groupId: String
    ): Flow<GroupInboxMetadata?> =
        combine(
            observeGroupString(groupId, "type"),
            observeGroupString(groupId, "name"),
            observeGroupLong(groupId, "createdAt"),
            observeGroupMembers(groupId)
        ) { type, name, createdAt, members ->
            if (type != "group") {
                null
            } else {
                GroupInboxMetadata(
                    name = name,
                    memberCount = members.size.toLong(),
                    createdAt = createdAt
                )
            }
        }.distinctUntilChanged()

    private fun observeGroupUnreadCount(
        currentUserId: String,
        groupId: String
    ): Flow<Long> =
        callbackFlow {
            if (
                currentUserId.isBlank() ||
                groupId.isBlank() ||
                auth.currentUser?.uid != currentUserId
            ) {
                trySend(0L)
                close()
                return@callbackFlow
            }

            // The notification Worker increments this counter atomically for
            // each incoming group message; markGroupRead resets it to zero.
            // Observe the maintained value instead of downloading and
            // recounting the entire unread message history after each update.
            val ref = database.getReference("userChats")
                .child(currentUserId)
                .child(groupId)
                .child("unreadCount")

            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    trySend(
                        (snapshot.getValue(Long::class.java) ?: 0L).coerceAtLeast(0L)
                    )
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "GroupChatRepository",
                        "Group unread counter listener cancelled for $groupId: ${error.message}"
                    )
                    trySend(0L)
                    close()
                }
            }

            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }.distinctUntilChanged()
    suspend fun markGroupRead(
        currentUserId: String,
        groupId: String,
        timestamp: Long
    ): Result<Unit> {
        return try {
            val firebaseUser = auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match group read owner."
                    )
                )
            }

            if (
                currentUserId.isBlank() ||
                groupId.isBlank() ||
                timestamp < 0L
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid group read state."
                    )
                )
            }

            database
                .getReference("groupReads")
                .child(currentUserId)
                .child(groupId)
                .setValue(timestamp)
                .await()

            runCatching {
                database
                    .getReference("userChats")
                    .child(currentUserId)
                    .child(groupId)
                    .child("unreadCount")
                    .setValue(0L)
                    .await()
            }.onFailure {
                Log.w("GroupChatRepository", "Could not clear the group unread badge.", it)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
                        // orderByChild(timestamp).limitToLast(1) already returns
                        // only the latest message, so no list allocation or max scan
                        // is needed here.
                        val latest = snapshot.children.firstOrNull()
                            ?.getValue(Message::class.java)

                        trySend(latest)
                    }

                    override fun onCancelled(
                        error: com.google.firebase.database.DatabaseError
                    ) {
                        Log.e(
                            "GroupChatRepository",
                            "Latest group message listener cancelled for $groupId: ${error.message}"
                        )
                        trySend(null)
                        close()
                    }
                }

            messagesRef.addValueEventListener(listener)

            awaitClose {
                messagesRef.removeEventListener(listener)
            }
        }

    fun observeGroup(groupId: String): Flow<Group?> {
        if (groupId.isBlank()) return flowOf(null)

        // Do not listen at /chats/{groupId}: that node also contains the
        // entire messages history. Separate metadata listeners prevent every
        // new message from retransmitting the group's full message subtree.
        return combine(
            observeGroupString(groupId, "type"),
            observeGroupString(groupId, "name"),
            observeGroupString(groupId, "createdBy"),
            observeGroupLong(groupId, "createdAt"),
            observeGroupMembers(groupId)
        ) { type, name, createdBy, createdAt, members ->
            if (type != "group") {
                null
            } else {
                Group(
                    chatId = groupId,
                    type = "group",
                    name = name,
                    createdBy = createdBy,
                    createdAt = createdAt,
                    members = members
                )
            }
        }.distinctUntilChanged()
    }

    private fun observeGroupString(
        groupId: String,
        field: String
    ): Flow<String> =
        callbackFlow {
            val ref = database.getReference("chats")
                .child(groupId)
                .child(field)
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    trySend(snapshot.getValue(String::class.java).orEmpty())
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "GroupChatRepository",
                        "Group $field listener cancelled for $groupId: ${error.message}"
                    )
                    trySend("")
                    close()
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }

    private fun observeGroupLong(
        groupId: String,
        field: String
    ): Flow<Long> =
        callbackFlow {
            val ref = database.getReference("chats")
                .child(groupId)
                .child(field)
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    trySend(snapshot.getValue(Long::class.java) ?: 0L)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "GroupChatRepository",
                        "Group $field listener cancelled for $groupId: ${error.message}"
                    )
                    trySend(0L)
                    close()
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }

    private fun observeGroupMembers(
        groupId: String
    ): Flow<Map<String, String>> =
        callbackFlow {
            val ref = database.getReference("chats")
                .child(groupId)
                .child("members")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val members = snapshot.children.mapNotNull { child ->
                        val uid = child.key
                        val role = child.getValue(String::class.java)
                        if (uid.isNullOrBlank() || role.isNullOrBlank()) {
                            null
                        } else {
                            uid to role
                        }
                    }.toMap()
                    trySend(members)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "GroupChatRepository",
                        "Group members listener cancelled for $groupId: ${error.message}"
                    )
                    trySend(emptyMap())
                    close()
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }

    suspend fun sendMessage(
        groupId: String,
        messageId: String,
        text: String,
        senderId: String,
        senderNickname: String,
        replyTo: ReplyTo? = null,
        attachment: com.example.inchat.data.model.Attachment? = null
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

            /*
             * Do not perform a remote get() before sending.
             *
             * A get() can suspend while offline, which prevents the
             * message write from ever reaching Firebase's offline
             * queue. Membership is enforced by Realtime Database
             * security rules on the actual message write.
             */
            val messageData =
                mutableMapOf<String, Any>(
                    "id" to messageId,
                    "senderId" to senderId,
                    "senderNickname" to senderNickname,
                    "text" to cleanText,
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

            // Notification and group inbox synchronization run in durable
            // background work. Do not make the sender wait for HTTP retries.
            try {
                MessageNotificationWorker.enqueue(
                    context = InChatApplication.instance,
                    senderId = senderId,
                    chatId = groupId,
                    messageId = messageId
                )
            } catch (queueError: Exception) {
                // The group message is already saved. A scheduling problem
                // must not turn a successful message write into a failed send.
                Log.e(
                    "GroupChatRepository",
                    "Group message was saved, but its background event could not be queued.",
                    queueError
                )
            }

            Result.success(messageId)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    /*
     * =========================================================
     * RENAME GROUP
     * =========================================================
     *
     * Only an existing group admin may change the group name.
     */
    suspend fun renameGroup(
        currentUserId: String,
        groupId: String,
        newName: String
    ): Result<Unit> {

        return try {

            val firebaseUser = auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match group admin."
                    )
                )
            }

            val cleanName = newName.trim()

            if (
                groupId.isBlank() ||
                cleanName.isBlank() ||
                cleanName.length > 50
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Group name must be between 1 and 50 characters."
                    )
                )
            }

            val group =
                getGroup(groupId)
                    ?: return Result.failure(
                        IllegalArgumentException(
                            "Group does not exist."
                        )
                    )

            if (group.members[currentUserId] != "admin") {
                return Result.failure(
                    IllegalStateException(
                        "Only group admins can change the group name."
                    )
                )
            }

            if (cleanName == group.name) {
                return Result.success(Unit)
            }

            val actorName = usernameFor(currentUserId)
            val updates = mutableMapOf<String, Any>(
                "chats/$groupId/name" to cleanName
            )
            updates.putAll(
                systemEventUpdates(
                    groupId = groupId,
                    actorId = currentUserId,
                    eventType = "group_renamed",
                    eventText = "@$actorName renamed the group to \"$cleanName\""
                )
            )

            database.reference.updateChildren(updates).await()
            dispatchGroupMessageEvents(groupId, updates)

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /*
     * =========================================================
     * ADD GROUP MEMBERS
     * =========================================================
     *
     * Membership and the Home index are updated in separate
     * validated writes, with rollback if the index update fails.
     */
    suspend fun addMembers(
        currentUserId: String,
        groupId: String,
        memberIds: List<String>
    ): Result<Unit> {
        return try {
            val firebaseUser = auth.currentUser
            if (firebaseUser == null || firebaseUser.uid != currentUserId) {
                return Result.failure(
                    IllegalStateException("Authenticated user does not match group admin.")
                )
            }

            val group = getGroup(groupId)
                ?: return Result.failure(IllegalArgumentException("Group does not exist."))

            if (group.members[currentUserId] != "admin") {
                return Result.failure(
                    IllegalStateException("Only group admins can add members.")
                )
            }

            val newMemberIds = memberIds
                .filter { it.isNotBlank() }
                .distinct()
                .filterNot { group.members.containsKey(it) }

            if (newMemberIds.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("No new members were selected.")
                )
            }
            if (group.members.size + newMemberIds.size > 50) {
                return Result.failure(
                    IllegalArgumentException("A group can contain at most 50 members.")
                )
            }

            val notifications = CloudflareNotificationRepository()
            val activityMessageId = notifications.manageGroupMembership(
                action = "add",
                chatId = groupId,
                memberIds = newMemberIds
            ).getOrThrow()

            dispatchGroupMembershipActivity(groupId, activityMessageId)
            syncGroupTopicMembers(groupId, newMemberIds)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setMemberRole(
        currentUserId: String,
        groupId: String,
        memberId: String,
        role: String
    ): Result<Unit> {
        return try {
            val firebaseUser = auth.currentUser
            if (firebaseUser == null || firebaseUser.uid != currentUserId) {
                return Result.failure(
                    IllegalStateException("Authenticated user does not match group admin.")
                )
            }

            if (memberId.isBlank() || role !in setOf("admin", "member")) {
                return Result.failure(IllegalArgumentException("Invalid group role."))
            }

            val group = getGroup(groupId)
                ?: return Result.failure(IllegalArgumentException("Group does not exist."))

            if (group.members[currentUserId] != "admin") {
                return Result.failure(
                    IllegalStateException("Only group admins can change member roles.")
                )
            }
            if (!group.members.containsKey(memberId)) {
                return Result.failure(
                    IllegalArgumentException("That user is not a group member.")
                )
            }
            if (memberId == currentUserId || memberId == group.createdBy) {
                return Result.failure(
                    IllegalStateException("The group creator remains an admin.")
                )
            }
            if (group.members[memberId] == role) {
                return Result.success(Unit)
            }

            val notifications = CloudflareNotificationRepository()
            val activityMessageId = notifications.manageGroupMembership(
                action = "set_role",
                chatId = groupId,
                targetUid = memberId,
                role = role
            ).getOrThrow()

            dispatchGroupMembershipActivity(groupId, activityMessageId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeMember(
        currentUserId: String,
        groupId: String,
        memberId: String
    ): Result<Unit> {
        return try {
            val firebaseUser = auth.currentUser
            if (firebaseUser == null || firebaseUser.uid != currentUserId) {
                return Result.failure(
                    IllegalStateException("Authenticated user does not match group admin.")
                )
            }

            val group = getGroup(groupId)
                ?: return Result.failure(IllegalArgumentException("Group does not exist."))

            if (group.members[currentUserId] != "admin") {
                return Result.failure(
                    IllegalStateException("Only group admins can remove members.")
                )
            }
            if (!group.members.containsKey(memberId)) {
                return Result.failure(
                    IllegalArgumentException("That user is not a group member.")
                )
            }
            if (memberId == group.createdBy || memberId == currentUserId) {
                return Result.failure(
                    IllegalStateException("The group creator cannot be removed.")
                )
            }
            if (group.members.size <= 2) {
                return Result.failure(
                    IllegalStateException("A group needs at least two members.")
                )
            }

            val notifications = CloudflareNotificationRepository()
            val activityMessageId = notifications.manageGroupMembership(
                action = "remove",
                chatId = groupId,
                targetUid = memberId
            ).getOrThrow()

            notifications.setGroupTopicSubscription(
                chatId = groupId,
                targetUid = memberId,
                subscribe = false
            ).onFailure { error ->
                Log.w(
                    "GroupChatRepository",
                    "Member was removed, but their group notification topic could not be cleared.",
                    error
                )
            }

            dispatchGroupMembershipActivity(groupId, activityMessageId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun leaveGroup(
        currentUserId: String,
        groupId: String
    ): Result<Unit> {
        return try {
            val firebaseUser = auth.currentUser
            if (firebaseUser == null || firebaseUser.uid != currentUserId) {
                return Result.failure(
                    IllegalStateException("Authenticated user does not match group member.")
                )
            }

            val group = getGroup(groupId)
                ?: return Result.failure(IllegalArgumentException("Group does not exist."))

            if (!group.members.containsKey(currentUserId)) {
                return Result.failure(
                    IllegalStateException("You are not a member of this group.")
                )
            }
            if (currentUserId == group.createdBy) {
                return Result.failure(
                    IllegalStateException(
                        "The group creator cannot leave. Transfer ownership is not available yet."
                    )
                )
            }
            if (group.members.size <= 2) {
                return Result.failure(
                    IllegalStateException("A group needs at least two members.")
                )
            }

            val notifications = CloudflareNotificationRepository()
            val activityMessageId = notifications.manageGroupMembership(
                action = "leave",
                chatId = groupId
            ).getOrThrow()

            notifications.setGroupTopicSubscription(
                chatId = groupId,
                targetUid = currentUserId,
                subscribe = false
            ).onFailure { error ->
                Log.w(
                    "GroupChatRepository",
                    "You left the group, but its notification topic could not be cleared.",
                    error
                )
            }

            dispatchGroupMembershipActivity(groupId, activityMessageId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun usernameFor(uid: String): String {
        return database.getReference("users")
            .child(uid)
            .child("username")
            .get()
            .await()
            .getValue(String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "member"
    }

    private suspend fun systemEventUpdates(
        groupId: String,
        actorId: String,
        eventType: String,
        eventText: String
    ): Map<String, Any> {
        val messagesRef = database.getReference("chats")
            .child(groupId)
            .child("messages")
        val messageId = messagesRef.push().key
            ?: throw IllegalStateException("Could not create group activity message ID.")

        val eventMessage = mapOf<String, Any>(
            "id" to messageId,
            "senderId" to actorId,
            "senderNickname" to "SYSTEM",
            "text" to eventText,
            "timestamp" to ServerValue.TIMESTAMP,
            "systemEvent" to eventType
        )
        return mapOf("chats/$groupId/messages/$messageId" to eventMessage)
    }

    private suspend fun syncGroupTopicMembers(
        groupId: String,
        memberIds: List<String>
    ) = coroutineScope {
        val notifications = CloudflareNotificationRepository()
        memberIds.distinct()
            .map { memberId ->
                async {
                    notifications
                        .setGroupTopicSubscription(
                            chatId = groupId,
                            targetUid = memberId,
                            subscribe = true
                        )
                        .onFailure { error ->
                            Log.w(
                                "GroupChatRepository",
                                "Could not enable group notifications for $memberId.",
                                error
                            )
                        }
                }
            }
            .awaitAll()
    }

    private suspend fun dispatchGroupMembershipActivity(
        groupId: String,
        messageId: String?
    ) {
        val notifications = CloudflareNotificationRepository()
        if (!messageId.isNullOrBlank()) {
            notifications.notifyMessage(groupId, messageId)
                .onFailure { error ->
                    Log.w(
                        "GroupChatRepository",
                        "Group membership changed, but its activity preview could not be synchronized.",
                        error
                    )
                    notifications.syncConversationPreview(groupId)
                        .onFailure { previewError ->
                            Log.w(
                                "GroupChatRepository",
                                "Fallback group preview sync failed.",
                                previewError
                            )
                        }
                }
        } else {
            notifications.syncConversationPreview(groupId)
                .onFailure { error ->
                    Log.w(
                        "GroupChatRepository",
                        "Group preview could not be synchronized after a membership retry.",
                        error
                    )
                }
        }
    }

    private suspend fun dispatchGroupMessageEvents(
        groupId: String,
        updates: Map<String, *>
    ) {
        val messagePrefix = "chats/$groupId/messages/"
        val latestActivityId = updates.keys
            .lastOrNull { it.startsWith(messagePrefix) }
            ?.removePrefix(messagePrefix)
            ?.takeIf { it.isNotBlank() }
            ?: return

        CloudflareNotificationRepository()
            .notifyMessage(groupId, latestActivityId)
            .onFailure { error ->
                Log.w(
                    "GroupChatRepository",
                    "Group activity was saved, but preview sync failed.",
                    error
                )
                CloudflareNotificationRepository()
                    .syncConversationPreview(groupId)
                    .onFailure { previewError ->
                        Log.w(
                            "GroupChatRepository",
                            "Fallback group preview sync failed.",
                            previewError
                        )
                    }
            }
    }


}