package com.example.inchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.ChatAppearanceRepository
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.data.repository.ChatRepository

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun ChatScreen(
    currentUserId: String,
    currentNickname: String,
    otherUserId: String,
    otherUserNickname: String,
    chatViewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onChatInfoClick: () -> Unit
) {

    val context =
        LocalContext.current

    val keyboardController =
        LocalSoftwareKeyboardController.current

    val appearanceRepository =
        remember {
            ChatAppearanceRepository()
        }

    val userRepository =
        remember {
            UserRepository()
        }

    var otherUserProfilePhoto by
    remember(
        otherUserId
    ) {
        mutableStateOf("")
    }

    val chatId =
        remember(
            currentUserId,
            otherUserId
        ) {

            ChatRepository()
                .getChatRoomId(
                    currentUserId,
                    otherUserId
                )
        }

    val chatThemeId by
    appearanceRepository
        .observeTheme(
            chatId
        )
        .collectAsState(
            initial =
                null
        )

    val chatTheme =
        chatThemeId?.let {
            ChatTheme.fromId(it)
        }

    LaunchedEffect(
        otherUserId
    ) {

        val user =
            userRepository
                .getUserByIdFast(
                    otherUserId
                )

        otherUserProfilePhoto =
            user
                ?.profilePhotoData
                ?.ifBlank {
                    user.profilePhotoUrl
                }
                .orEmpty()
    }

    LaunchedEffect(
        currentUserId,
        otherUserId
    ) {

        chatViewModel.startListening(

            currentUserId =
                currentUserId,

            otherUserId =
                otherUserId
        )
    }

    val messages by
    chatViewModel
        .messages
        .collectAsState()

    val messagesLoaded by
    chatViewModel
        .messagesLoaded
        .collectAsState()

    val otherUserReadTimestamp by
    chatViewModel
        .otherUserReadTimestamp
        .collectAsState()

    val otherUserPresence by
    chatViewModel
        .otherUserPresence
        .collectAsState()

    val otherUserTyping by
    chatViewModel
        .otherUserTyping
        .collectAsState()

    val blockState by
    chatViewModel
        .blockState
        .collectAsState()

    val isConnected by
    chatViewModel
        .isConnected
        .collectAsState()

    val readReceiptsEnabled by
    chatViewModel
        .readReceiptsEnabled
        .collectAsState()

    val pendingMessageIds by
    chatViewModel
        .pendingMessageIds
        .collectAsState()

    val replyingTo by
    chatViewModel
        .replyingTo
        .collectAsState()

    val editingMessage by
    chatViewModel
        .editingMessage
        .collectAsState()

    val messagingBlocked =
        blockState !=
                BlockState.NONE

    var messageText by
    remember {
        mutableStateOf("")
    }

    var messageToDelete by
    remember {
        mutableStateOf<Message?>(
            null
        )
    }

    var messageForActions by
    remember {
        mutableStateOf<Message?>(
            null
        )
    }

    var actionMessage by
    remember {
        mutableStateOf<String?>(
            null
        )
    }

    fun clearReplyMode() {

        chatViewModel
            .clearReplyingTo()

        messageText =
            ""

        keyboardController
            ?.hide()
    }

    fun clearEditMode() {

        chatViewModel
            .clearEditingMessage()

        messageText =
            ""

        keyboardController
            ?.hide()
    }

    /*
     * =========================================================
     * MESSAGE ACTIONS
     * =========================================================
     */
    if (
        messageForActions != null
    ) {

        val message =
            messageForActions!!

        ChatMessageActionsDialog(

            message =
                message,

            isOwnMessage =
                message.senderId ==
                        currentUserId,

            currentUserId =
                currentUserId,

            onReaction = {
                    reaction ->

                chatViewModel
                    .toggleReaction(

                        messageId =
                            message.id,

                        reaction =
                            reaction,

                        currentUserId =
                            currentUserId
                    )

                messageForActions =
                    null
            },

            onReply = {

                messageForActions =
                    null

                chatViewModel
                    .clearEditingMessage()

                messageText =
                    ""

                chatViewModel
                    .setReplyingTo(
                        message
                    )
            },

            onEdit = {

                messageForActions =
                    null

                chatViewModel
                    .setEditingMessage(

                        message =
                            message,

                        currentUserId =
                            currentUserId
                    )
            },

            onDelete = {

                messageForActions =
                    null

                messageToDelete =
                    message
            },

            onDismiss = {

                messageForActions =
                    null
            },

            onCopied = {

                messageForActions =
                    null

                actionMessage =
                    "Message copied"
            }
        )
    }

    /*
     * =========================================================
     * DELETE MESSAGE
     * =========================================================
     */
    if (
        messageToDelete != null
    ) {

        DeleteMessageDialog(

            onConfirm = {

                messageToDelete
                    ?.let { message ->

                        chatViewModel
                            .deleteMessage(
                                message.id
                            )
                    }

                messageToDelete =
                    null
            },

            onDismiss = {

                messageToDelete =
                    null
            }
        )
    }

    /*
     * =========================================================
     * ACTION MESSAGE
     * =========================================================
     */
    if (
        actionMessage != null
    ) {

        AlertDialog(

            onDismissRequest = {

                actionMessage =
                    null
            },

            title = {

                Text(
                    "InChat"
                )
            },

            text = {

                Text(
                    actionMessage!!
                )
            },

            confirmButton = {

                TextButton(

                    onClick = {

                        actionMessage =
                            null
                    }
                ) {

                    Text(
                        "OK"
                    )
                }
            }
        )
    }

    /*
     * =========================================================
     * MAIN SCREEN
     * =========================================================
     */
    androidx.compose.material3.Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background,

        topBar = {

            ChatTopBar(

                otherUserNickname =
                    otherUserNickname,

                otherUserProfilePhoto =
                    otherUserProfilePhoto,

                otherUserPresence =
                    otherUserPresence,

                otherUserTyping =
                    otherUserTyping,

                blockState =
                    blockState,

                onBackClick =
                    onBackClick,

                onChatInfoClick =
                    onChatInfoClick
            )
        },

        bottomBar = {

            ChatComposer(

                messageText =
                    messageText,

                onMessageTextChange = {
                        newText ->

                    if (
                        !messagingBlocked
                    ) {

                        messageText =
                            newText

                        if (
                            editingMessage ==
                            null
                        ) {

                            chatViewModel
                                .onTypingChanged(

                                    currentUserId =
                                        currentUserId,

                                    isTyping =
                                        newText.isNotBlank()
                                )
                        }
                    }
                },

                currentUserId =
                    currentUserId,

                currentNickname =
                    currentNickname,

                otherUserId =
                    otherUserId,

                otherUserNickname =
                    otherUserNickname,

                chatViewModel =
                    chatViewModel,

                blockState =
                    blockState,

                replyingTo =
                    replyingTo,

                editingMessage =
                    editingMessage,

                messagingBlocked =
                    messagingBlocked,

                onClearReply =
                    ::clearReplyMode,

                onClearEdit =
                    ::clearEditMode,

                onActionMessage = {
                        message ->

                    actionMessage =
                        message
                },

                onMessageSent = {

                    messageText =
                        ""

                    chatViewModel
                        .onTypingChanged(

                            currentUserId =
                                currentUserId,

                            isTyping =
                                false
                        )

                    chatViewModel
                        .clearReplyingTo()
                }
            )
        }

    ) { innerPadding ->

        if (chatTheme != null) {
            ChatMessageList(

                messages =
                    messages,

                messagesLoaded =
                    messagesLoaded,

                currentUserId =
                    currentUserId,

                otherUserNickname =
                    otherUserNickname,

                blockState =
                    blockState,

                messagingBlocked =
                    messagingBlocked,

                otherUserReadTimestamp =
                    otherUserReadTimestamp,

                pendingMessageIds =
                    pendingMessageIds,

                isConnected =
                    isConnected,

                readReceiptsEnabled =
                    readReceiptsEnabled,

                chatTheme =
                    chatTheme,

                onReply = {
                        message ->

                    if (
                        !messagingBlocked
                    ) {

                        chatViewModel
                            .clearEditingMessage()

                        messageText =
                            ""

                        chatViewModel
                            .setReplyingTo(
                                message
                            )
                    }
                },

                onLongClick = {
                        message ->

                    if (
                        !messagingBlocked
                    ) {

                        messageForActions =
                            message
                    }
                },

                onReactionClick = {
                        message,
                        reaction ->

                    if (
                        !messagingBlocked
                    ) {

                        chatViewModel
                            .toggleReaction(
                                messageId =
                                    message.id,
                                reaction =
                                    reaction,
                                currentUserId =
                                    currentUserId
                            )
                    }
                },

                innerPadding =
                    innerPadding
            )
        } else {
            androidx.compose.foundation.layout.Box(
                modifier =
                    androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .padding(
                            innerPadding
                        )
            )
        }
    }
}