package com.example.inchat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.model.Conversation
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.GroupChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        _conversationsLoaded.value =
            false

        conversationsJob?.cancel()

        conversationsJob =
            viewModelScope.launch {

                combine(
                    chatRepository
                        .getConversationsFlow(currentUserId)
                        .map { list ->
                            list.filter {
                                it.chatType != "group"
                            }
                        },
                    groupChatRepository
                        .observeGroupConversations(currentUserId)
                ) { directConversations, groupConversations ->
                    (directConversations + groupConversations)
                        .distinctBy { it.chatId }
                        .sortedByDescending { it.lastTimestamp }
                        .take(50)
                }.collect { list ->

                        _conversations.value =
                            list

                        _conversationsLoaded.value =
                            true
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
                    _conversations.value =
                        _conversations.value
                            .filterNot {
                                it.chatId ==
                                        chatId
                            }

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