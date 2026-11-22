package com.example.inchat.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.InChatApplication
import com.example.inchat.crypto.DecryptedGroupMessage
import com.example.inchat.crypto.EncryptedGroupMessageRepository
import com.example.inchat.crypto.MegolmGroupMessageSendCoordinatorFactory
import com.example.inchat.crypto.MegolmGroupMessageSendOutcome
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.GroupChatRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupChatViewModel : ViewModel() {

    private val groupRepository = GroupChatRepository()
    private val chatRepository = ChatRepository()
    private val localDatabase by lazy {
        LocalChatDatabase.getInstance(InChatApplication.instance)
    }
    private val encryptedGroupMessageRepository by lazy {
        EncryptedGroupMessageRepository(localDatabase.encryptedGroupMessageDao())
    }
    private val encryptedGroupMessageSendCoordinator by lazy {
        MegolmGroupMessageSendCoordinatorFactory(
            context = InChatApplication.instance,
            database = localDatabase,
            groupRepository = groupRepository,
        ).create()
    }

    private val _group = MutableStateFlow<Group?>(null)
    val group: StateFlow<Group?> = _group.asStateFlow()

    private val _messages =
        MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> =
        _messages.asStateFlow()

    // Kept separate from legacy Message rows so encrypted content can never
    // accidentally enter Firebase edit/delete/reaction or retry operations.
    private val _encryptedMessages = MutableStateFlow<List<DecryptedGroupMessage>>(emptyList())
    internal val encryptedMessages: StateFlow<List<DecryptedGroupMessage>> =
        _encryptedMessages.asStateFlow()

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
    private var encryptedMessagesJob: Job? = null
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
        encryptedMessagesJob?.cancel()
        _encryptedMessages.value = emptyList()
        pendingMessagesJob?.cancel()
        pendingReconciliationJob?.cancel()
        pendingMessagesJob = null
        pendingReconciliationJob = null
        _pendingMessageIds.value = emptySet()
        groupReadJobs.values.forEach { it.cancel() }
        groupReadJobs.clear()
        _groupReadTimestamps.value = emptyMap()

        _group.value = null

        // Group sends now use the ciphertext-only Olm outbox. Do not
        // restore or reconcile legacy plaintext pending rows here: that retry
        // path writes to chats/{groupId}/messages and would bypass Megolm.
        _pendingMessageIds.value = emptySet()

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

        // Encrypted Megolm messages live only in account-scoped Room storage.
        // Their visibility is continuously gated by the Worker-authored atomic
        // membership snapshot; legacy /members listeners are never consulted.
        encryptedMessagesJob = viewModelScope.launch {
            try {
                encryptedGroupMessageRepository
                    .observeMessagesForMember(
                        ownerUserId = currentUserId,
                        groupId = groupId,
                        membershipSnapshots = combine(
                            groupRepository.observeGroupCryptoMembership(groupId),
                            observeAuthenticatedUserId(),
                        ) { membership, authenticatedUserId ->
                            if (authenticatedUserId == currentUserId) membership else null
                        },
                    )
                    .collect { encrypted ->
                        if (listeningGroupId == groupId) {
                            _encryptedMessages.value = encrypted
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (listeningGroupId == groupId) {
                    _encryptedMessages.value = emptyList()
                }
                Log.e(
                    "GroupChatViewModel",
                    "Encrypted group message timeline could not be observed",
                    e,
                )
            }
        }

        messagesJob = viewModelScope.launch {
            try {
                chatRepository
                    .getMessagesFlow(groupId)
                    .distinctUntilChanged()
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
        onQueued: () -> Unit = {},
        onResult: (Boolean, String?) -> Unit
    ) {
        val cleanText = text.trim()
        val groupId = listeningGroupId
        if (cleanText.isBlank() || groupId.isBlank()) {
            onResult(false, "No group chat is open or the message is empty.")
            return
        }
        if (cleanText.length > 5000) {
            onResult(false, "Message is too long.")
            return
        }
        if (FirebaseAuth.getInstance().currentUser?.uid != currentUserId) {
            onResult(false, "The signed-in account changed. Reopen the group and try again.")
            return
        }
        if (_replyingTo.value != null) {
            onResult(false, "Replies in encrypted group chats are not supported yet.")
            return
        }

        val messageId = chatRepository.generateMessageId(groupId)
        if (messageId == null) {
            onResult(false, "Could not generate message ID.")
            return
        }
        val sentAtMillis = System.currentTimeMillis()

        // Group content is sent only through Megolm. In particular, do not
        // create a plaintext Room pending row: the legacy retry worker would
        // otherwise be able to upload it to chats/{groupId}/messages.
        viewModelScope.launch {
            try {
                val membership = groupRepository.getGroupCryptoMembershipSnapshot(groupId)
                if (membership == null || !membership.isValid() ||
                    membership.groupId != groupId ||
                    currentUserId !in membership.memberUserIds
                ) {
                    onResult(
                        false,
                        "This group has no valid encrypted membership yet. Group messages are blocked until E2EE setup is complete."
                    )
                    return@launch
                }

                when (
                    val outcome = withContext(Dispatchers.IO) {
                        encryptedGroupMessageSendCoordinator.send(
                            groupId = groupId,
                            messageId = messageId,
                            plaintext = cleanText,
                            sentAtMillis = sentAtMillis,
                        )
                    }
                ) {
                    is MegolmGroupMessageSendOutcome.AwaitingKeyAcknowledgements -> {
                        onResult(
                            false,
                            "Waiting for encrypted group keys to be acknowledged by all trusted member devices. Retry this message after acknowledgements complete."
                        )
                    }

                    is MegolmGroupMessageSendOutcome.Queued -> {
                        val queued = outcome.result
                        // Keep the sender's own timeline copy in the isolated
                        // AES-GCM encrypted table; the legacy plaintext cache
                        // and Firebase chat path are never touched.
                        try {
                            encryptedGroupMessageRepository.saveOutgoingMessage(
                                DecryptedGroupMessage(
                                    ownerUserId = currentUserId,
                                    groupId = groupId,
                                    messageId = queued.messageId,
                                    membershipEpoch = queued.membershipEpoch,
                                    senderUserId = queued.senderUserId,
                                    senderDeviceId = queued.senderDeviceId,
                                    megolmSessionId = queued.megolmSessionId,
                                    messageIndex = queued.messageIndex,
                                    sentAtMillis = queued.sentAtMillis,
                                    text = cleanText,
                                )
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (localCopyError: Exception) {
                            Log.e(
                                "GroupChatViewModel",
                                "Encrypted group message is queued, but its local timeline copy could not be saved.",
                                localCopyError,
                            )
                        }
                        if (listeningGroupId == groupId) {
                            _replyingTo.value = null
                        }
                        // The coordinator returns only after ciphertext-only
                        // per-device deliveries are durably committed to Room.
                        onQueued()
                        onResult(true, null)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "GroupChatViewModel",
                    "Encrypted group message was not queued; plaintext fallback is disabled.",
                    e,
                )
                onResult(
                    false,
                    e.message ?: "Encrypted group message could not be queued.",
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
                            // The Firebase page ends strictly before the
                            // oldest loaded timestamp, so these IDs cannot overlap.
                            olderMessages + _messages.value
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

    private fun observeAuthenticatedUserId(): Flow<String?> = callbackFlow {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override fun onCleared() {
        groupJob?.cancel()
        messagesJob?.cancel()
        encryptedMessagesJob?.cancel()
        pendingMessagesJob?.cancel()
        pendingReconciliationJob?.cancel()
        connectionListenerJob?.cancel()
        groupReadJobs.values.forEach { it.cancel() }
        groupReadJobs.clear()
        chatRepository.clearOlderMessagePages(listeningGroupId)
        super.onCleared()
    }
}