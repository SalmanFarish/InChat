package com.example.inchat.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.InChatApplication
import com.example.inchat.crypto.DecryptedGroupMessage
import com.example.inchat.crypto.EncryptedGroupMessageRepository
import com.example.inchat.crypto.MegolmGroupMessageSendCoordinatorFactory
import com.example.inchat.crypto.MegolmPendingGroupIntentRetryLock
import com.example.inchat.crypto.MegolmGroupMessageSendOutcome
import com.example.inchat.crypto.PendingGroupMessageIntent
import com.example.inchat.crypto.TrustedDeviceRepository
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.CloudflareNotificationRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    // Outgoing group intents remain encrypted-at-rest until every trusted
    // member device acknowledges the exact Megolm session key.
    private val _pendingEncryptedMessages =
        MutableStateFlow<List<PendingGroupMessageIntent>>(emptyList())
    internal val pendingEncryptedMessages: StateFlow<List<PendingGroupMessageIntent>> =
        _pendingEncryptedMessages.asStateFlow()

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
    private var pendingIntentRetryJob: Job? = null
    private var trustChangeRetryJob: Job? = null
    private val pendingOutgoingIntentMutex = MegolmPendingGroupIntentRetryLock.mutex
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
        pendingIntentRetryJob?.cancel()
        trustChangeRetryJob?.cancel()
        pendingMessagesJob = null
        pendingIntentRetryJob = null
        _pendingEncryptedMessages.value = emptyList()
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
        _hasOlderMessages.value = false

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
                // Recover completed sends as soon as the conversation is reopened,
                // not only when the user happens to compose another message.
                val recoveryMembership = groupRepository.getGroupCryptoMembershipSnapshot(groupId)
                if (
                    FirebaseAuth.getInstance().currentUser?.uid == currentUserId &&
                    recoveryMembership != null &&
                    recoveryMembership.isValid() &&
                    recoveryMembership.groupId == groupId &&
                    currentUserId in recoveryMembership.memberUserIds
                ) {
                    try {
                        withContext(Dispatchers.IO) {
                            encryptedGroupMessageRepository.recoverCommittedOutgoingIntents(
                                ownerUserId = currentUserId,
                                groupId = groupId,
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (recoveryError: Exception) {
                        Log.e(
                            "GroupChatViewModel",
                            "Committed encrypted group messages could not be restored to the local timeline.",
                            recoveryError,
                        )
                    }
                }
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

        // Surface the encrypted outgoing intent immediately, including while
        // the Megolm key-distribution gate is waiting for trusted-device ACKs.
        // This flow decrypts only on IO and never writes to the legacy chat cache.
        pendingMessagesJob = viewModelScope.launch {
            try {
                if (FirebaseAuth.getInstance().currentUser?.uid != currentUserId) {
                    _pendingEncryptedMessages.value = emptyList()
                    _pendingMessageIds.value = emptySet()
                    return@launch
                }
                encryptedGroupMessageRepository
                    .observePendingOutgoingIntents(currentUserId, groupId)
                    .collect { pending ->
                        if (
                            listeningGroupId == groupId &&
                            FirebaseAuth.getInstance().currentUser?.uid == currentUserId
                        ) {
                            _pendingEncryptedMessages.value = pending
                            _pendingMessageIds.value = pending.mapTo(mutableSetOf()) {
                                it.messageId
                            }
                        }
                    }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (listeningGroupId == groupId) {
                    _pendingEncryptedMessages.value = emptyList()
                    _pendingMessageIds.value = emptySet()
                }
                Log.e(
                    "GroupChatViewModel",
                    "Encrypted pending group messages could not be observed",
                    error,
                )
            }
        }

        // Durable receipt inserts wake pending outgoing messages. The initial
        // emission also handles acknowledgements received while this chat was closed.
        pendingIntentRetryJob = viewModelScope.launch {
            try {
                encryptedGroupMessageSendCoordinator
                    .observeAcknowledgementChanges(currentUserId)
                    .distinctUntilChanged()
                    .collect {
                        if (listeningGroupId != groupId ||
                            FirebaseAuth.getInstance().currentUser?.uid != currentUserId
                        ) return@collect
                        try {
                            pendingOutgoingIntentMutex.withLock {
                                retryPendingOutgoingIntents(groupId, currentUserId)
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (retryError: Exception) {
                            Log.e(
                                "GroupChatViewModel",
                                "Acknowledgement-driven retry pass failed; a later receipt can retry it.",
                                retryError,
                            )
                        }
                    }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (retryError: Exception) {
                Log.e("GroupChatViewModel", "Automatic encrypted group-message retry observer stopped.", retryError)
            }
        }

        // Device verification changes local SAS trust but does not itself create
        // a delivery receipt. Wake pending group intents when that trust roster changes;
        // the send coordinator independently revalidates membership and trust before use.
        trustChangeRetryJob = viewModelScope.launch {
            try {
                TrustedDeviceRepository(InChatApplication.instance)
                    .observeTrustChanges()
                    .collect {
                        if (listeningGroupId != groupId ||
                            FirebaseAuth.getInstance().currentUser?.uid != currentUserId
                        ) return@collect
                        try {
                            pendingOutgoingIntentMutex.withLock {
                                retryPendingOutgoingIntents(groupId, currentUserId)
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (retryError: Exception) {
                            Log.e(
                                "GroupChatViewModel",
                                "Trust-change retry pass failed; the encrypted intent remains stored.",
                                retryError,
                            )
                        }
                    }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (observerError: Exception) {
                Log.e(
                    "GroupChatViewModel",
                    "Encrypted group trust-change observer stopped.",
                    observerError,
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
                            _hasOlderMessages.value = false
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

    /**
     * Re-attempt durable pending intents after acknowledgement receipts change.
     * Every attempt revalidates the exact Megolm session, membership and device roster.
     */
    private suspend fun retryPendingOutgoingIntents(groupId: String, ownerUserId: String) {
        if (listeningGroupId != groupId ||
            FirebaseAuth.getInstance().currentUser?.uid != ownerUserId
        ) return
        val membership = groupRepository.getGroupCryptoMembershipSnapshot(groupId) ?: return
        if (!membership.isValid() || membership.groupId != groupId ||
            ownerUserId !in membership.memberUserIds
        ) return

        // Resume any exact native-staged ciphertext before considering plaintext
        // intents. This prevents an interrupted staged message from being encrypted twice.
        withContext(Dispatchers.IO) {
            encryptedGroupMessageSendCoordinator.recoverPendingMessages()
        }
        withContext(Dispatchers.IO) {
            encryptedGroupMessageRepository.recoverCommittedOutgoingIntents(ownerUserId, groupId)
        }
        val pending = withContext(Dispatchers.IO) {
            encryptedGroupMessageRepository.pendingOutgoingIntents(ownerUserId)
                .filter { it.groupId == groupId }
        }
        for (intent in pending) {
            if (listeningGroupId != groupId ||
                FirebaseAuth.getInstance().currentUser?.uid != ownerUserId
            ) return
            try {
                when (val outcome = withContext(Dispatchers.IO) {
                    encryptedGroupMessageSendCoordinator.send(
                        groupId = groupId,
                        messageId = intent.messageId,
                        plaintext = intent.plaintext,
                        sentAtMillis = intent.sentAtMillis,
                    )
                }) {
                    is MegolmGroupMessageSendOutcome.AwaitingKeyAcknowledgements -> Unit
                    is MegolmGroupMessageSendOutcome.Queued -> {
                        val queued = outcome.result
                        withContext(Dispatchers.IO) {
                            encryptedGroupMessageRepository.saveOutgoingMessageAndClearPendingIntent(
                                DecryptedGroupMessage(
                                    ownerUserId = ownerUserId,
                                    groupId = groupId,
                                    messageId = queued.messageId,
                                    membershipEpoch = queued.membershipEpoch,
                                    senderUserId = queued.senderUserId,
                                    senderDeviceId = queued.senderDeviceId,
                                    megolmSessionId = queued.megolmSessionId,
                                    messageIndex = queued.messageIndex,
                                    sentAtMillis = queued.sentAtMillis,
                                    text = intent.plaintext,
                                )
                            )
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (retryError: Exception) {
                Log.e(
                    "GroupChatViewModel",
                    "Pending encrypted group message ${intent.messageId} remains queued for retry.",
                    retryError,
                )
            }
        }
    }

    private fun isEncryptedGroupMessage(messageId: String): Boolean =
        messageId.isNotBlank() && (
            _encryptedMessages.value.any { it.messageId == messageId } ||
                _pendingEncryptedMessages.value.any { it.messageId == messageId }
            )

    fun setReplyingTo(message: Message) {
        if (
            isEncryptedGroupMessage(message.id)
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
        if (isEncryptedGroupMessage(message.id)) return
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
            var pendingIntentPersisted = false
            try {
                pendingOutgoingIntentMutex.withLock {
                val existingMembership = groupRepository.getGroupCryptoMembershipSnapshot(groupId)
                val membership = if (
                    existingMembership != null &&
                    existingMembership.isValid() &&
                    existingMembership.groupId == groupId
                ) {
                    existingMembership
                } else {
                    // Legacy groups may predate the Worker-authored E2EE snapshot.
                    // Ask the authenticated Worker to initialize/repair it; never
                    // synthesize membership from client-side group metadata.
                    val initializedMembership =
                        CloudflareNotificationRepository()
                            .ensureGroupCryptoMembershipSnapshot(groupId)
                            .getOrThrow()

                    check(FirebaseAuth.getInstance().currentUser?.uid == currentUserId) {
                        "The signed-in account changed during group E2EE initialization."
                    }
                    require(
                        initializedMembership.isValid() &&
                            initializedMembership.groupId == groupId &&
                            currentUserId in initializedMembership.memberUserIds
                    ) {
                        "The server could not verify this account's encrypted group membership."
                    }

                    // Firebase may first emit a stale locally cached value while the
                    // Worker's authoritative write is propagating. Ignore every
                    // non-matching emission and wait briefly for the exact snapshot.
                    // The send coordinator still performs its own independent reads
                    // and membership checks before it can commit any ciphertext.
                    val refreshedMembership = withTimeoutOrNull(15_000L) {
                        groupRepository.observeGroupCryptoMembership(groupId).first {
                            observed ->
                            observed != null &&
                                observed.isValid() &&
                                observed.groupId == groupId &&
                                observed.epoch == initializedMembership.epoch &&
                                observed.members == initializedMembership.members
                        }
                    } ?: throw IllegalStateException(
                        "The server initialized group encryption, but its membership snapshot did not become visible. Please retry."
                    )
                    check(FirebaseAuth.getInstance().currentUser?.uid == currentUserId) {
                        "The signed-in account changed while waiting for group encryption to initialize."
                    }
                    refreshedMembership
                }

                if (!membership.isValid() ||
                    membership.groupId != groupId ||
                    currentUserId !in membership.memberUserIds
                ) {
                    onResult(
                        false,
                        "This account is not an active member of the group's encrypted membership."
                    )
                    return@launch
                }

                // A prior process may have died after the ciphertext fan-out was
                // committed but before its sender timeline echo was stored. Recover
                // that exact ciphertext metadata from the durable marker; never call
                // Megolm send again for a committed pending intent.
                withContext(Dispatchers.IO) {
                    encryptedGroupMessageRepository.recoverCommittedOutgoingIntents(
                        ownerUserId = currentUserId,
                        groupId = groupId,
                    )
                }

                // A previous send may have been securely staged while a device
                // was untrusted or temporarily unavailable. Re-evaluate those
                // intents before adding this new one, so a later successful send
                // also drives recovery instead of stranding older messages.
                retryPendingOutgoingIntents(groupId, currentUserId)

                // Persist the user's intent before attempting key distribution or
                // advancing the Megolm ratchet. Room stores only AES-GCM ciphertext.
                withContext(Dispatchers.IO) {
                    encryptedGroupMessageRepository.savePendingOutgoingIntent(
                        ownerUserId = currentUserId,
                        groupId = groupId,
                        messageId = messageId,
                        sentAtMillis = sentAtMillis,
                        plaintext = cleanText,
                    )
                }
                // A persisted encrypted intent is now safely accepted by this
                // device, even while group-key acknowledgements are outstanding.
                // Clear the composer now so a tap/retry cannot create a duplicate ID.
                pendingIntentPersisted = true
                onQueued()

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
                            true,
                            "Message saved securely. It is waiting for trusted member devices to acknowledge the group key.",
                        )
                    }

                    is MegolmGroupMessageSendOutcome.Queued -> {
                        val queued = outcome.result
                        // Keep the sender's own timeline copy in the isolated
                        // AES-GCM encrypted table; the legacy plaintext cache
                        // and Firebase chat path are never touched.
                        try {
                            encryptedGroupMessageRepository.saveOutgoingMessageAndClearPendingIntent(
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
                        onResult(true, null)
                    }
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
                if (pendingIntentPersisted) {
                    onResult(
                        true,
                        "Message is saved securely on this device. Delivery will retry when the group encryption prerequisites are ready.",
                    )
                } else {
                    onResult(
                        false,
                        e.message ?: "Encrypted group message could not be queued.",
                    )
                }
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

        if (isEncryptedGroupMessage(messageId)) {
            onResult(false, "Editing encrypted group messages is not supported yet.")
            return
        }

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

        val ownerUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank()) {
            onResult(false, "You are not authenticated.")
            return
        }

        val pendingIdsForChat = _pendingEncryptedMessages.value.mapTo(mutableSetOf()) { it.messageId }
        if (messageIds.any { it in pendingIdsForChat }) {
            onResult(false, "This encrypted group message is still waiting for secure delivery and cannot be deleted yet.")
            return
        }
        val encryptedIdsForChat = _encryptedMessages.value.mapTo(mutableSetOf()) { it.messageId }
        viewModelScope.launch {
            for (messageId in messageIds) {
                if (messageId in encryptedIdsForChat) {
                    try {
                        encryptedGroupMessageRepository.deleteMessageForMe(
                            ownerUserId = ownerUserId,
                            groupId = groupId,
                            messageId = messageId,
                        )
                    } catch (error: Exception) {
                        onResult(false, error.localizedMessage ?: "Could not delete encrypted group message for you.")
                        return@launch
                    }
                } else {
                    val result = chatRepository.deleteMessageForMe(groupId, messageId)
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
        val groupId = listeningGroupId
        if (groupId.isBlank() || messageIds.isEmpty()) {
            onResult(false, "No messages selected.")
            return
        }
        if (messageIds.any { isEncryptedGroupMessage(it) }) {
            onResult(false, "Encrypted group messages cannot be deleted for everyone yet.")
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
        val ownerUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (ownerUserId.isBlank()) {
            onResult(false, "You are not authenticated.")
            return
        }
        viewModelScope.launch {
            val result = chatRepository.clearChatForMe(groupId, pendingIds)
            if (result.isFailure) {
                onResult(false, result.exceptionOrNull()?.message)
                return@launch
            }
            try {
                encryptedGroupMessageRepository.deleteConversationForMe(
                    ownerUserId = ownerUserId,
                    groupId = groupId,
                )
                onResult(true, null)
            } catch (error: Exception) {
                onResult(
                    false,
                    error.localizedMessage ?: "Legacy history was cleared, but encrypted group history could not be cleared.",
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
        if (isEncryptedGroupMessage(messageId)) {
            onResult(false, "Encrypted group messages cannot be deleted for everyone yet.")
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

    /** Legacy Firebase history pages are disabled; the timeline is Room-only. */
    fun loadOlderMessages() {
        _hasOlderMessages.value = false
    }

    fun toggleReaction(
        messageId: String,
        reaction: String,
        currentUserId: String
    ) {

        if (listeningGroupId.isBlank() || isEncryptedGroupMessage(messageId)) {
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
        pendingIntentRetryJob?.cancel()
        trustChangeRetryJob?.cancel()
        connectionListenerJob?.cancel()
        groupReadJobs.values.forEach { it.cancel() }
        groupReadJobs.clear()
        chatRepository.clearOlderMessagePages(listeningGroupId)
        super.onCleared()
    }
}