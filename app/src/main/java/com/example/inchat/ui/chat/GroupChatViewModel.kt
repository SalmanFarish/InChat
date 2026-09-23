package com.example.inchat.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.GroupChatRepository
import kotlinx.coroutines.Job
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

    private var groupJob: Job? = null
    private var messagesJob: Job? = null
    private var listeningGroupId = ""

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
        groupJob?.cancel()
        messagesJob?.cancel()

        _group.value = null
        _messages.value = emptyList()
        _messagesLoaded.value = false

        groupJob = viewModelScope.launch {
            try {
                groupRepository
                    .observeGroup(groupId)
                    .collect { group ->
                        _group.value = group

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

        val messageId =
            chatRepository.generateMessageId(listeningGroupId)

        if (messageId == null) {
            onResult(false, "Could not generate message ID.")
            return
        }

        _pendingMessageIds.value =
            _pendingMessageIds.value + messageId

        viewModelScope.launch {
            try {
                groupRepository
                    .sendMessage(
                        groupId = listeningGroupId,
                        messageId = messageId,
                        text = cleanText,
                        senderId = currentUserId,
                        senderNickname = senderNickname,
                        replyTo = replyTo
                    )
                    .onSuccess {
                        _pendingMessageIds.value =
                            _pendingMessageIds.value - messageId

                        if (
                            _replyingTo.value?.id ==
                            selectedReply?.id
                        ) {
                            _replyingTo.value = null
                        }

                        onResult(true, null)
                    }
                    .onFailure { error ->
                        _pendingMessageIds.value =
                            _pendingMessageIds.value - messageId
                        onResult(
                            false,
                            error.message
                                ?: "Could not send message."
                        )
                    }
            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {
                throw e
            } catch (e: Exception) {
                _pendingMessageIds.value =
                    _pendingMessageIds.value - messageId
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

        viewModelScope.launch {
            chatRepository
                .editMessage(
                    chatId = listeningGroupId,
                    messageId = messageId,
                    userId = currentUserId,
                    newText = cleanText
                )
                .onSuccess {
                    _editingMessage.value = null
                    onResult(true, null)
                }
                .onFailure { error ->
                    onResult(
                        false,
                        error.message
                            ?: "Could not edit message."
                    )
                }
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
        super.onCleared()
    }
}