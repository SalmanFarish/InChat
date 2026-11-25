package com.example.inchat.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.InChatApplication
import com.example.inchat.crypto.DecryptedOlmMessage
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.DeviceKeyDirectoryRepository
import com.example.inchat.crypto.EncryptedMessageDeliveryStatus
import com.example.inchat.crypto.EncryptedMessageDeliveryStatusRepository
import com.example.inchat.crypto.EncryptedOlmMessageRepository
import com.example.inchat.crypto.EncryptedDirectPendingMessageRepository
import com.example.inchat.crypto.OlmDeliveryQueue
import com.example.inchat.crypto.OlmDirectMessageSendCoordinatorFactory
import com.example.inchat.crypto.RoomOlmDeliveryQueueStore
import com.example.inchat.crypto.TrustedDeviceRepository
import com.example.inchat.crypto.toDelivery
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.worker.EncryptedOlmDeliveryWorker
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.Presence
import com.example.inchat.data.model.ReplyTo
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.ModerationRepository
import com.example.inchat.data.repository.PresenceRepository
import com.example.inchat.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BlockState {
    NONE,
    I_BLOCKED_THEM,
    THEY_BLOCKED_ME
}

class ChatViewModel : ViewModel() {

    private val chatRepository =
        ChatRepository()

    // Incoming Olm messages are persisted separately from the legacy plaintext
    // cache. Observe that encrypted store directly so authenticated E2EE messages
    // can appear in the same conversation timeline without being copied to it.
    private val localChatDatabase by lazy {
        LocalChatDatabase.getInstance(InChatApplication.instance)
    }

    private val encryptedOlmMessageRepository by lazy {
        EncryptedOlmMessageRepository(localChatDatabase.encryptedOlmMessageDao())
    }

    private val encryptedDirectPendingMessageRepository by lazy {
        EncryptedDirectPendingMessageRepository(localChatDatabase.encryptedDirectPendingMessageDao())
    }

    private val encryptedMessageDeliveryStatusRepository by lazy {
        EncryptedMessageDeliveryStatusRepository(
            queueDao = localChatDatabase.olmDeliveryQueueDao(),
            receiptDao = localChatDatabase.olmDeliveryReceiptDao(),
        )
    }

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

    // IDs currently sourced from the authenticated encrypted Room timeline.
    // Feature handlers use this to avoid sending those IDs to plaintext RTDB APIs.
    private val _encryptedMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val encryptedMessageIds: StateFlow<Set<String>> =
        _encryptedMessageIds.asStateFlow()

    private val _encryptedDeliveryStatuses =
        MutableStateFlow<Map<String, EncryptedMessageDeliveryStatus>>(emptyMap())
    val encryptedDeliveryStatuses: StateFlow<Map<String, EncryptedMessageDeliveryStatus>> =
        _encryptedDeliveryStatuses.asStateFlow()

    fun isEncryptedMessageForActions(messageId: String): Boolean =
        messageId.isNotBlank() && (
            messageId in _encryptedMessageIds.value ||
                messageId in _pendingDirectMessageIds.value
            )

    private val _messagesLoaded =
        MutableStateFlow(false)

    val messagesLoaded:
            StateFlow<Boolean> =
        _messagesLoaded.asStateFlow()

    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()

    private val _isLoadingOlderMessages = MutableStateFlow(false)
    val isLoadingOlderMessages: StateFlow<Boolean> =
        _isLoadingOlderMessages.asStateFlow()

    private var hasRequestedOlderMessages = false
    private var isChatVisible = false
    private var activeTypingUserId = ""

    private val _chatReady =
        MutableStateFlow(false)

    val chatReady: StateFlow<Boolean> =
        _chatReady.asStateFlow()

    private val _chatInitializationError =
        MutableStateFlow<String?>(null)

    val chatInitializationError: StateFlow<String?> =
        _chatInitializationError.asStateFlow()

    // A ready flag must be tied to the conversation that was initialized.
    private val _readyChatId = MutableStateFlow("")

    val readyChatId: StateFlow<String> =
        _readyChatId.asStateFlow()

    private val _otherUserDeliveredTimestamp =
        MutableStateFlow(0L)

    val otherUserDeliveredTimestamp: StateFlow<Long> =
        _otherUserDeliveredTimestamp.asStateFlow()

    private val _otherUserReadTimestamp = MutableStateFlow(0L)

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

    private val _legacyPendingMessageIds = MutableStateFlow<Set<String>>(emptySet())
    private val _pendingDirectMessageIds = MutableStateFlow<Set<String>>(emptySet())
    private val _pendingMessageIds = MutableStateFlow<Set<String>>(emptySet())

    val pendingMessageIds: StateFlow<Set<String>> = _pendingMessageIds.asStateFlow()

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

    private var initializationJob:
            Job? = null

    private var deliveryListenerJob:
            Job? = null

    private var listenerJob:
            Job? = null

    private var encryptedDeliveryStatusJob: Job? = null

    private var encryptedTrustRetryJob: Job? = null

    private var localMessagesJob:
            Job? = null

    private var pendingMessagesJob:
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

    private var initializingChatId =
        ""

    private var lastMarkedReadTimestamp =
        0L

    // Avoid writing the same delivered cursor on every Room/Firebase emission.
    private var lastMarkedDeliveredTimestamp =
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
        otherUserId: String,
        otherUserNickname: String = "User"
    ) {
        if (
            currentUserId.isBlank() ||
            otherUserId.isBlank() ||
            currentUserId == otherUserId
        ) {
            Log.e(
                "ChatViewModel",
                "Invalid participants"
            )
            _chatReady.value = false
            _readyChatId.value = ""
            _chatInitializationError.value = "Invalid chat participants."
            return
        }

        val chatId =
            chatRepository.getChatRoomId(
                currentUserId,
                otherUserId
            )

        if (
            initializedChatId == chatId ||
            initializingChatId == chatId
        ) {
            return
        }

        if (currentChatId.isNotBlank() && activeTypingUserId.isNotBlank()) {
            clearTypingState(activeTypingUserId, currentChatId)
        }
        hasRequestedOlderMessages = false
        _hasOlderMessages.value = false
        _isLoadingOlderMessages.value = false

        // Stop any previous conversation listeners before switching chats.
        initializationJob?.cancel()
        listenerJob?.cancel()
        encryptedDeliveryStatusJob?.cancel()
        encryptedTrustRetryJob?.cancel()
        _encryptedDeliveryStatuses.value = emptyMap()
        localMessagesJob?.cancel()
        pendingMessagesJob?.cancel()
        deliveryListenerJob?.cancel()
        readListenerJob?.cancel()
        readReceiptPreferenceJob?.cancel()
        presenceJob?.cancel()
        typingListenerJob?.cancel()
        typingPreferenceJob?.cancel()
        typingResetJob?.cancel()
        blockListenerJob?.cancel()

        currentChatId = chatId
        _encryptedMessageIds.value = emptySet()
        _encryptedDeliveryStatuses.value = emptyMap()
        _legacyPendingMessageIds.value = emptySet()
        _pendingDirectMessageIds.value = emptySet()
        _pendingMessageIds.value = emptySet()

        // Restore the per-chat pending indicators from the legacy and encrypted local outboxes.
        pendingMessagesJob = viewModelScope.launch {
            try {
                chatRepository.observePendingMessageIds(chatId).collect { pendingIds ->
                    if (currentChatId == chatId) {
                        _legacyPendingMessageIds.value = pendingIds
                        _pendingMessageIds.value = pendingIds + _pendingDirectMessageIds.value
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Pending message state could not be observed", e)
            }
        }

        initializedChatId = ""
        initializingChatId = chatId
        _chatReady.value = false
        _readyChatId.value = ""
        _chatInitializationError.value = null

        lastMarkedReadTimestamp = 0L
        lastMarkedDeliveredTimestamp = 0L

        // Populate from the last in-memory snapshot before waiting for
        // chat initialization or the next Firebase listener callback.
        val cachedMessages = chatRepository.getCachedMessages(chatId)
        _messages.value = cachedMessages ?: emptyList()
        _messagesLoaded.value = cachedMessages != null
        _hasOlderMessages.value = false

        // Read Room immediately, independently of the Firebase chat verification.
        localMessagesJob = viewModelScope.launch {
            try {
                chatRepository.getLocalMessagesFlow(chatId).collect { localMessages ->
                    if (currentChatId == chatId) {
                        _messages.value = localMessages
                        _messagesLoaded.value = true
                        if (!hasRequestedOlderMessages) {
                            _hasOlderMessages.value = false
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Local message cache could not be observed", e)
            }
        }

        _otherUserDeliveredTimestamp.value = 0L
        _otherUserReadTimestamp.value = 0L
        _readReceiptsEnabled.value = true
        _otherUserTyping.value = false
        _typingIndicatorEnabled.value = true
        _otherUserPresence.value = Presence()
        _blockState.value = BlockState.NONE
        _replyingTo.value = null
        _editingMessage.value = null

        /*
         * Create/confirm the direct-chat record BEFORE starting
         * listeners that are protected by participant-based RTDB rules.
         * Otherwise the first theme/message reads can be denied while
         * the chat node does not exist yet.
         */
        initializationJob =
            viewModelScope.launch {
                try {
                    Log.d(
                        "ChatViewModel",
                        "Ensuring direct chat exists: $chatId"
                    )

                    val result =
                        chatRepository.ensureChat(
                            currentUserId = currentUserId,
                            otherUserId = otherUserId
                        )

                    if (result.isFailure) {
                        throw result.exceptionOrNull()
                            ?: IllegalStateException(
                                "Could not initialize this conversation."
                            )
                    }

                    if (initializingChatId != chatId) {
                        return@launch
                    }

                    // The Firebase chat node now exists, so participant-only
                    // message, receipt, typing and theme reads are authorized.
                    initializedChatId = chatId
                    initializingChatId = ""

                    // Preference listeners below deliver these settings asynchronously.
                    // They must not delay the chat becoming send-ready.

                    startMessageListener(
                        chatId = chatId,
                        currentUserId = currentUserId,
                        otherUserId = otherUserId,
                        otherUserNickname = otherUserNickname
                    )

                    // Encrypted Room outbox workers own delivery recovery.
                    startDeliveryListener(
                        chatId = chatId,
                        otherUserId = otherUserId
                    )

                    startReadListener(
                        chatId = chatId,
                        otherUserId = otherUserId
                    )

                    startReadReceiptPreferenceListener(
                        currentUserId = currentUserId
                    )

                    startPresenceListener(
                        otherUserId = otherUserId
                    )

                    startTypingListener(
                        chatId = chatId,
                        otherUserId = otherUserId
                    )

                    startTypingPreferenceListener(
                        currentUserId = currentUserId
                    )

                    startBlockListener(
                        currentUserId = currentUserId,
                        otherUserId = otherUserId
                    )

                    _readyChatId.value = chatId
                    _chatReady.value = true

                    if (isChatVisible) {
                        markConversationRead(currentUserId)
                    }

                    Log.d(
                        "ChatViewModel",
                        "Direct chat is ready: $chatId"
                    )
                } catch (
                    e: kotlinx.coroutines.CancellationException
                ) {
                    throw e
                } catch (e: Exception) {
                    Log.e(
                        "ChatViewModel",
                        "Direct chat initialization failed: $chatId",
                        e
                    )

                    if (initializingChatId == chatId) {
                        initializingChatId = ""
                    }

                    if (initializedChatId == chatId) {
                        initializedChatId = ""
                    }

                    if (_readyChatId.value == chatId) {
                        _readyChatId.value = ""
                    }
                    _chatReady.value = false
                    _chatInitializationError.value =
                        e.localizedMessage ?: "Could not initialize this conversation."
                    _messagesLoaded.value = true
                } finally {
                    if (initializingChatId == chatId) {
                        initializingChatId = ""
                    }
                }
            }
    }

    /*
     * =========================================================
     * MESSAGE LISTENER
     * =========================================================
     */
    private fun retryFailedDirectDeliveriesForTrustedDevices(
        ownerUserId: String,
        recipientUserId: String,
        conversationId: String,
    ) {
        if (
            currentChatId != conversationId ||
            FirebaseAuth.getInstance().currentUser?.uid != ownerUserId
        ) return

        viewModelScope.launch {
            try {
                val appContext = InChatApplication.instance
                val identityAndTrustedDevices = withContext(Dispatchers.IO) {
                    val senderDeviceId = DeviceIdentityRepository(appContext)
                        .existingDeviceIdForDelivery()
                        ?: return@withContext null
                    val trust = TrustedDeviceRepository(appContext)
                    val trustedDeviceIds = DeviceKeyDirectoryRepository()
                        .getUserDevices(recipientUserId)
                        .filter { device ->
                            trust.isVerified(
                                peerUserId = recipientUserId,
                                peerDeviceId = device.deviceId,
                                peerEd25519IdentityKey = device.ed25519IdentityKey,
                            )
                        }
                        .map { it.deviceId }
                    senderDeviceId to trustedDeviceIds
                } ?: return@launch

                val senderDeviceId = identityAndTrustedDevices.first
                val trustedDeviceIds = identityAndTrustedDevices.second
                if (
                    trustedDeviceIds.isEmpty() ||
                    currentChatId != conversationId ||
                    FirebaseAuth.getInstance().currentUser?.uid != ownerUserId
                ) return@launch

                val dao = localChatDatabase.olmDeliveryQueueDao()
                val queue = OlmDeliveryQueue(
                    RoomOlmDeliveryQueueStore(
                        database = localChatDatabase,
                        dao = dao,
                    ),
                )
                val retriedCount = withContext(Dispatchers.IO) {
                    val failed = dao.getFailedDirectDeliveriesForTrustedDevices(
                        ownerUserId = ownerUserId,
                        senderDeviceId = senderDeviceId,
                        recipientUserId = recipientUserId,
                        conversationId = conversationId,
                        trustedRecipientDeviceIds = trustedDeviceIds,
                    )
                    val now = System.currentTimeMillis()
                    failed.count { delivery ->
                        queue.retryFailed(delivery.toDelivery(), now)
                    }
                }
                if (
                    retriedCount > 0 &&
                    currentChatId == conversationId &&
                    FirebaseAuth.getInstance().currentUser?.uid == ownerUserId
                ) {
                    EncryptedOlmDeliveryWorker.scheduleRecoveryAfterTrustChange(
                        context = appContext,
                        ownerUserId = ownerUserId,
                        senderDeviceId = senderDeviceId,
                    )
                    Log.i(
                        "ChatViewModel",
                        "Requeued $retriedCount original encrypted delivery(s) after recipient SAS trust changed.",
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(
                    "ChatViewModel",
                    "Could not recover failed encrypted deliveries after trust changed.",
                    error,
                )
            }
        }
    }

    private fun startMessageListener(
        chatId: String,
        currentUserId: String,
        otherUserId: String,
        otherUserNickname: String
    ) {
        encryptedTrustRetryJob?.cancel()
        encryptedTrustRetryJob = viewModelScope.launch {
            try {
                TrustedDeviceRepository(InChatApplication.instance)
                    .observeTrustChanges()
                    .onStart { emit(Unit) }
                    .collect {
                        if (
                            currentChatId == chatId &&
                            FirebaseAuth.getInstance().currentUser?.uid == currentUserId
                        ) {
                            retryFailedDirectDeliveriesForTrustedDevices(
                                ownerUserId = currentUserId,
                                recipientUserId = otherUserId,
                                conversationId = chatId,
                            )
                        }
                    }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(
                    "ChatViewModel",
                    "Direct-message trust-change retry observer stopped.",
                    error,
                )
            }
        }

        encryptedDeliveryStatusJob?.cancel()
        _encryptedDeliveryStatuses.value = emptyMap()
        encryptedDeliveryStatusJob = viewModelScope.launch {
            try {
                encryptedMessageDeliveryStatusRepository
                    .observeConversation(
                        ownerUserId = currentUserId,
                        recipientUserId = otherUserId,
                        conversationId = chatId,
                    )
                    .collect { statuses ->
                        if (
                            currentChatId == chatId &&
                            FirebaseAuth.getInstance().currentUser?.uid == currentUserId
                        ) {
                            _encryptedDeliveryStatuses.value = statuses
                        }
                    }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentChatId == chatId) {
                    _encryptedDeliveryStatuses.value = emptyMap()
                }
                Log.e(
                    "ChatViewModel",
                    "Encrypted direct-message delivery state could not be observed",
                    error,
                )
            }
        }

        localMessagesJob?.cancel()
        localMessagesJob = null
        listenerJob?.cancel()
        _encryptedMessageIds.value = emptySet()

        listenerJob =
            viewModelScope.launch {

                try {

                    combine(
                        chatRepository.getMessagesFlow(chatId),
                        encryptedOlmMessageRepository.observeMessages(
                            ownerUserId = currentUserId,
                            conversationId = chatId
                        ),
                        encryptedDirectPendingMessageRepository.observePendingOutgoingIntents(
                            ownerUserId = currentUserId,
                            conversationId = chatId,
                            peerUserId = otherUserId,
                        ),
                    ) { legacyMessages, encryptedMessages, pendingDirectMessages ->
                        val encryptedIds = encryptedMessages.mapTo(mutableSetOf()) {
                            it.messageId
                        }
                        val pendingIds = pendingDirectMessages.mapTo(mutableSetOf()) {
                            it.messageId
                        }
                        _encryptedMessageIds.value = encryptedIds
                        _pendingDirectMessageIds.value = pendingIds
                        _pendingMessageIds.value = _legacyPendingMessageIds.value + pendingIds

                        val encryptedTimelineMessages = encryptedMessages.map { encrypted ->
                            encrypted.toTimelineMessage(
                                currentUserId = currentUserId,
                                otherUserNickname = otherUserNickname
                            )
                        }
                        // A committed sender copy takes precedence over the pending
                        // echo during the small transaction window between outbox
                        // commit and pending-intent deletion.
                        val pendingTimelineMessages = pendingDirectMessages
                            .filterNot { it.messageId in encryptedIds }
                            .map { pending ->
                                Message(
                                    id = pending.messageId,
                                    senderId = currentUserId,
                                    senderNickname = "You",
                                    text = pending.plaintext,
                                    timestamp = pending.sentAtMillis,
                                )
                            }
                        val hiddenLegacyIds = encryptedIds + pendingIds
                        // Never let a stale plaintext cache row replace an
                        // authenticated encrypted message or a local encrypted intent.
                        (legacyMessages.filterNot { it.id in hiddenLegacyIds } +
                            encryptedTimelineMessages +
                            pendingTimelineMessages)
                            .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                    }
                        .distinctUntilChanged()
                        .collect { messageList ->

                            _messages.value =
                                messageList

                            _messagesLoaded.value = true

                            if (!hasRequestedOlderMessages) {
                                _hasOlderMessages.value = false
                            }

                            // One pass finds the newest incoming message for both
                            // delivery and read cursors. The old code scanned the
                            // same list twice and wrote the delivery cursor on every
                            // local-cache and Firebase snapshot emission.
                            val newestOtherMessage = messageList
                                .asSequence()
                                .filter {
                                    it.senderId != currentUserId &&
                                        it.timestamp > 0L
                                }
                                .maxOfOrNull { it.timestamp }
                                ?: 0L

                            if (newestOtherMessage > lastMarkedDeliveredTimestamp) {
                                lastMarkedDeliveredTimestamp = newestOtherMessage
                                markDelivered(
                                    currentUserId = currentUserId,
                                    timestamp = newestOtherMessage
                                )
                            }

                            if (
                                isChatVisible &&
                                newestOtherMessage > lastMarkedReadTimestamp
                            ) {
                                lastMarkedReadTimestamp = newestOtherMessage
                                markConversationRead(currentUserId)
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

    private fun startDeliveryListener(
        chatId: String,
        otherUserId: String
    ) {
        deliveryListenerJob?.cancel()

        deliveryListenerJob =
            viewModelScope.launch {
                try {
                    chatRepository
                        .getOtherUserDeliveredTimestampFlow(chatId, otherUserId)
                        .collect { timestamp ->
                            _otherUserDeliveredTimestamp.value = timestamp
                        }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Delivery listener failed", e)
                }
            }
    }

    private fun markDelivered(
        currentUserId: String,
        timestamp: Long
    ) {
        val chatId = currentChatId
        if (chatId.isBlank()) return
        viewModelScope.launch {
            val result = chatRepository.markConversationDelivered(
                currentUserId = currentUserId,
                chatId = chatId,
                timestamp = timestamp
            )
            if (result.isFailure) {
                if (currentChatId == chatId && lastMarkedDeliveredTimestamp == timestamp) {
                    lastMarkedDeliveredTimestamp = 0L
                }
                Log.w(
                    "ChatViewModel",
                    "Could not update delivery cursor for $chatId",
                    result.exceptionOrNull()
                )
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
        readListenerJob = viewModelScope.launch {
            try {
                combine(
                    userRepository.observeReadReceiptsVisible(otherUserId),
                    blockState
                ) { visible, block -> visible && block == BlockState.NONE }
                    .collectLatest { canObserve ->
                        if (!canObserve) {
                            _otherUserReadTimestamp.value = 0L
                        } else {
                            chatRepository
                                .getOtherUserReadTimestampFlow(chatId, otherUserId)
                                .collect { timestamp ->
                                    _otherUserReadTimestamp.value = timestamp
                                }
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Read listener failed", e)
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
                                val chatId = currentChatId
                                viewModelScope.launch {
                                    chatRepository.clearReadReceipt(
                                        currentUserId,
                                        chatId
                                    )
                                }
                            } else if (
                                isChatVisible &&
                                _chatReady.value &&
                                _readyChatId.value == currentChatId
                            ) {
                                markConversationRead(currentUserId)
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

        if (message.id in _encryptedMessageIds.value) {
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

        if (messageId in _encryptedMessageIds.value) {
            onResult(false, "Editing encrypted messages is not supported yet.")
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

        val editChatId = currentChatId
        val editingMessageId = messageId

        viewModelScope.launch {

            try {

                chatRepository
                    .editMessage(
                        chatId =
                            editChatId,

                        messageId =
                            messageId,

                        userId =
                            currentUserId,

                        newText =
                            cleanText
                    )
                    .onSuccess {

                        if (
                            currentChatId == editChatId &&
                            _editingMessage.value?.id == editingMessageId
                        ) {
                            _editingMessage.value = null
                            onResult(true, null)
                        }

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

                        if (currentChatId == editChatId) {
                            onResult(
                                false,
                                error.message ?: "Could not edit message"
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

        if (messageId in _encryptedMessageIds.value) {
            onResult?.invoke(false, "Reactions on encrypted messages are not supported yet.")
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
            !_chatReady.value ||
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

        activeTypingUserId = currentUserId

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

        val chatId = currentChatId

        viewModelScope.launch {

            chatRepository
                .markConversationRead(
                    currentUserId =
                        currentUserId,

                    chatId =
                        chatId,

                    sendReadReceipt =
                        _readReceiptsEnabled.value
                )
                .onFailure { error ->
                    if (currentChatId == chatId) {
                        lastMarkedReadTimestamp = 0L
                    }
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
        otherUserNickname: String,
        onQueued: () -> Unit,
        onResult: (success: Boolean, error: String?) -> Unit
    ) {

        val cleanText = text.trim()
        val chatId = currentChatId

        if (cleanText.isEmpty()) {
            onResult(false, "Message cannot be empty.")
            return
        }

        if (
            !_chatReady.value ||
            _readyChatId.value != chatId ||
            chatId.isBlank() ||
            currentUserId.isBlank() ||
            otherUserId.isBlank()
        ) {
            onResult(false, "This chat is still connecting. Please wait and try again.")
            return
        }

        if (_blockState.value != BlockState.NONE) {
            Log.d("ChatViewModel", "Message prevented: blocked relationship")
            onResult(false, "Messages cannot be sent because this conversation is blocked.")
            return
        }

        if (_editingMessage.value != null) {
            Log.d("ChatViewModel", "Normal send ignored while editing a message")
            onResult(false, "Finish editing the current message first.")
            return
        }

        // Reply metadata is not part of the authenticated Olm text schema yet.
        // Refuse the action instead of leaking reply text through the legacy RTDB path.
        if (_replyingTo.value != null) {
            onResult(false, "Replies are not available in encrypted direct messages yet.")
            return
        }

        onTypingChanged(
            currentUserId = currentUserId,
            isTyping = false
        )

        val messageId = chatRepository.generateMessageId(chatId)
        if (messageId == null) {
            Log.e("ChatViewModel", "Could not generate message ID")
            onResult(false, "Could not prepare this message. Please try again.")
            return
        }

        val sentAtMillis = System.currentTimeMillis()

        viewModelScope.launch {
            var locallySaved = false
            try {
                // Persist only AES-GCM ciphertext before touching Firebase. This
                // makes sending local-first: slow key lookup, prekey claims, or
                // temporary network failures cannot hold the composer hostage.
                withContext(Dispatchers.IO) {
                    encryptedDirectPendingMessageRepository.savePendingOutgoingIntent(
                        ownerUserId = currentUserId,
                        conversationId = chatId,
                        peerUserId = otherUserId,
                        messageId = messageId,
                        sentAtMillis = sentAtMillis,
                        plaintext = cleanText,
                    )
                }
                locallySaved = true

                if (currentChatId == chatId) {
                    _pendingDirectMessageIds.value = _pendingDirectMessageIds.value + messageId
                    _pendingMessageIds.value = _legacyPendingMessageIds.value + _pendingDirectMessageIds.value
                }

                // The local encrypted outbox is the send-acceptance boundary.
                // Release the composer before any optional device provisioning.
                onQueued()
                onResult(true, null)

                // WorkManager is the single network sender for pending direct
                // intents. Its connected-network constraint and retry policy keep
                // this coroutine and the compose input independent of Firebase.
                try {
                    val senderDeviceId = withContext(Dispatchers.IO) {
                        val identity = DeviceIdentityRepository(InChatApplication.instance)
                        identity.existingDeviceIdForDelivery() ?: try {
                            DeviceKeyDirectoryRepository().ensureMyDevicePublished(identity).deviceId
                        } catch (provisioningError: Exception) {
                            // A failed Firebase publication can happen after the
                            // local Keystore identity was created. Schedule the worker
                            // with that stable identity so it can retry publication.
                            identity.existingDeviceIdForDelivery() ?: throw provisioningError
                        }
                    }
                    EncryptedOlmDeliveryWorker.scheduleRecoveryAfterTrustChange(
                        context = InChatApplication.instance,
                        ownerUserId = currentUserId,
                        senderDeviceId = senderDeviceId,
                    )
                    Log.d(
                        "ChatViewModel",
                        "Encrypted direct message saved locally and background delivery scheduled: $messageId",
                    )
                } catch (scheduleError: Exception) {
                    // The intent remains encrypted and durable. Authentication
                    // startup and the periodic delivery worker remain recovery paths.
                    Log.w(
                        "ChatViewModel",
                        "Encrypted direct message is saved locally; immediate worker scheduling will be retried: $messageId",
                        scheduleError,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The encrypted pending intent survives ViewModel/process teardown;
                // the auth-scoped worker resumes it when the app becomes active.
                throw e
            } catch (e: Exception) {
                if (!locallySaved) {
                    Log.e("ChatViewModel", "Encrypted direct message could not be saved locally: $messageId", e)
                    onResult(
                        false,
                        e.localizedMessage ?: "Message could not be saved securely on this device.",
                    )
                } else {
                    // The message is accepted locally. Keep its encrypted pending
                    // row and retry on the next auth/startup or network recovery pass.
                    Log.w(
                        "ChatViewModel",
                        "Encrypted direct message is saved locally but delivery scheduling needs recovery: $messageId",
                        e,
                    )
                    onResult(
                        true,
                        "Message saved securely on this phone. Delivery will retry when the connection is ready.",
                    )
                }
            }
        }
    }
    fun deleteMessagesForMe(
        messageIds: Set<String>,
        onResult: (Boolean, String?) -> Unit
    ) {
        val chatId = currentChatId
        if (chatId.isBlank() || messageIds.isEmpty()) {
            onResult(false, "No messages selected.")
            return
        }

        val ownerUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank()) {
            onResult(false, "You are not authenticated.")
            return
        }

        if (messageIds.any { it in _pendingDirectMessageIds.value }) {
            onResult(false, "A message is still waiting for encrypted delivery. Try again after it is sent.")
            return
        }

        viewModelScope.launch {
            for (messageId in messageIds) {
                if (messageId in _encryptedMessageIds.value) {
                    try {
                        encryptedOlmMessageRepository.deleteMessageForMe(
                            ownerUserId = ownerUserId,
                            conversationId = chatId,
                            messageId = messageId,
                        )
                    } catch (error: Exception) {
                        onResult(false, error.localizedMessage ?: "Could not delete encrypted message for you.")
                        return@launch
                    }
                } else {
                    val result = chatRepository.deleteMessageForMe(chatId, messageId)
                    if (result.isFailure) {
                        onResult(false, result.exceptionOrNull()?.message ?: "Could not delete message for you.")
                        return@launch
                    }
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
        val chatId = currentChatId
        if (chatId.isBlank() || messageIds.isEmpty()) {
            onResult(false, "No messages selected.")
            return
        }
        if (messageIds.any { it in _encryptedMessageIds.value || it in _pendingDirectMessageIds.value }) {
            onResult(false, "Encrypted messages cannot be deleted for everyone yet.")
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
                val result = chatRepository.deleteMessage(chatId, messageId)
                if (result.isFailure) {
                    onResult(false, result.exceptionOrNull()?.message ?: "Could not delete message for everyone.")
                    return@launch
                }
            }
            onResult(true, null)
        }
    }

    fun clearChatForMe(onResult: (Boolean, String?) -> Unit) {
        val chatId = currentChatId
        if (chatId.isBlank()) {
            onResult(false, "No chat is open.")
            return
        }
        val pendingIds = _pendingMessageIds.value
        val ownerUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank()) {
            onResult(false, "You are not authenticated.")
            return
        }

        viewModelScope.launch {
            val result = chatRepository.clearChatForMe(chatId, pendingIds)
            if (result.isFailure) {
                onResult(false, result.exceptionOrNull()?.message)
                return@launch
            }
            try {
                encryptedOlmMessageRepository.deleteConversationForMe(
                    ownerUserId = ownerUserId,
                    conversationId = chatId,
                )
                encryptedDirectPendingMessageRepository.deleteConversationForMe(
                    ownerUserId = ownerUserId,
                    conversationId = chatId,
                )
                if (currentChatId == chatId) {
                    _pendingDirectMessageIds.value = emptySet()
                    _pendingMessageIds.value = _legacyPendingMessageIds.value
                }
                onResult(true, null)
            } catch (error: Exception) {
                onResult(
                    false,
                    error.localizedMessage ?: "Legacy history was cleared, but encrypted local history could not be cleared.",
                )
            }
        }
    }

    /**
     * Project authenticated Olm plaintext into the existing display model only
     * in memory. The source row remains AES-GCM encrypted in Room.
     */
    private fun DecryptedOlmMessage.toTimelineMessage(
        currentUserId: String,
        otherUserNickname: String
    ): Message = Message(
        id = messageId,
        senderId = senderUserId,
        senderNickname = if (senderUserId == currentUserId) "You" else otherUserNickname,
        text = text,
        timestamp = sentAtMillis
    )

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

        if (messageId in _encryptedMessageIds.value || messageId in _pendingDirectMessageIds.value) {
            Log.w("ChatViewModel", "Delete-for-everyone is unavailable for an encrypted direct message.")
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
    fun setChatVisible(
        currentUserId: String,
        visible: Boolean
    ) {
        isChatVisible = visible
        if (currentChatId.isBlank()) return

        if (visible) {
            if (_chatReady.value && _readyChatId.value == currentChatId) {
                markConversationRead(currentUserId)
            }
        } else {
            clearChatState()
        }
    }

    private fun clearTypingState(currentUserId: String, chatId: String) {
        typingResetJob?.cancel()
        typingResetJob = null
        if (currentUserId.isNotBlank() && chatId.isNotBlank()) {
            database.getReference("typing")
                .child(chatId).child(currentUserId).setValue(false)
        }
        if (activeTypingUserId == currentUserId) activeTypingUserId = ""
    }

    fun clearChatState() {
        if (activeTypingUserId.isNotBlank() && currentChatId.isNotBlank()) {
            clearTypingState(activeTypingUserId, currentChatId)
        }
        _replyingTo.value = null
        _editingMessage.value = null
        // The active chat listeners remain attached while this screen is hidden.
        // Keep their encrypted IDs/statuses so reopening this same chat is consistent.
        _otherUserTyping.value = false
    }

    fun loadOlderMessages() {
        val chatId = currentChatId
        if (chatId.isBlank() || !_hasOlderMessages.value ||
            _isLoadingOlderMessages.value
        ) return

        val oldestTimestamp = _messages.value
            .firstOrNull { it.timestamp > 0L }?.timestamp ?: return

        hasRequestedOlderMessages = true
        _isLoadingOlderMessages.value = true
        viewModelScope.launch {
            try {
                chatRepository.getOlderMessages(
                    chatId = chatId,
                    beforeTimestamp = oldestTimestamp
                ).onSuccess { olderMessages ->
                    if (currentChatId == chatId) {
                        _messages.value =
                            // The Firebase page ends strictly before the
                            // oldest loaded timestamp, so these IDs cannot overlap.
                            olderMessages + _messages.value
                        _hasOlderMessages.value = false
                    }
                }.onFailure { error ->
                    Log.e("ChatViewModel", "Could not load earlier messages", error)
                }
            } finally {
                _isLoadingOlderMessages.value = false
            }
        }
    }

    /*
     * =========================================================
     * CLEANUP
     * =========================================================
     */
    override fun onCleared() {
        if (activeTypingUserId.isNotBlank() && currentChatId.isNotBlank()) {
            clearTypingState(activeTypingUserId, currentChatId)
        }

        connectionListenerJob?.cancel()
        initializationJob?.cancel()
        localMessagesJob?.cancel()
        deliveryListenerJob?.cancel()

        listenerJob?.cancel()
        encryptedDeliveryStatusJob?.cancel()
        encryptedTrustRetryJob?.cancel()
        readListenerJob?.cancel()
        readReceiptPreferenceJob?.cancel()
        presenceJob?.cancel()
        typingListenerJob?.cancel()
        typingPreferenceJob?.cancel()
        typingResetJob?.cancel()
        blockListenerJob?.cancel()
        chatRepository.clearOlderMessagePages(currentChatId)

        super.onCleared()
    }
}