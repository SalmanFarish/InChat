package com.example.inchat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.model.Conversation
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.GroupChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val chatRepository =
        ChatRepository()

    private val groupChatRepository =
        GroupChatRepository()

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
                        .collect { list ->

                            _groupConversations.value =
                                list

                            _groupConversationsLoaded.value =
                                true

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
                .sortedByDescending {
                    it.lastTimestamp
                }
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
