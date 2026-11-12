package com.example.inchat.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.GroupChatRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GroupChatViewModel : ViewModel() {

    private val groupRepository = GroupChatRepository()
    private val chatRepository = ChatRepository()

    private val _group = MutableStateFlow<Group?>(null)
    val group: StateFlow<Group?> = _group.asStateFlow()

    private val _messages =
        MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> =
        _messages.asStateFlow()

    private val _messagesLoaded = MutableStateFlow(false)
    val messagesLoaded: StateFlow<Boolean> =
        _messagesLoaded.asStateFlow()

    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()

    private val _isLoadingOlderMessages = MutableStateFlow(false)
    val isLoadingOlderMessages: StateFlow<Boolean> =
        _isLoadingOlderMessages.asStateFlow()

    private var hasRequestedOlderMessages = false

    private val _replyingTo = MutableStateFlow<Message?>(null)
    val replyingTo: StateFlow<Message?> =
        _replyingTo.asStateFlow()

    private val _editingMessage = MutableStateFlow<Message?>(null)
    val editingMessage: StateFlow<Message?> =
        _editingMessage.asStateFlow()

    private val _pendingMessageIds =
        MutableStateFlow<Set<String>>(emptySet())
    val pendingMessageIds: StateFlow<Set<String>> =
        _pendingMessageIds.asStateFlow()

    private val database = FirebaseDatabase.getInstance()
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var connectionListenerJob: Job? = null
    private var pendingMessagesJob: Job? = null
    private var pendingReconciliationJob: Job? = null
    private var groupJob: Job? = null
    private var messagesJob: Job? = null
    private val groupReadJobs = mutableMapOf<String, Job>()
    private val _groupReadTimestamps =
        MutableStateFlow<Map<String, Long>>(emptyMap())
    val groupReadTimestamps: StateFlow<Map<String, Long>> =
        _groupReadTimestamps.asStateFlow()
    private var listeningGroupId = ""

    init {
        startConnectionListener()
    }

    private fun startConnectionListener() {
        connectionListenerJob?.cancel()
        connectionListenerJob = viewModelScope.launch {
            val connectedRef = database.getReference(".info/connected")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _isConnected.value =
                        snapshot.getValue(Boolean::class.java) ?: false
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(
                        "GroupChatViewModel",
                        "Connection listener cancelled",
                        error.toException()
                    )
                    _isConnected.value = false
                }
            }

            connectedRef.addValueEventListener(listener)
            try {
                awaitCancellation()
            } finally {
                connectedRef.removeEventListener(listener)
            }
        }
    }

    fun startListening(
        groupId: String,
        currentUserId: String
    ) {

        if (groupId.isBlank() || currentUserId.isBlank()) {
            return
        }

        if (listeningGroupId == groupId) {
            return
        }

        listeningGroupId = groupId
        hasRequestedOlderMessages = false
        _hasOlderMessages.value = false
        _isLoadingOlderMessages.value = false
        groupJob?.cancel()
        messagesJob?.cancel()
        pendingMessagesJob?.cancel()
        pendingReconciliationJob?.cancel()
        pendingMessagesJob = null
        pendingReconciliationJob = null
        _pendingMessageIds.value = emptySet()
        groupReadJobs.values.forEach { it.cancel() }
        groupReadJobs.clear()
        _groupReadTimestamps.value = emptyMap()

        _group.value = null

        // Restore pending indicators from Room so they survive app restarts.
        pendingMessagesJob = viewModelScope.launch {
            try {
                chatRepository.observePendingMessageIds(groupId).collect { pendingIds ->
                    if (listeningGroupId == groupId) {
                        _pendingMessageIds.value = pendingIds
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "GroupChatViewModel",
                    "Pending group message state could not be observed",
                    e
                )
            }
        }

        // Once connected, confirm queued IDs against Firebase. Retry only when
        // the remote record is absent, always reusing its original message ID.
        pendingReconciliationJob = viewModelScope.launch {
            _isConnected.collect { connected ->
                if (!connected || listeningGroupId != groupId) {
                    return@collect
                }

                val pendingMessages = chatRepository.getPendingMessages(groupId)
                for (localMessage in pendingMessages) {
                    try {
                        val remoteMessage = chatRepository.getRemoteMessage(
                            chatId = groupId,
                            messageId = localMessage.id
                        )

                        when {
                            remoteMessage != null &&
                                remoteMessage.id == localMessage.id &&
                                remoteMessage.senderId == currentUserId -> {
                                chatRepository.markOutgoingMessageSent(
                                    chatId = groupId,
                                    messageId = localMessage.id
                                )
                            }

                            remoteMessage == null -> {
                                groupRepository.sendMessage(
                                    groupId = groupId,
                                    messageId = localMessage.id,
                                    text = localMessage.text,
                                    senderId = currentUserId,
                                    senderNickname = localMessage.senderNickname,
                                    replyTo = localMessage.replyTo,
                                    attachment = localMessage.attachment
                                ).onSuccess {
                                    chatRepository.markOutgoingMessageSent(
                                        chatId = groupId,
                                        messageId = localMessage.id
                                    )
                                }.onFailure { error ->
                                    Log.w(
                                        "GroupChatViewModel",
                                        "Pending group message retry failed: " + localMessage.id,
                                        error
                                    )
                                }
                            }

                            else -> {
                                Log.w(
                                    "GroupChatViewModel",
                                    "Pending group message ID exists remotely with unexpected sender: " + localMessage.id
                                )
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(
                            "GroupChatViewModel",
                            "Could not reconcile pending group message " + localMessage.id,
                            e
                        )
                    }
                }
            }
        }

        // Reuse the last known message snapshot immediately while the
        // realtime group/message listeners reconnect.
        val cachedMessages = chatRepository.getCachedMessages(groupId)
        _messages.value = cachedMessages ?: emptyList()
        _messagesLoaded.value = cachedMessages != null
        _hasOlderMessages.value = (cachedMessages?.size ?: 0) >= 200

        groupJob = viewModelScope.launch {
            try {
                groupRepository
                    .observeGroup(groupId)
                    .collect { group ->
                        _group.value = group

                        if (group != null) {
                            val memberIds =
                                group.members.keys
                                    .filter { it != currentUserId }

                            groupReadJobs.keys
                                .filter { it !in memberIds }
                                .forEach { memberId ->
                                    groupReadJobs.remove(memberId)?.cancel()
                                    _groupReadTimestamps.value =
                                        _groupReadTimestamps.value -
                                                memberId
                                }

                            memberIds.forEach { memberId ->
                                if (groupReadJobs.containsKey(memberId)) {
                                    return@forEach
                                }

                                groupReadJobs[memberId] =
                                    viewModelScope.launch {
                                        try {
                                            groupRepository
                                                .observeGroupReadTimestampForMember(
                                                    groupId = group.chatId,
                                                    memberId = memberId
                                                )
                                                .collect { timestamp ->
                                                    _groupReadTimestamps.value =
                                                        _groupReadTimestamps.value +
                                                                (memberId to timestamp)
                                                }
                                        } catch (
                                            e: kotlinx.coroutines.CancellationException
                                        ) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.e(
                                                "GroupChatViewModel",
                                                "Group read listener failed for $memberId",
                                                e
                                            )
                                        }
                                    }
                            }
                        }

                        if (
                            group == null ||
                            currentUserId !in group.members.keys
                        ) {
                            _replyingTo.value = null
                            _editingMessage.value = null
                        }
                    }
            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "GroupChatViewModel",
                    "Group listener failed",
                    e
                )
            }
        }

        messagesJob = viewModelScope.launch {
            try {
                chatRepository
                    .getMessagesFlow(groupId)
                    .collect { messageList ->
                        _messages.value = messageList
                        _messagesLoaded.value = true
                        if (!hasRequestedOlderMessages) {
                            _hasOlderMessages.value = messageList.size >= 200
                        }

                        val editingId =
                            _editingMessage.value?.id

                        if (
                            editingId != null &&
                            messageList.none { it.id == editingId }
                        ) {
                            _editingMessage.value = null
                        }
                    }
            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "GroupChatViewModel",
                    "Group message listener failed",
                    e
                )
            }
        }
    }

    fun setReplyingTo(message: Message) {
        if (
            message.id.isBlank() ||
            message.senderId.isBlank() ||
            message.senderNickname.isBlank() ||
            message.text.isBlank()
        ) {
            return
        }

        _editingMessage.value = null
        _replyingTo.value = message
    }

    fun clearReplyingTo() {
        _replyingTo.value = null
    }

    fun setEditingMessage(
        message: Message,
        currentUserId: String
    ) {
        if (
            message.senderId != currentUserId ||
            message.id.isBlank()
        ) {
            return
        }

        _replyingTo.value = null
        _editingMessage.value = message
    }

    fun clearEditingMessage() {
        _editingMessage.value = null
    }

    fun sendMessage(
        text: String,
        currentUserId: String,
        senderNickname: String,
        onResult: (Boolean, String?) -> Unit
    ) {

        val cleanText = text.trim()

        if (cleanText.isBlank() || listeningGroupId.isBlank()) {
            return
        }

        val selectedReply = _replyingTo.value
        val replyTo = selectedReply?.let { message ->
            ReplyTo(
                messageId = message.id,
                senderId = message.senderId,
                senderNickname = message.senderNickname,
                text = message.text
            )
        }

        val groupId = listeningGroupId
        val messageId = chatRepository.generateMessageId(groupId)

        if (messageId == null) {
            onResult(false, "Could not generate message ID.")
            return
        }

        val outgoingMessage = Message(
            id = messageId,
            senderId = currentUserId,
            senderNickname = senderNickname,
            text = cleanText,
            timestamp = System.currentTimeMillis(),
            replyTo = replyTo
        )

        _pendingMessageIds.value =
            _pendingMessageIds.value + messageId

        viewModelScope.launch {
            try {
                try {
                    chatRepository.cacheOutgoingMessage(
                        chatId = groupId,
                        message = outgoingMessage
                    )
                } catch (cacheError: Exception) {
                    // A local cache problem must not block Firebase delivery.
                    Log.e(
                        "GroupChatViewModel",
                        "Could not cache outgoing group message locally",
                        cacheError
                    )
                }

                groupRepository.sendMessage(
                    groupId = groupId,
                    messageId = messageId,
                    text = cleanText,
                    senderId = currentUserId,
                    senderNickname = senderNickname,
                    replyTo = replyTo
                ).onSuccess {
                    runCatching {
                        chatRepository.markOutgoingMessageSent(groupId, messageId)
                    }.onFailure { cacheError ->
                        Log.e(
                            "GroupChatViewModel",
                            "Group message sent but local pending state could not be cleared",
                            cacheError
                        )
                    }

                    if (
                        listeningGroupId == groupId &&
                        _replyingTo.value?.id == selectedReply?.id
                    ) {
                        _replyingTo.value = null
                    }

                    onResult(true, null)
                }.onFailure { error ->
                    runCatching {
                        chatRepository.removeFailedOutgoingMessage(groupId, messageId)
                    }.onFailure { cacheError ->
                        Log.e(
                            "GroupChatViewModel",
                            "Failed group message could not be removed from Room",
                            cacheError
                        )
                    }

                    onResult(
                        false,
                        error.message ?: "Could not send message."
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Leave the Room row marked pending. The next group session
                // will reconcile it with Firebase using the same message ID.
                throw e
            } catch (e: Exception) {
                runCatching {
                    chatRepository.removeFailedOutgoingMessage(groupId, messageId)
                }
                onResult(
                    false,
                    e.message ?: "Could not send message."
                )
            }
        }
    }

    fun editMessage(
        messageId: String,
        newText: String,
        currentUserId: String,
        onResult: (Boolean, String?) -> Unit
    ) {

        val editing = _editingMessage.value
        val cleanText = newText.trim()

        if (
            editing == null ||
            editing.id != messageId ||
            editing.senderId != currentUserId ||
            cleanText.isBlank() ||
            cleanText.length > 5000
        ) {
            onResult(false, "Invalid edit.")
            return
        }

        val editGroupId = listeningGroupId
        val editingMessageId = messageId

        viewModelScope.launch {
            chatRepository
                .editMessage(
                    chatId = editGroupId,
                    messageId = messageId,
                    userId = currentUserId,
                    newText = cleanText
                )
                .onSuccess {
                    if (
                        listeningGroupId == editGroupId &&
                        _editingMessage.value?.id == editingMessageId
                    ) {
                        _editingMessage.value = null
                        onResult(true, null)
                    }
                }
                .onFailure { error ->
                    if (listeningGroupId == editGroupId) {
                        onResult(
                            false,
                            error.message ?: "Could not edit message."
                        )
                    }
                }
        }
    }

    fun deleteMessagesForMe(
        messageIds: Set<String>,
        onResult: (Boolean, String?) -> Unit
    ) {
        val groupId = listeningGroupId
        if (groupId.isBlank() || messageIds.isEmpty()) {
            onResult(false, "No messages selected.")
            return
        }

        viewModelScope.launch {
            for (messageId in messageIds) {
                val result = chatRepository.deleteMessageForMe(groupId, messageId)
                if (result.isFailure) {
                    onResult(false, result.exceptionOrNull()?.message ?: "Could not delete message for you.")
                    return@launch
                }
            }
            onResult(true, null)
        }
    }

    fun deleteMessagesForEveryone(
        messageIds: Set<String>,
        currentUserId: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val groupId = listeningGroupId
        if (groupId.isBlank() || messageIds.isEmpty()) {
            onResult(false, "No messages selected.")
            return
        }
        val selectedMessages = _messages.value.filter { it.id in messageIds }
        if (
            selectedMessages.size != messageIds.size ||
            selectedMessages.any { it.senderId != currentUserId }
        ) {
            onResult(false, "You can only delete your own messages for everyone.")
            return
        }

        viewModelScope.launch {
            for (messageId in messageIds) {
                val result = chatRepository.deleteMessage(groupId, messageId)
                if (result.isFailure) {
                    onResult(false, result.exceptionOrNull()?.message ?: "Could not delete message for everyone.")
                    return@launch
                }
            }
            onResult(true, null)
        }
    }

    fun clearChatForMe(onResult: (Boolean, String?) -> Unit) {
        val groupId = listeningGroupId
        if (groupId.isBlank()) {
            onResult(false, "No group chat is open.")
            return
        }
        val pendingIds = _pendingMessageIds.value
        viewModelScope.launch {
            val result = chatRepository.clearChatForMe(groupId, pendingIds)
            onResult(result.isSuccess, result.exceptionOrNull()?.message)
        }
    }

    fun deleteMessage(
        messageId: String,
        onResult: (Boolean, String?) -> Unit
    ) {

        if (messageId.isBlank()) {
            onResult(false, "Invalid message.")
            return
        }

        viewModelScope.launch {
            chatRepository
                .deleteMessage(
                    chatId = listeningGroupId,
                    messageId = messageId
                )
                .onSuccess {
                    onResult(true, null)
                }
                .onFailure { error ->
                    onResult(
                        false,
                        error.message
                            ?: "Could not delete message."
                    )
                }
        }
    }

    fun markGroupRead(
        currentUserId: String,
        timestamp: Long
    ) {
        if (
            currentUserId.isBlank() ||
            listeningGroupId.isBlank() ||
            timestamp <= 0L
        ) {
            return
        }

        viewModelScope.launch {
            groupRepository.markGroupRead(
                currentUserId = currentUserId,
                groupId = listeningGroupId,
                timestamp = timestamp
            )
        }
    }

    fun loadOlderMessages() {
        val groupId = listeningGroupId
        if (groupId.isBlank() || !_hasOlderMessages.value ||
            _isLoadingOlderMessages.value
        ) return

        val oldestTimestamp = _messages.value
            .firstOrNull { it.timestamp > 0L }?.timestamp ?: return

        hasRequestedOlderMessages = true
        _isLoadingOlderMessages.value = true
        viewModelScope.launch {
            try {
                chatRepository.getOlderMessages(
                    chatId = groupId,
                    beforeTimestamp = oldestTimestamp
                ).onSuccess { olderMessages ->
                    if (listeningGroupId == groupId) {
                        _messages.value =
                            (olderMessages + _messages.value)
                                .distinctBy { it.id }
                                .sortedBy { it.timestamp }
                        _hasOlderMessages.value =
                            olderMessages.size >= ChatRepository.OLDER_MESSAGE_PAGE_SIZE
                    }
                }.onFailure { error ->
                    Log.e("GroupChatViewModel", "Could not load earlier group messages", error)
                }
            } finally {
                _isLoadingOlderMessages.value = false
            }
        }
    }

    fun toggleReaction(
        messageId: String,
        reaction: String,
        currentUserId: String
    ) {

        if (listeningGroupId.isBlank()) {
            return
        }

        viewModelScope.launch {
            chatRepository
                .toggleReaction(
                    chatId = listeningGroupId,
                    messageId = messageId,
                    userId = currentUserId,
                    reaction = reaction
                )
        }
    }

    override fun onCleared() {
        groupJob?.cancel()
        messagesJob?.cancel()
        pendingMessagesJob?.cancel()
        pendingReconciliationJob?.cancel()
        connectionListenerJob?.cancel()
        groupReadJobs.values.forEach { it.cancel() }
        groupReadJobs.clear()
        chatRepository.clearOlderMessagePages(listeningGroupId)
        super.onCleared()
    }
}