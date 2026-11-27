package com.example.inchat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.InChatApplication
import com.example.inchat.crypto.EncryptedDirectPendingMessageRepository
import com.example.inchat.crypto.EncryptedGroupMessageRepository
import com.example.inchat.crypto.EncryptedOlmMessageRepository
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.model.Conversation
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.ConversationPreference
import com.example.inchat.data.repository.ConversationPreferencesRepository
import com.example.inchat.data.repository.GroupChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A message preview decrypted from this account's local encrypted message store.
 * The text is held only in memory for rendering; it is never written to Firebase.
 */
data class LocalConversationPreview(
    val text: String,
    val senderUserId: String,
    val timestamp: Long
)

class HomeViewModel : ViewModel() {

    private val chatRepository =
        ChatRepository()

    private val groupChatRepository =
        GroupChatRepository()

    private val conversationPreferencesRepository =
        ConversationPreferencesRepository()

    private val localChatDatabase by lazy {
        LocalChatDatabase.getInstance(InChatApplication.instance)
    }

    private val encryptedOlmMessageRepository by lazy {
        EncryptedOlmMessageRepository(localChatDatabase.encryptedOlmMessageDao())
    }

    private val encryptedDirectPendingMessageRepository by lazy {
        EncryptedDirectPendingMessageRepository(
            localChatDatabase.encryptedDirectPendingMessageDao()
        )
    }

    private val encryptedGroupMessageRepository by lazy {
        EncryptedGroupMessageRepository(localChatDatabase.encryptedGroupMessageDao())
    }

    private val _conversationPreferences =
        MutableStateFlow<Map<String, ConversationPreference>>(emptyMap())

    private val _conversations =
        MutableStateFlow<List<Conversation>>(
            emptyList()
        )

    val conversations:
            StateFlow<List<Conversation>> =
        _conversations.asStateFlow()

    /*
     * The Home inbox is intentionally cache-first.
     *
     * Direct conversations are the primary Home data source and are
     * allowed to populate the UI as soon as Firebase's local/server
     * listener emits. Group conversations are merged in independently
     * so a slow group sync can never block the normal chat list.
     */
    private val _directConversations =
        MutableStateFlow<List<Conversation>>(
            emptyList()
        )

    private val _groupConversations =
        MutableStateFlow<List<Conversation>>(
            emptyList()
        )

    private val _directConversationsLoaded =
        MutableStateFlow(false)

    private val _groupConversationsLoaded =
        MutableStateFlow(false)

    private val _conversationsLoaded =
        MutableStateFlow(false)

    val conversationsLoaded:
            StateFlow<Boolean> =
        _conversationsLoaded.asStateFlow()

    private var conversationsJob:
            Job? = null

    private var listeningUserId =
        ""

    /*
     * =========================================================
     * CONVERSATION LISTENER
     * =========================================================
     */
    fun startConversationListener(
        currentUserId: String
    ) {

        if (
            currentUserId.isBlank()
        ) {

            _conversations.value =
                emptyList()

            _directConversations.value =
                emptyList()

            _groupConversations.value =
                emptyList()

            _conversationPreferences.value =
                emptyMap()

            _directConversationsLoaded.value =
                false

            _groupConversationsLoaded.value =
                false

            _conversationsLoaded.value =
                false

            listeningUserId =
                ""

            conversationsJob?.cancel()

            conversationsJob =
                null

            return
        }

        if (
            listeningUserId ==
            currentUserId
        ) {

            return
        }

        listeningUserId =
            currentUserId

        conversationsJob?.cancel()

        _conversations.value =
            emptyList()

        _directConversations.value =
            emptyList()

        _groupConversations.value =
            emptyList()

        _conversationPreferences.value =
            emptyMap()

        _directConversationsLoaded.value =
            false

        _groupConversationsLoaded.value =
            false

        _conversationsLoaded.value =
            false

        conversationsJob =
            viewModelScope.launch {

                /*
                 * Keep direct chats independent from group sync.
                 *
                 * This removes the old combine() bottleneck where Home
                 * waited for the direct listener AND every group summary
                 * before showing any conversation.
                 */
                launch {

                    chatRepository
                        .getConversationsFlow(
                            currentUserId
                        )
                        .map { list ->
                            list.filter {
                                it.chatType != "group"
                            }
                        }
                        .distinctUntilChanged()
                        .collect { list ->

                            _directConversations.value =
                                list

                            _directConversationsLoaded.value =
                                true

                            publishConversations()
                        }
                }

                /*
                 * Groups arrive independently and are merged into the
                 * already-visible direct chat list.
                 */
                launch {

                    groupChatRepository
                        .observeGroupConversations(
                            currentUserId
                        )
                        .distinctUntilChanged()
                        .collect { list ->

                            _groupConversations.value =
                                list

                            _groupConversationsLoaded.value =
                                true

                            publishConversations()
                        }
                }

                launch {
                    conversationPreferencesRepository
                        .observePreferences(currentUserId)
                        .catch {
                            emit(emptyMap())
                        }
                        .distinctUntilChanged()
                        .collect { preferences ->
                            _conversationPreferences.value = preferences
                            publishConversations()
                        }
                }
            }
    }

    private fun publishConversations() {

        val merged =
            (
                _directConversations.value +
                        _groupConversations.value
                )
                .distinctBy {
                    it.chatId
                }
                .map { conversation ->
                    val preference = _conversationPreferences.value[conversation.chatId]
                    val clearedAt = preference?.clearedAt ?: 0L
                    val historyWasCleared = clearedAt > 0L &&
                        clearedAt >= conversation.lastTimestamp
                    conversation.copy(
                        lastMessage = if (historyWasCleared) "No messages yet" else conversation.lastMessage,
                        lastTimestamp = if (historyWasCleared) clearedAt else conversation.lastTimestamp,
                        lastSenderId = if (historyWasCleared) "" else conversation.lastSenderId,
                        lastSenderNickname = if (historyWasCleared) "" else conversation.lastSenderNickname,
                        unreadCount = if (historyWasCleared) 0L else conversation.unreadCount,
                        isPinned = preference?.isPinned ?: false,
                        isHidden = preference?.isHidden ?: false
                    )
                }
                .sortedWith(
                    compareBy<Conversation> { it.isHidden }
                        .thenByDescending { it.isPinned }
                        .thenByDescending { it.lastTimestamp }
                )
                .take(50)

        _conversations.value =
            merged

        /*
         * Show the normal inbox immediately once direct conversations
         * have emitted. When there are no direct chats, wait for the
         * group stream's first emission so a real group-only inbox does
         * not briefly flash the empty state.
         */
        _conversationsLoaded.value =
            _directConversationsLoaded.value &&
                    (
                        _groupConversationsLoaded.value ||
                                _directConversations.value.isNotEmpty()
                        )
    }

    /**
     * Observe the newest locally available E2EE message for one inbox row.
     *
     * Direct and group content is decrypted from the existing account-scoped,
     * AES-GCM-protected Room stores. No plaintext preview is sent to Firebase
     * or copied into the legacy cached_messages table.
     */
    fun observeLocalPreview(
        currentUserId: String,
        conversation: Conversation
    ): Flow<LocalConversationPreview?> {
        if (currentUserId.isBlank() || conversation.chatId.isBlank()) {
            return flowOf(null)
        }

        // Defer repository construction until collection so a short-lived auth
        // transition cannot throw synchronously while a LazyColumn row composes.
        return flow {
            val source = if (conversation.chatType == "group") {
                observeGroupLocalPreview(currentUserId, conversation.chatId)
            } else {
                observeDirectLocalPreview(
                    currentUserId = currentUserId,
                    conversationId = conversation.chatId,
                    peerUserId = conversation.otherUserId
                )
            }
            emitAll(source)
        }.catch { emit(null) }.distinctUntilChanged()
    }

    private fun observeDirectLocalPreview(
        currentUserId: String,
        conversationId: String,
        peerUserId: String
    ): Flow<LocalConversationPreview?> {
        if (peerUserId.isBlank()) return flowOf(null)

        // Preserve the latest legacy message already cached on this device.
        // ChatRepository applies the same per-user clear/hide visibility rules
        // used by the chat timeline. New E2EE records always take precedence.
        val legacyMessages = chatRepository.getMessagesFlow(conversationId)
            .catch { emit(emptyList()) }
        val committedMessages = encryptedOlmMessageRepository
            .observeMessages(currentUserId, conversationId)
            .catch { emit(emptyList()) }
        val pendingMessages = encryptedDirectPendingMessageRepository
            .observePendingOutgoingIntents(
                ownerUserId = currentUserId,
                conversationId = conversationId,
                peerUserId = peerUserId
            )
            .catch { emit(emptyList()) }

        return combine(legacyMessages, committedMessages, pendingMessages) { legacy, committed, pending ->
            val protectedIds = committed.mapTo(mutableSetOf()) { it.messageId } +
                pending.map { it.messageId }
            val legacyPreview = legacy.asSequence()
                .filterNot { it.id in protectedIds }
                .maxByOrNull { it.timestamp }
                ?.let { LocalConversationPreview(it.text, it.senderId, it.timestamp) }
            val committedPreview = committed.maxByOrNull { it.sentAtMillis }
                ?.let { LocalConversationPreview(it.text, it.senderUserId, it.sentAtMillis) }
            val pendingPreview = pending.maxByOrNull { it.sentAtMillis }
                ?.let { LocalConversationPreview(it.plaintext, currentUserId, it.sentAtMillis) }

            newestPreview(legacyPreview, newestPreview(committedPreview, pendingPreview))
        }.distinctUntilChanged()
    }

    private fun observeGroupLocalPreview(
        currentUserId: String,
        groupId: String
    ): Flow<LocalConversationPreview?> {
        // Share the membership listener between the authorized message stream and
        // the pending-send stream. A removed member must never see a stale local preview.
        val membershipSnapshots = groupChatRepository
            .observeGroupCryptoMembership(groupId)
            .catch { emit(null) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = null
            )

        // Legacy group messages already cached by the chat timeline remain a
        // valid local preview source. E2EE content below stays membership-gated.
        val legacyMessages = chatRepository.getMessagesFlow(groupId)
            .catch { emit(emptyList()) }
        val committedMessages = encryptedGroupMessageRepository
            .observeMessagesForMember(
                ownerUserId = currentUserId,
                groupId = groupId,
                membershipSnapshots = membershipSnapshots
            )
            .catch { emit(emptyList()) }
        val pendingMessages = encryptedGroupMessageRepository
            .observePendingOutgoingIntents(currentUserId, groupId)
            .catch { emit(emptyList()) }

        return combine(
            membershipSnapshots,
            legacyMessages,
            committedMessages,
            pendingMessages
        ) { membership, legacy, committed, pending ->
            val canViewEncryptedMessages = membership?.let {
                it.isValid() &&
                    it.groupId == groupId &&
                    currentUserId in it.memberUserIds
            } == true

            val protectedIds = committed.mapTo(mutableSetOf()) { it.messageId } +
                pending.map { it.messageId }
            val legacyPreview = legacy.asSequence()
                .filterNot { it.id in protectedIds }
                .maxByOrNull { it.timestamp }
                ?.let { LocalConversationPreview(it.text, it.senderId, it.timestamp) }

            val encryptedPreview = if (canViewEncryptedMessages) {
                val committedPreview = committed.maxByOrNull { it.sentAtMillis }
                    ?.let { LocalConversationPreview(it.text, it.senderUserId, it.sentAtMillis) }
                val pendingPreview = pending.maxByOrNull { it.sentAtMillis }
                    ?.let { LocalConversationPreview(it.plaintext, currentUserId, it.sentAtMillis) }
                newestPreview(committedPreview, pendingPreview)
            } else {
                null
            }

            newestPreview(legacyPreview, encryptedPreview)
        }.distinctUntilChanged()
    }

    private fun newestPreview(
        first: LocalConversationPreview?,
        second: LocalConversationPreview?
    ): LocalConversationPreview? =
        listOfNotNull(first, second)
            .filter { it.text.isNotBlank() }
            .maxByOrNull { it.timestamp }

    /*
     * =========================================================
     * PER-USER INBOX ACTIONS
     * =========================================================
     */
    fun setConversationPinned(
        currentUserId: String,
        chatId: String,
        pinned: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            conversationPreferencesRepository
                .setPinned(currentUserId, chatId, pinned)
                .onSuccess {
                    val updated = _conversationPreferences.value.toMutableMap()
                    val current = updated[chatId] ?: ConversationPreference()
                    updated[chatId] = current.copy(isPinned = pinned)
                    _conversationPreferences.value = updated
                    publishConversations()
                    onResult(true, null)
                }
                .onFailure { error ->
                    onResult(
                        false,
                        error.message ?: "Could not update the pin."
                    )
                }
        }
    }

    fun setConversationHidden(
        currentUserId: String,
        chatId: String,
        hidden: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            conversationPreferencesRepository
                .setHidden(currentUserId, chatId, hidden)
                .onSuccess {
                    val updated = _conversationPreferences.value.toMutableMap()
                    val current = updated[chatId] ?: ConversationPreference()
                    updated[chatId] = current.copy(isHidden = hidden)
                    _conversationPreferences.value = updated
                    publishConversations()
                    onResult(true, null)
                }
                .onFailure { error ->
                    onResult(
                        false,
                        error.message ?: "Could not update hidden chats."
                    )
                }
        }
    }

    /*
     * =========================================================
     * DELETE CONVERSATION
     * =========================================================
     *
     * Removes the conversation only from the current user's
     * Recent Chats list.
     *
     * Actual messages are not deleted.
     */
    fun deleteConversation(
        currentUserId: String,
        chatId: String,
        chatType: String = "direct",
        onResult:
            (Boolean, String?) -> Unit
    ) {

        if (
            currentUserId.isBlank() ||
            chatId.isBlank()
        ) {

            onResult(
                false,
                "Invalid conversation"
            )

            return
        }

        viewModelScope.launch {

            val deleteResult =
                if (chatType == "group") {
                    groupChatRepository
                        .removeMyGroupMembership(
                            currentUserId = currentUserId,
                            groupId = chatId
                        )
                } else {
                    chatRepository
                        .deleteConversation(
                            currentUserId = currentUserId,
                            chatId = chatId
                        )
                }

            deleteResult
                .onSuccess {

                    /*
                     * Update local state immediately.
                     */
                    _directConversations.value =
                        _directConversations.value
                            .filterNot {
                                it.chatId ==
                                        chatId
                            }

                    _groupConversations.value =
                        _groupConversations.value
                            .filterNot {
                                it.chatId ==
                                        chatId
                            }

                    publishConversations()

                    onResult(
                        true,
                        null
                    )
                }
                .onFailure { error ->

                    onResult(
                        false,

                        error.message
                            ?: "Could not delete conversation"
                    )
                }
        }
    }

    /*
     * =========================================================
     * CLEANUP
     * =========================================================
     */
    override fun onCleared() {

        conversationsJob?.cancel()

        super.onCleared()
    }
}
