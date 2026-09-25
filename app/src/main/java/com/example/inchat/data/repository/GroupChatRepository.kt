package com.example.inchat.data.repository

import com.example.inchat.data.model.Conversation
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
            observeGroup(groupId),
            observeLatestMessage(groupId),
            observeGroupUnreadCount(
                currentUserId = currentUserId,
                groupId = groupId
            ).onStart {
                /*
                 * The unread calculation may need to inspect message
                 * history. Never make the Home row wait for that work.
                 * Emit zero immediately, then replace it with the real
                 * unread count when the listener finishes its first load.
                 */
                emit(0L)
            }
        ) { group, latestMessage, unreadCount ->

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
                    unreadCount = unreadCount
                )
            }
        }

    private fun observeGroupUnreadCount(
        currentUserId: String,
        groupId: String
    ): Flow<Long> =
        combine(
            observeGroupReadTimestamp(
                currentUserId = currentUserId,
                groupId = groupId
            ),
            observeGroupMessagesSince(
                groupId = groupId
            )
        ) { lastReadTimestamp, messages ->
            messages.count { message ->
                message.timestamp > lastReadTimestamp &&
                        message.senderId != currentUserId
            }.toLong()
        }

    private fun observeGroupReadTimestamp(
        currentUserId: String,
        groupId: String
    ): Flow<Long> =
        callbackFlow {
            if (
                currentUserId.isBlank() ||
                groupId.isBlank()
            ) {
                trySend(0L)
                close()
                return@callbackFlow
            }

            val ref =
                database
                    .getReference("groupReads")
                    .child(currentUserId)
                    .child(groupId)

            val listener =
                object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {
                        trySend(
                            snapshot.getValue(Long::class.java) ?: 0L
                        )
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                        close(error.toException())
                    }
                }

            ref.addValueEventListener(listener)

            awaitClose {
                ref.removeEventListener(listener)
            }
        }

    private fun observeGroupMessagesSince(
        groupId: String
    ): Flow<List<Message>> =
        callbackFlow {
            if (groupId.isBlank()) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }

            val ref =
                database
                    .getReference("chats")
                    .child(groupId)
                    .child("messages")
                    .orderByChild("timestamp")

            val listener =
                object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {
                        trySend(
                            snapshot.children
                                .mapNotNull {
                                    it.getValue(Message::class.java)
                                }
                                .sortedBy { it.timestamp }
                        )
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                        close(error.toException())
                    }
                }

            ref.addValueEventListener(listener)

            awaitClose {
                ref.removeEventListener(listener)
            }
        }

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

            database
                .getReference("chats")
                .child(groupId)
                .child("name")
                .setValue(cleanName)
                .await()

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
     * Membership and the Spark-only Home discovery index are
     * updated atomically.
     */
    suspend fun addMembers(
        currentUserId: String,
        groupId: String,
        memberIds: List<String>
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
                        "Only group admins can add members."
                    )
                )
            }

            val newMemberIds =
                memberIds
                    .filter { it.isNotBlank() }
                    .distinct()
                    .filterNot { group.members.containsKey(it) }

            if (newMemberIds.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException(
                        "No new members were selected."
                    )
                )
            }

            if (
                group.members.size +
                        newMemberIds.size >
                50
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "A group can contain at most 50 members."
                    )
                )
            }

            val updates =
                mutableMapOf<String, Any?>()

            newMemberIds.forEach { uid ->
                updates[
                    "chats/$groupId/members/$uid"
                ] = "member"

                updates[
                    "groupMemberships/$uid/$groupId"
                ] = true
            }

            database
                .reference
                .updateChildren(updates)
                .await()

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /*
     * =========================================================
     * CHANGE MEMBER ROLE
     * =========================================================
     *
     * The creator is permanently an admin. Admins cannot change
     * their own role, which guarantees that the group always keeps
     * at least its creator as an administrator.
     */
    suspend fun setMemberRole(
        currentUserId: String,
        groupId: String,
        memberId: String,
        role: String
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

            if (
                memberId.isBlank() ||
                role !in setOf("admin", "member")
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid group role."
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
                        "Only group admins can change member roles."
                    )
                )
            }

            if (!group.members.containsKey(memberId)) {
                return Result.failure(
                    IllegalArgumentException(
                        "That user is not a group member."
                    )
                )
            }

            if (
                memberId == currentUserId ||
                memberId == group.createdBy
            ) {
                return Result.failure(
                    IllegalStateException(
                        "The group creator remains an admin."
                    )
                )
            }

            if (group.members[memberId] == role) {
                return Result.success(Unit)
            }

            database
                .getReference("chats")
                .child(groupId)
                .child("members")
                .child(memberId)
                .setValue(role)
                .await()

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /*
     * =========================================================
     * REMOVE MEMBER
     * =========================================================
     *
     * Removing someone also removes their Home membership index
     * and private group read cursor in the same database update.
     */
    suspend fun removeMember(
        currentUserId: String,
        groupId: String,
        memberId: String
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
                        "Only group admins can remove members."
                    )
                )
            }

            if (!group.members.containsKey(memberId)) {
                return Result.failure(
                    IllegalArgumentException(
                        "That user is not a group member."
                    )
                )
            }

            if (
                memberId == group.createdBy ||
                memberId == currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "The group creator cannot be removed."
                    )
                )
            }

            if (group.members.size <= 2) {
                return Result.failure(
                    IllegalStateException(
                        "A group needs at least two members."
                    )
                )
            }

            val updates =
                mapOf<String, Any?>(
                    "chats/$groupId/members/$memberId" to null,
                    "groupMemberships/$memberId/$groupId" to null,
                    "groupReads/$memberId/$groupId" to null
                )

            database
                .reference
                .updateChildren(updates)
                .await()

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /*
     * =========================================================
     * LEAVE GROUP
     * =========================================================
     *
     * The creator stays with the group so the group always keeps
     * a permanent administrator.
     */
    suspend fun leaveGroup(
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

            val group =
                getGroup(groupId)
                    ?: return Result.failure(
                        IllegalArgumentException(
                            "Group does not exist."
                        )
                    )

            if (!group.members.containsKey(currentUserId)) {
                return Result.failure(
                    IllegalStateException(
                        "You are not a member of this group."
                    )
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
                    IllegalStateException(
                        "A group needs at least two members."
                    )
                )
            }

            val updates =
                mapOf<String, Any?>(
                    "chats/$groupId/members/$currentUserId" to null,
                    "groupMemberships/$currentUserId/$groupId" to null,
                    "groupReads/$currentUserId/$groupId" to null
                )

            database
                .reference
                .updateChildren(updates)
                .await()

            Result.success(Unit)

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