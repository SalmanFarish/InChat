package com.example.inchat.data.repository

import android.util.Log

import com.example.inchat.InChatApplication
import com.example.inchat.data.worker.MessageNotificationWorker

import com.example.inchat.data.model.Conversation
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
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
                        val groupIds = snapshot.children
                            .mapNotNull { it.key }
                            .filter { it.isNotBlank() }
                            .distinct()

                        trySend(groupIds)
                        backfillGroupReadCursorMirrors(currentUserId, groupIds)
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
        }.distinctUntilChanged()

    /**
     * Backfill the Worker's lightweight group read-cursor mirror for accounts
     * upgrading from versions that stored cursors only under groupReads.
     *
     * The source read happens once for the current account's group list. Each
     * mirror is merged with a transaction so an older backfill result can never
     * overwrite a newer markGroupRead write that races with this migration.
     */
    private fun backfillGroupReadCursorMirrors(
        currentUserId: String,
        groupIds: List<String>
    ) {
        if (
            currentUserId.isBlank() ||
            auth.currentUser?.uid != currentUserId ||
            groupIds.isEmpty()
        ) {
            return
        }

        database.getReference("groupReads")
            .child(currentUserId)
            .get()
            .addOnSuccessListener { readSnapshot ->
                if (auth.currentUser?.uid != currentUserId) {
                    return@addOnSuccessListener
                }

                groupIds.forEach { groupId ->
                    val lastReadTimestamp =
                        readSnapshot.child(groupId).getValue(Long::class.java) ?: 0L
                    if (lastReadTimestamp <= 0L) return@forEach

                    val cursorRef = database.getReference("chats")
                        .child(groupId)
                        .child("groupReadCursors")
                        .child(currentUserId)

                    cursorRef.runTransaction(object : Transaction.Handler {
                        override fun doTransaction(currentData: MutableData): Transaction.Result {
                            val currentTimestamp =
                                currentData.getValue(Long::class.java) ?: 0L
                            if (lastReadTimestamp > currentTimestamp) {
                                currentData.value = lastReadTimestamp
                            }
                            return Transaction.success(currentData)
                        }

                        override fun onComplete(
                            error: DatabaseError?,
                            committed: Boolean,
                            currentData: DataSnapshot?
                        ) {
                            if (error != null) {
                                Log.w(
                                    "GroupChatRepository",
                                    "Could not backfill the group read cursor for $groupId",
                                    error.toException()
                                )
                            }
                        }
                    }, false)
                }
            }
            .addOnFailureListener { error ->
                Log.w(
                    "GroupChatRepository",
                    "Could not load existing group read cursors for $currentUserId",
                    error
                )
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
        callbackFlow {
            if (
                groupId.isBlank() ||
                currentUserId.isBlank() ||
                auth.currentUser?.uid != currentUserId
            ) {
                trySend(null)
                close()
                return@callbackFlow
            }

            // The Worker already maintains the complete per-user group inbox
            // entry (preview, member count, and unread count). One listener on
            // that summary replaces separate group metadata, latest-message,
            // and unread-counter listeners.
            val ref = database.getReference("userChats")
                .child(currentUserId)
                .child(groupId)

            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val conversation = snapshot.getValue(Conversation::class.java)
                        ?.takeIf {
                            it.chatId == groupId &&
                                it.chatType == "group" &&
                                it.groupName.isNotBlank()
                        }
                    trySend(conversation)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "GroupChatRepository",
                        "Group inbox summary listener cancelled for $groupId: ${error.message}"
                    )
                    trySend(null)
                    close()
                }
            }

            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }.distinctUntilChanged()

    fun observeGroupReadTimestampForMember(
        groupId: String,
        memberId: String
    ): Flow<Long> =
        callbackFlow {
            if (groupId.isBlank() || memberId.isBlank()) {
                trySend(0L)
                close()
                return@callbackFlow
            }

            // Group read receipts are stored separately from group metadata
            // and messages, so observe only this member's read cursor.
            val ref = database.getReference("groupReads")
                .child(memberId)
                .child(groupId)

            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    trySend(
                        (snapshot.getValue(Long::class.java) ?: 0L).coerceAtLeast(0L)
                    )
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "GroupChatRepository",
                        "Group read receipt listener cancelled for $memberId in $groupId: ${error.message}"
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

            // Advance the read cursor and clear the inbox badge in one
            // multi-location update. This avoids a second network round trip
            // and prevents the two pieces of read state diverging.
            database.reference.updateChildren(
                mapOf(
                    "groupReads/$currentUserId/$groupId" to timestamp,
                    "chats/$groupId/groupReadCursors/$currentUserId" to timestamp,
                    "userChats/$currentUserId/$groupId/unreadCount" to 0L
                )
            ).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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

    /**
     * Observes the Worker-authored E2EE membership snapshot as one atomic value.
     *
     * Do not derive this from observeGroupMembers() plus a separate epoch
     * listener: their callbacks can be delivered at different times. A null
     * value means this group has not yet been initialized for E2EE or its
     * snapshot is malformed; senders must fail closed in either case.
     */
    fun observeGroupCryptoMembership(
        groupId: String
    ): Flow<GroupCryptoMembershipSnapshot?> {
        if (groupId.isBlank()) return flowOf(null)

        return callbackFlow {
            val ref = database.getReference("chats")
                .child(groupId)
                .child("cryptoMembership")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val epoch = snapshot.child("epoch").getValue(Long::class.java)
                        val memberEntries = snapshot.child("members").children.map { child ->
                            child.key to child.getValue(String::class.java)
                        }
                        val value = GroupCryptoMembershipSnapshot.fromEntries(
                            groupId = groupId,
                            epoch = epoch,
                            entries = memberEntries,
                        )
                        trySend(value)
                    } catch (error: Exception) {
                        Log.e(
                            "GroupChatRepository",
                            "Could not parse group crypto membership snapshot",
                            error
                        )
                        trySend(null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "GroupChatRepository",
                        "Group crypto membership listener cancelled: ${error.message}"
                    )
                    trySend(null)
                    close()
                }
            }
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener) }
        }.distinctUntilChanged()
    }

    /** Returns the current complete snapshot, or null when E2EE initialization is required. */
    suspend fun getGroupCryptoMembershipSnapshot(
        groupId: String
    ): GroupCryptoMembershipSnapshot? =
        observeGroupCryptoMembership(groupId).first()

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
            val cryptoRef = database.getReference("chats")
                .child(groupId)
                .child("cryptoMembership")
                .child("members")
            val legacyRef = database.getReference("chats")
                .child(groupId)
                .child("members")
            var cryptoMembers: Map<String, String>? = null
            var legacyMembers: Map<String, String> = emptyMap()

            fun parseMembers(snapshot: DataSnapshot): Map<String, String> =
                snapshot.children.mapNotNull { child ->
                    val uid = child.key
                    val role = child.getValue(String::class.java)
                    if (uid.isNullOrBlank() || role == null || role !in setOf("admin", "member")) null
                    else uid to role
                }.toMap()

            fun emitCurrentMembers() {
                trySend(cryptoMembers ?: legacyMembers)
            }

            val cryptoListener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    cryptoMembers = if (snapshot.exists()) parseMembers(snapshot) else null
                    emitCurrentMembers()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("GroupChatRepository", "E2EE group members listener cancelled for $groupId: " + error.message)
                    trySend(legacyMembers)
                    close()
                }
            }
            val legacyListener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    legacyMembers = parseMembers(snapshot)
                    emitCurrentMembers()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w("GroupChatRepository", "Legacy group members listener unavailable for $groupId: " + error.message)
                    emitCurrentMembers()
                }
            }

            cryptoRef.addValueEventListener(cryptoListener)
            legacyRef.addValueEventListener(legacyListener)
            awaitClose {
                cryptoRef.removeEventListener(cryptoListener)
                legacyRef.removeEventListener(legacyListener)
            }
        }

    /**
     * Legacy plaintext group-send API retained only so older call sites fail
     * closed at runtime. Group text must use the authenticated Megolm outbox.
     */
    @Deprecated(
        message = "Plaintext group-message sending is disabled; use the encrypted Megolm coordinator.",
    )
    suspend fun sendMessage(
        groupId: String,
        messageId: String,
        text: String,
        senderId: String,
        senderNickname: String,
        replyTo: ReplyTo? = null,
        attachment: com.example.inchat.data.model.Attachment? = null
    ): Result<String> = Result.failure(
        UnsupportedOperationException(
            "Plaintext group-message delivery is disabled. Use the encrypted Megolm coordinator.",
        ),
    )


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

            database.getReference("chats")
                .child(groupId)
                .child("name")
                .setValue(cleanName)
                .await()
            CloudflareNotificationRepository()
                .syncConversationPreview(groupId)
                .onFailure { previewError ->
                    Log.w("GroupChatRepository", "Group name changed, but inbox preview sync failed.", previewError)
                }

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
        @Suppress("UNUSED_PARAMETER") messageId: String?,
    ) {
        CloudflareNotificationRepository()
            .syncConversationPreview(groupId)
            .onFailure { error ->
                Log.w(
                    "GroupChatRepository",
                    "Group metadata changed, but inbox preview synchronization failed.",
                    error,
                )
            }
    }


}