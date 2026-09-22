package com.example.inchat.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.Presence
import com.example.inchat.data.model.ReplyTo
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.ModerationRepository
import com.example.inchat.data.repository.PresenceRepository
import com.example.inchat.data.repository.UserRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BlockState {
    NONE,
    I_BLOCKED_THEM,
    THEY_BLOCKED_ME
}

class ChatViewModel : ViewModel() {

    private val chatRepository =
        ChatRepository()

    private val moderationRepository =
        ModerationRepository()

    private val userRepository =
        UserRepository()

    private val database =
        FirebaseDatabase.getInstance()

    private val _messages =
        MutableStateFlow<List<Message>>(
            emptyList()
        )

    val messages:
            StateFlow<List<Message>> =
        _messages.asStateFlow()

    private val _messagesLoaded =
        MutableStateFlow(false)

    val messagesLoaded:
            StateFlow<Boolean> =
        _messagesLoaded.asStateFlow()

    private val _otherUserReadTimestamp =
        MutableStateFlow(0L)

    private val _readReceiptsEnabled =
        MutableStateFlow(true)

    val readReceiptsEnabled:
            StateFlow<Boolean> =
        _readReceiptsEnabled.asStateFlow()

    val otherUserReadTimestamp:
            StateFlow<Long> =
        _otherUserReadTimestamp
            .asStateFlow()

    private val _otherUserPresence =
        MutableStateFlow(
            Presence()
        )

    val otherUserPresence:
            StateFlow<Presence> =
        _otherUserPresence
            .asStateFlow()

    private val _otherUserTyping =
        MutableStateFlow(false)

    private val _typingIndicatorEnabled =
        MutableStateFlow(true)

    val typingIndicatorEnabled:
            StateFlow<Boolean> =
        _typingIndicatorEnabled.asStateFlow()

    val otherUserTyping:
            StateFlow<Boolean> =
        _otherUserTyping.asStateFlow()

    private val _blockState =
        MutableStateFlow(
            BlockState.NONE
        )

    val blockState:
            StateFlow<BlockState> =
        _blockState.asStateFlow()

    /*
     * =========================================================
     * CONNECTION STATE
     * =========================================================
     *
     * Firebase provides /.info/connected specifically for
     * knowing whether this client currently has a realtime
     * database connection.
     */
    private val _isConnected =
        MutableStateFlow(false)

    val isConnected:
            StateFlow<Boolean> =
        _isConnected.asStateFlow()

    private val _pendingMessageIds =
        MutableStateFlow<Set<String>>(
            emptySet()
        )

    val pendingMessageIds:
            StateFlow<Set<String>> =
        _pendingMessageIds.asStateFlow()

    private var connectionListenerJob:
            Job? = null

    /*
     * =========================================================
     * REPLY STATE
     * =========================================================
     */
    private val _replyingTo =
        MutableStateFlow<Message?>(
            null
        )

    val replyingTo:
            StateFlow<Message?> =
        _replyingTo.asStateFlow()

    /*
     * =========================================================
     * EDIT STATE
     * =========================================================
     */
    private val _editingMessage =
        MutableStateFlow<Message?>(
            null
        )

    val editingMessage:
            StateFlow<Message?> =
        _editingMessage.asStateFlow()

    private var listenerJob:
            Job? = null

    private var readListenerJob:
            Job? = null

    private var readReceiptPreferenceJob:
            Job? = null

    private var presenceJob:
            Job? = null

    private var typingListenerJob:
            Job? = null

    private var typingPreferenceJob:
            Job? = null

    private var typingResetJob:
            Job? = null

    private var blockListenerJob:
            Job? = null

    var currentChatId: String = ""
        private set

    private var initializedChatId =
        ""

    private var lastMarkedReadTimestamp =
        0L

    init {
        startConnectionListener()
    }

    /*
     * =========================================================
     * CONNECTION LISTENER
     * =========================================================
     */
    private fun startConnectionListener() {

        connectionListenerJob?.cancel()

        connectionListenerJob =
            viewModelScope.launch {

                val connectedRef =
                    database
                        .getReference(
                            ".info/connected"
                        )

                val listener =
                    object :
                        ValueEventListener {

                        override fun onDataChange(
                            snapshot:
                            DataSnapshot
                        ) {

                            _isConnected.value =
                                snapshot.getValue(
                                    Boolean::class.java
                                ) ?: false
                        }

                        override fun onCancelled(
                            error:
                            DatabaseError
                        ) {

                            Log.e(
                                "ChatViewModel",
                                "Connection listener cancelled",
                                error.toException()
                            )

                            _isConnected.value =
                                false
                        }
                    }

                connectedRef
                    .addValueEventListener(
                        listener
                    )

                try {

                    awaitCancellation()

                } finally {

                    connectedRef
                        .removeEventListener(
                            listener
                        )
                }
            }
    }

    /*
     * =========================================================
     * START LISTENING
     * =========================================================
     */
    fun startListening(
        currentUserId: String,
        otherUserId: String
    ) {

        if (
            currentUserId.isBlank() ||
            otherUserId.isBlank() ||
            currentUserId ==
            otherUserId
        ) {

            Log.e(
                "ChatViewModel",
                "Invalid participants"
            )

            return
        }

        val chatId =
            chatRepository.getChatRoomId(
                currentUserId,
                otherUserId
            )

        currentChatId =
            chatId

        if (
            initializedChatId ==
            chatId
        ) {

            return
        }

        initializedChatId =
            chatId

        /*
         * Reset chat-local state.
         *
         * Pending message IDs are intentionally NOT cleared.
         * Firebase may still be synchronizing a message while
         * the user navigates between conversations.
         */
        lastMarkedReadTimestamp =
            0L

        _messages.value =
            emptyList()

        _messagesLoaded.value =
            false

        _otherUserReadTimestamp.value =
            0L

        _readReceiptsEnabled.value =
            true

        _otherUserTyping.value =
            false

        _typingIndicatorEnabled.value =
            true

        _blockState.value =
            BlockState.NONE

        _replyingTo.value =
            null

        _editingMessage.value =
            null

        startMessageListener(
            chatId =
                chatId,

            currentUserId =
                currentUserId
        )

        startReadListener(
            chatId =
                chatId,

            otherUserId =
                otherUserId
        )

        startReadReceiptPreferenceListener(
            currentUserId =
                currentUserId
        )

        startPresenceListener(
            otherUserId =
                otherUserId
        )

        startTypingListener(
            chatId =
                chatId,

            otherUserId =
                otherUserId
        )

        startTypingPreferenceListener(
            currentUserId =
                currentUserId
        )

        startBlockListener(
            currentUserId =
                currentUserId,

            otherUserId =
                otherUserId
        )

        viewModelScope.launch {

            try {

                Log.d(
                    "ChatViewModel",
                    "Initializing chat: $chatId"
                )

                _readReceiptsEnabled.value =
                    userRepository
                        .getReadReceiptsVisible(
                            currentUserId
                        )

                _typingIndicatorEnabled.value =
                    userRepository
                        .getTypingIndicatorVisible(
                            currentUserId
                        )

                val result =
                    chatRepository.ensureChat(
                        currentUserId =
                            currentUserId,

                        otherUserId =
                            otherUserId
                    )

                if (
                    result.isFailure
                ) {

                    Log.e(
                        "ChatViewModel",
                        "ensureChat failed",
                        result.exceptionOrNull()
                    )

                    return@launch
                }

                Log.d(
                    "ChatViewModel",
                    "Chat initialized successfully: $chatId"
                )

                markConversationRead(
                    currentUserId
                )

            } catch (
                e:
                kotlinx.coroutines.CancellationException
            ) {

                throw e

            } catch (e: Exception) {

                Log.e(
                    "ChatViewModel",
                    "Unexpected chat initialization error",
                    e
                )
            }
        }
    }

    /*
     * =========================================================
     * MESSAGE LISTENER
     * =========================================================
     */
    private fun startMessageListener(
        chatId: String,
        currentUserId: String
    ) {

        listenerJob?.cancel()

        listenerJob =
            viewModelScope.launch {

                try {

                    chatRepository
                        .getMessagesFlow(
                            chatId
                        )
                        .collect { messageList ->

                            _messages.value =
                                messageList

                            _messagesLoaded.value =
                                true

                            val newestOtherMessage =
                                messageList
                                    .asSequence()
                                    .filter {
                                        it.senderId !=
                                                currentUserId
                                    }
                                    .maxOfOrNull {
                                        it.timestamp
                                    }
                                    ?: 0L

                            if (
                                newestOtherMessage > 0L &&
                                newestOtherMessage >
                                lastMarkedReadTimestamp
                            ) {

                                lastMarkedReadTimestamp =
                                    newestOtherMessage

                                markConversationRead(
                                    currentUserId
                                )
                            }

                            val currentEditingId =
                                _editingMessage
                                    .value
                                    ?.id

                            if (
                                currentEditingId != null &&
                                messageList.none {
                                    it.id ==
                                            currentEditingId
                                }
                            ) {

                                _editingMessage.value =
                                    null
                            }

                            Log.d(
                                "ChatViewModel",
                                "Messages received: " +
                                        messageList.size
                            )
                        }

                } catch (
                    e:
                    kotlinx.coroutines.CancellationException
                ) {

                    throw e

                } catch (e: Exception) {

                    Log.e(
                        "ChatViewModel",
                        "Message listener failed",
                        e
                    )

                    _messagesLoaded.value =
                        true
                }
            }
    }

    /*
     * =========================================================
     * READ RECEIPT LISTENER
     * =========================================================
     */
    private fun startReadListener(
        chatId: String,
        otherUserId: String
    ) {

        readListenerJob?.cancel()

        readListenerJob =
            viewModelScope.launch {

                try {

                    chatRepository
                        .getOtherUserReadTimestampFlow(
                            chatId =
                                chatId,

                            otherUserId =
                                otherUserId
                        )
                        .collect { timestamp ->

                            _otherUserReadTimestamp
                                .value =
                                timestamp
                        }

                } catch (
                    e:
                    kotlinx.coroutines.CancellationException
                ) {

                    throw e

                } catch (e: Exception) {

                    Log.e(
                        "ChatViewModel",
                        "Read listener failed",
                        e
                    )
                }
            }
    }

    /*
     * =========================================================
     * READ RECEIPT PREFERENCE LISTENER
     * =========================================================
     */
    private fun startReadReceiptPreferenceListener(
        currentUserId: String
    ) {

        readReceiptPreferenceJob?.cancel()

        readReceiptPreferenceJob =
            viewModelScope.launch {

                try {

                    userRepository
                        .observeReadReceiptsVisible(
                            currentUserId
                        )
                        .collect { enabled ->

                            _readReceiptsEnabled.value =
                                enabled

                            if (!enabled) {

                                markConversationRead(
                                    currentUserId
                                )
                            }
                        }

                } catch (
                    e:
                    kotlinx.coroutines.CancellationException
                ) {

                    throw e

                } catch (e: Exception) {

                    Log.e(
                        "ChatViewModel",
                        "Read receipt preference listener failed",
                        e
                    )
                }
            }
    }

    /*
     * =========================================================
     * TYPING PREFERENCE LISTENER
     * =========================================================
     */
    private fun startTypingPreferenceListener(
        currentUserId: String
    ) {

        typingPreferenceJob?.cancel()

        typingPreferenceJob =
            viewModelScope.launch {

                try {

                    userRepository
                        .observeTypingIndicatorVisible(
                            currentUserId
                        )
                        .collect { enabled ->

                            _typingIndicatorEnabled.value =
                                enabled

                            if (!enabled) {

                                typingResetJob?.cancel()

                                database
                                    .getReference(
                                        "typing"
                                    )
                                    .child(
                                        currentChatId
                                    )
                                    .child(
                                        currentUserId
                                    )
                                    .setValue(
                                        false
                                    )
                            }
                        }

                } catch (
                    e:
                    kotlinx.coroutines.CancellationException
                ) {

                    throw e

                } catch (e: Exception) {

                    Log.e(
                        "ChatViewModel",
                        "Typing preference listener failed",
                        e
                    )
                }
            }
    }

    /*
     * =========================================================
     * PRESENCE LISTENER
     * =========================================================
     */
    private fun startPresenceListener(
        otherUserId: String
    ) {

        presenceJob?.cancel()

        presenceJob =
            viewModelScope.launch {

                try {

                    PresenceRepository
                        .observePresence(
                            otherUserId
                        )
                        .collect { presence ->

                            _otherUserPresence
                                .value =
                                presence
                        }

                } catch (
                    e:
                    kotlinx.coroutines.CancellationException
                ) {

                    throw e

                } catch (e: Exception) {

                    Log.e(
                        "ChatViewModel",
                        "Presence listener failed",
                        e
                    )
                }
            }
    }

    /*
     * =========================================================
     * BLOCK LISTENER
     * =========================================================
     */
    private fun startBlockListener(
        currentUserId: String,
        otherUserId: String
    ) {

        blockListenerJob?.cancel()

        blockListenerJob =
            viewModelScope.launch {

                val myBlockRef =
                    database
                        .getReference(
                            "blockedUsers"
                        )
                        .child(
                            currentUserId
                        )
                        .child(
                            otherUserId
                        )

                val theirBlockRef =
                    database
                        .getReference(
                            "blockedUsers"
                        )
                        .child(
                            otherUserId
                        )
                        .child(
                            currentUserId
                        )

                myBlockRef.keepSynced(
                    true
                )

                theirBlockRef.keepSynced(
                    true
                )

                var iBlockedThem =
                    false

                var theyBlockedMe =
                    false

                fun updateBlockState() {

                    _blockState.value =
                        when {

                            iBlockedThem ->
                                BlockState.I_BLOCKED_THEM

                            theyBlockedMe ->
                                BlockState.THEY_BLOCKED_ME

                            else ->
                                BlockState.NONE
                        }

                    if (
                        iBlockedThem ||
                        theyBlockedMe
                    ) {

                        typingResetJob?.cancel()

                        _otherUserTyping.value =
                            false

                        _replyingTo.value =
                            null

                        _editingMessage.value =
                            null

                        database
                            .getReference(
                                "typing"
                            )
                            .child(
                                currentChatId
                            )
                            .child(
                                currentUserId
                            )
                            .setValue(
                                false
                            )
                    }
                }

                val myBlockListener =
                    object :
                        ValueEventListener {

                        override fun onDataChange(
                            snapshot:
                            DataSnapshot
                        ) {

                            iBlockedThem =
                                snapshot.exists()

                            updateBlockState()
                        }

                        override fun onCancelled(
                            error:
                            DatabaseError
                        ) {

                            Log.e(
                                "ChatViewModel",
                                "Own block listener cancelled",
                                error.toException()
                            )
                        }
                    }

                val theirBlockListener =
                    object :
                        ValueEventListener {

                        override fun onDataChange(
                            snapshot:
                            DataSnapshot
                        ) {

                            theyBlockedMe =
                                snapshot.exists()

                            updateBlockState()
                        }

                        override fun onCancelled(
                            error:
                            DatabaseError
                        ) {

                            Log.e(
                                "ChatViewModel",
                                "Incoming block listener cancelled",
                                error.toException()
                            )
                        }
                    }

                myBlockRef
                    .addValueEventListener(
                        myBlockListener
                    )

                theirBlockRef
                    .addValueEventListener(
                        theirBlockListener
                    )

                try {

                    awaitCancellation()

                } finally {

                    myBlockRef
                        .removeEventListener(
                            myBlockListener
                        )

                    theirBlockRef
                        .removeEventListener(
                            theirBlockListener
                        )
                }
            }
    }

    /*
     * =========================================================
     * TYPING LISTENER
     * =========================================================
     */
    private fun startTypingListener(
        chatId: String,
        otherUserId: String
    ) {

        typingListenerJob?.cancel()

        typingListenerJob =
            viewModelScope.launch {

                val typingRef =
                    database
                        .getReference(
                            "typing"
                        )
                        .child(
                            chatId
                        )
                        .child(
                            otherUserId
                        )

                typingRef.keepSynced(
                    true
                )

                val listener =
                    object :
                        ValueEventListener {

                        override fun onDataChange(
                            snapshot:
                            DataSnapshot
                        ) {

                            if (
                                _blockState.value !=
                                BlockState.NONE
                            ) {

                                _otherUserTyping.value =
                                    false

                                return
                            }

                            _otherUserTyping.value =
                                snapshot.getValue(
                                    Boolean::class.java
                                ) ?: false
                        }

                        override fun onCancelled(
                            error:
                            DatabaseError
                        ) {

                            Log.e(
                                "ChatViewModel",
                                "Typing listener cancelled",
                                error.toException()
                            )
                        }
                    }

                typingRef
                    .addValueEventListener(
                        listener
                    )

                try {

                    awaitCancellation()

                } finally {

                    typingRef
                        .removeEventListener(
                            listener
                        )
                }
            }
    }

    /*
     * =========================================================
     * REPLY SELECTION
     * =========================================================
     */
    fun setReplyingTo(
        message: Message
    ) {

        if (
            _blockState.value !=
            BlockState.NONE
        ) {

            return
        }

        if (
            _editingMessage.value != null
        ) {

            return
        }

        if (
            message.id.isBlank() ||
            message.senderId.isBlank() ||
            message.senderNickname.isBlank() ||
            message.text.isBlank()
        ) {

            return
        }

        _replyingTo.value =
            message
    }

    fun clearReplyingTo() {

        _replyingTo.value =
            null
    }

    /*
     * =========================================================
     * EDIT SELECTION
     * =========================================================
     */
    fun setEditingMessage(
        message: Message,
        currentUserId: String
    ) {

        if (
            _blockState.value !=
            BlockState.NONE
        ) {

            return
        }

        if (
            currentUserId.isBlank()
        ) {

            return
        }

        if (
            message.id.isBlank() ||
            message.text.isBlank()
        ) {

            return
        }

        if (
            message.senderId !=
            currentUserId
        ) {

            return
        }

        _replyingTo.value =
            null

        _editingMessage.value =
            message
    }

    fun clearEditingMessage() {

        _editingMessage.value =
            null
    }

    /*
     * =========================================================
     * EDIT MESSAGE
     * =========================================================
     */
    fun editMessage(
        messageId: String,
        newText: String,
        currentUserId: String,
        onResult:
            (Boolean, String?) -> Unit
    ) {

        val cleanText =
            newText.trim()

        if (
            currentChatId.isBlank() ||
            messageId.isBlank() ||
            currentUserId.isBlank()
        ) {

            onResult(
                false,
                "Invalid message data"
            )

            return
        }

        if (
            cleanText.isBlank()
        ) {

            onResult(
                false,
                "Message cannot be empty"
            )

            return
        }

        if (
            cleanText.length > 5000
        ) {

            onResult(
                false,
                "Message is too long"
            )

            return
        }

        val editingMessage =
            _editingMessage.value

        if (
            editingMessage == null ||
            editingMessage.id != messageId
        ) {

            onResult(
                false,
                "No message is being edited"
            )

            return
        }

        if (
            editingMessage.senderId !=
            currentUserId
        ) {

            onResult(
                false,
                "You can only edit your own messages"
            )

            return
        }

        viewModelScope.launch {

            try {

                chatRepository
                    .editMessage(
                        chatId =
                            currentChatId,

                        messageId =
                            messageId,

                        userId =
                            currentUserId,

                        newText =
                            cleanText
                    )
                    .onSuccess {

                        _editingMessage.value =
                            null

                        onResult(
                            true,
                            null
                        )

                        Log.d(
                            "ChatViewModel",
                            "Message edited successfully: $messageId"
                        )
                    }
                    .onFailure { error ->

                        Log.e(
                            "ChatViewModel",
                            "Message edit failed",
                            error
                        )

                        onResult(
                            false,
                            error.message
                                ?: "Could not edit message"
                        )
                    }

            } catch (
                e:
                kotlinx.coroutines.CancellationException
            ) {

                throw e

            } catch (e: Exception) {

                Log.e(
                    "ChatViewModel",
                    "Unexpected edit error",
                    e
                )

                onResult(
                    false,
                    e.message
                        ?: "Could not edit message"
                )
            }
        }
    }

    /*
     * =========================================================
     * REACTIONS
     * =========================================================
     */
    fun toggleReaction(
        messageId: String,
        reaction: String,
        currentUserId: String,
        onResult:
        ((Boolean, String?) -> Unit)? = null
    ) {

        if (
            currentChatId.isBlank() ||
            messageId.isBlank() ||
            currentUserId.isBlank() ||
            reaction.isBlank()
        ) {

            onResult?.invoke(
                false,
                "Invalid reaction data"
            )

            return
        }

        if (
            _blockState.value !=
            BlockState.NONE
        ) {

            onResult?.invoke(
                false,
                "You cannot react while blocked."
            )

            return
        }

        viewModelScope.launch {

            try {

                val result =
                    chatRepository
                        .toggleReaction(
                            chatId =
                                currentChatId,

                            messageId =
                                messageId,

                            userId =
                                currentUserId,

                            reaction =
                                reaction
                        )

                result
                    .onSuccess { isActive ->

                        Log.d(
                            "ChatViewModel",
                            if (
                                isActive
                            ) {
                                "Reaction added: $reaction"
                            } else {
                                "Reaction removed"
                            }
                        )

                        onResult?.invoke(
                            isActive,
                            null
                        )
                    }
                    .onFailure { error ->

                        Log.e(
                            "ChatViewModel",
                            "Reaction update failed",
                            error
                        )

                        onResult?.invoke(
                            false,
                            error.message
                                ?: "Could not update reaction"
                        )
                    }

            } catch (
                e:
                kotlinx.coroutines.CancellationException
            ) {

                throw e

            } catch (e: Exception) {

                Log.e(
                    "ChatViewModel",
                    "Unexpected reaction error",
                    e
                )

                onResult?.invoke(
                    false,
                    e.message
                        ?: "Could not update reaction"
                )
            }
        }
    }

    private fun isTypingIndicatorEnabled(): Boolean =
        _typingIndicatorEnabled.value

    /*
     * =========================================================
     * TYPING
     * =========================================================
     */
    fun onTypingChanged(
        currentUserId: String,
        isTyping: Boolean
    ) {

        if (
            currentChatId.isBlank() ||
            currentUserId.isBlank() ||
            _blockState.value !=
            BlockState.NONE
        ) {

            return
        }

        if (
            _editingMessage.value != null
        ) {

            return
        }

        if (!isTypingIndicatorEnabled()) {

            typingResetJob?.cancel()

            database
                .getReference(
                    "typing"
                )
                .child(
                    currentChatId
                )
                .child(
                    currentUserId
                )
                .setValue(
                    false
                )

            return
        }

        val typingRef =
            database
                .getReference(
                    "typing"
                )
                .child(
                    currentChatId
                )
                .child(
                    currentUserId
                )

        typingResetJob?.cancel()

        if (
            !isTyping
        ) {

            typingRef.setValue(
                false
            )

            return
        }

        typingRef.setValue(
            true
        )

        typingResetJob =
            viewModelScope.launch {

                delay(
                    1500
                )

                if (
                    _blockState.value ==
                    BlockState.NONE &&
                    _editingMessage.value == null
                ) {

                    typingRef.setValue(
                        false
                    )
                }
            }
    }

    /*
     * =========================================================
     * BLOCK USER
     * =========================================================
     */
    fun blockUser(
        currentUserId: String,
        otherUserId: String,
        otherUsername: String,
        onResult:
            (Boolean, String?) -> Unit
    ) {

        if (
            currentUserId.isBlank() ||
            otherUserId.isBlank()
        ) {

            onResult(
                false,
                "Invalid user"
            )

            return
        }

        viewModelScope.launch {

            moderationRepository
                .blockUser(
                    currentUserId =
                        currentUserId,

                    userIdToBlock =
                        otherUserId,

                    usernameToBlock =
                        otherUsername
                )
                .onSuccess {

                    _blockState.value =
                        BlockState.I_BLOCKED_THEM

                    _otherUserTyping.value =
                        false

                    _replyingTo.value =
                        null

                    _editingMessage.value =
                        null

                    onTypingChanged(
                        currentUserId =
                            currentUserId,

                        isTyping =
                            false
                    )

                    onResult(
                        true,
                        null
                    )
                }
                .onFailure { error ->

                    Log.e(
                        "ChatViewModel",
                        "Block user failed",
                        error
                    )

                    onResult(
                        false,
                        error.message
                            ?: "Could not block user"
                    )
                }
        }
    }

    /*
     * =========================================================
     * UNBLOCK USER
     * =========================================================
     */
    fun unblockUser(
        currentUserId: String,
        otherUserId: String,
        onResult:
            (Boolean, String?) -> Unit
    ) {

        viewModelScope.launch {

            moderationRepository
                .unblockUser(
                    currentUserId =
                        currentUserId,

                    userIdToUnblock =
                        otherUserId
                )
                .onSuccess {

                    _blockState.value =
                        BlockState.NONE

                    onResult(
                        true,
                        null
                    )
                }
                .onFailure { error ->

                    Log.e(
                        "ChatViewModel",
                        "Unblock user failed",
                        error
                    )

                    onResult(
                        false,
                        error.message
                            ?: "Could not unblock user"
                    )
                }
        }
    }

    /*
     * =========================================================
     * REPORT USER
     * =========================================================
     */
    fun reportUser(
        reporterId: String,
        reportedUserId: String,
        reportedUsername: String,
        reason: String,
        onResult:
            (Boolean, String?) -> Unit
    ) {

        viewModelScope.launch {

            moderationRepository
                .reportUser(
                    reporterId =
                        reporterId,

                    reportedUserId =
                        reportedUserId,

                    reportedUsername =
                        reportedUsername,

                    reason =
                        reason
                )
                .onSuccess {

                    onResult(
                        true,
                        null
                    )
                }
                .onFailure { error ->

                    Log.e(
                        "ChatViewModel",
                        "Report user failed",
                        error
                    )

                    onResult(
                        false,
                        error.message
                            ?: "Could not submit report"
                    )
                }
        }
    }

    /*
     * =========================================================
     * MARK CONVERSATION READ
     * =========================================================
     */
    fun markConversationRead(
        currentUserId: String
    ) {

        if (
            currentUserId.isBlank() ||
            currentChatId.isBlank() ||
            _blockState.value !=
            BlockState.NONE
        ) {

            return
        }

        viewModelScope.launch {

            chatRepository
                .markConversationRead(
                    currentUserId =
                        currentUserId,

                    chatId =
                        currentChatId,

                    sendReadReceipt =
                        _readReceiptsEnabled.value
                )
                .onFailure { error ->

                    Log.e(
                        "ChatViewModel",
                        "Mark read failed",
                        error
                    )
                }
        }
    }

    /*
     * =========================================================
     * SEND MESSAGE
     * =========================================================
     *
     * Important:
     *
     * The pending ID is added BEFORE the Firebase write starts.
     *
     * Firebase Realtime Database triggers local listener events
     * immediately, so the new message can appear in the UI even
     * while the actual server write is still waiting.
     *
     * The coroutine remains suspended until Firebase confirms the
     * write. At that moment the message becomes "Sent".
     */
    fun sendMessage(
        text: String,
        currentUserId: String,
        senderNickname: String,
        otherUserId: String,
        otherUserNickname: String
    ) {

        val cleanText =
            text.trim()

        if (
            cleanText.isEmpty()
        ) {

            return
        }

        if (
            currentChatId.isEmpty() ||
            currentUserId.isBlank() ||
            otherUserId.isBlank()
        ) {

            return
        }

        if (
            _blockState.value !=
            BlockState.NONE
        ) {

            Log.d(
                "ChatViewModel",
                "Message prevented: blocked relationship"
            )

            return
        }

        if (
            _editingMessage.value != null
        ) {

            Log.d(
                "ChatViewModel",
                "Normal send ignored while editing a message"
            )

            return
        }

        val selectedReply =
            _replyingTo.value

        val replyTo =
            selectedReply?.let { message ->

                ReplyTo(

                    messageId =
                        message.id,

                    senderId =
                        message.senderId,

                    senderNickname =
                        message.senderNickname,

                    text =
                        message.text
                )
            }

        onTypingChanged(
            currentUserId =
                currentUserId,

            isTyping =
                false
        )

        val messageId =
            chatRepository
                .generateMessageId(
                    currentChatId
                )

        if (
            messageId == null
        ) {

            Log.e(
                "ChatViewModel",
                "Could not generate message ID"
            )

            return
        }

        /*
         * Mark as pending before Firebase receives the write.
         */
        _pendingMessageIds.value =
            _pendingMessageIds.value +
                    messageId

        viewModelScope.launch {

            try {

                chatRepository
                    .sendMessage(

                        chatId =
                            currentChatId,

                        messageId =
                            messageId,

                        text =
                            cleanText,

                        senderId =
                            currentUserId,

                        senderNickname =
                            senderNickname,

                        receiverId =
                            otherUserId,

                        receiverNickname =
                            otherUserNickname,

                        replyTo =
                            replyTo
                    )
                    .onSuccess {

                        /*
                         * Firebase has confirmed the write.
                         */
                        _pendingMessageIds.value =
                            _pendingMessageIds.value -
                                    messageId

                        if (
                            _replyingTo.value?.id ==
                            selectedReply?.id
                        ) {

                            _replyingTo.value =
                                null
                        }

                        Log.d(
                            "ChatViewModel",
                            "Message sent successfully: $messageId"
                        )
                    }
                    .onFailure { error ->

                        /*
                         * The write failed.
                         *
                         * Firebase will roll back the local
                         * optimistic event if the server rejected
                         * the operation.
                         */
                        _pendingMessageIds.value =
                            _pendingMessageIds.value -
                                    messageId

                        Log.e(
                            "ChatViewModel",
                            "Message write failed",
                            error
                        )
                    }

            } catch (
                e:
                kotlinx.coroutines.CancellationException
            ) {

                _pendingMessageIds.value =
                    _pendingMessageIds.value -
                            messageId

                throw e

            } catch (e: Exception) {

                _pendingMessageIds.value =
                    _pendingMessageIds.value -
                            messageId

                Log.e(
                    "ChatViewModel",
                    "Unexpected send error",
                    e
                )
            }
        }
    }

    /*
     * =========================================================
     * DELETE MESSAGE
     * =========================================================
     */
    fun deleteMessage(
        messageId: String
    ) {

        if (
            currentChatId.isEmpty() ||
            messageId.isEmpty()
        ) {

            return
        }

        if (
            _replyingTo.value?.id ==
            messageId
        ) {

            _replyingTo.value =
                null
        }

        if (
            _editingMessage.value?.id ==
            messageId
        ) {

            _editingMessage.value =
                null
        }

        _pendingMessageIds.value =
            _pendingMessageIds.value -
                    messageId

        viewModelScope.launch {

            try {

                chatRepository
                    .deleteMessage(
                        currentChatId,
                        messageId
                    )
                    .onSuccess {

                        Log.d(
                            "ChatViewModel",
                            "Message deleted successfully"
                        )
                    }
                    .onFailure { error ->

                        Log.e(
                            "ChatViewModel",
                            "DELETE MESSAGE FAILED",
                            error
                        )
                    }

            } catch (e: Exception) {

                Log.e(
                    "ChatViewModel",
                    "Unexpected delete error",
                    e
                )
            }
        }
    }

    /*
     * =========================================================
     * CLEAR CHAT STATE
     * =========================================================
     */
    fun clearChatState() {

        _replyingTo.value =
            null

        _editingMessage.value =
            null

        _otherUserTyping.value =
            false
    }

    /*
     * =========================================================
     * CLEANUP
     * =========================================================
     */
    override fun onCleared() {

        connectionListenerJob?.cancel()

        listenerJob?.cancel()
        readListenerJob?.cancel()
        presenceJob?.cancel()
        typingListenerJob?.cancel()
        typingPreferenceJob?.cancel()
        typingResetJob?.cancel()
        blockListenerJob?.cancel()

        super.onCleared()
    }
}