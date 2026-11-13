package com.example.inchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.ChatAppearanceRepository
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.notification.ActiveConversationTracker
import kotlinx.coroutines.flow.flowOf

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

    val readyChatId by
    chatViewModel
        .readyChatId
        .collectAsState()

    val chatInitializationError by
    chatViewModel
        .chatInitializationError
        .collectAsState()

    // Do not reuse the previous conversation's ready state during navigation.
    val chatReady = readyChatId == chatId

    /*
     * The first-time chat record must exist before subscribing to
     * themeId. Theme reads are protected by the chat participant rules.
     */
    val chatThemeFlow =
        remember(chatId, chatReady) {
            if (chatReady) {
                appearanceRepository.observeTheme(chatId)
            } else {
                flowOf(null)
            }
        }

    val chatThemeId by
    chatThemeFlow.collectAsState(
        initial = null
    )

    val chatTheme =
        ChatTheme.fromId(
            id = chatThemeId,
            defaultTheme = ChatTheme.BITCHAT_GREEN
        )

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
        otherUserId,
        otherUserNickname
    ) {

        chatViewModel.startListening(

            currentUserId =
                currentUserId,

            otherUserId =
                otherUserId,

            otherUserNickname =
                otherUserNickname
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

    val hasOlderMessages by
        chatViewModel.hasOlderMessages.collectAsState()

    val isLoadingOlderMessages by
        chatViewModel.isLoadingOlderMessages.collectAsState()

    DisposableEffect(chatViewModel, currentUserId, chatId) {
        ActiveConversationTracker.setActiveChat(chatId)
        chatViewModel.setChatVisible(currentUserId, true)
        onDispose {
            ActiveConversationTracker.clearActiveChat(chatId)
            chatViewModel.setChatVisible(currentUserId, false)
        }
    }

    val otherUserDeliveredTimestamp by chatViewModel.otherUserDeliveredTimestamp.collectAsState()

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

    var draftBeforeEdit by remember {
        mutableStateOf<String?>(null)
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

    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearChatConfirmation by remember { mutableStateOf(false) }
    var showDeleteSelectedForMeConfirmation by remember { mutableStateOf(false) }
    var showDeleteSelectedForEveryoneConfirmation by remember { mutableStateOf(false) }

    fun toggleMessageSelection(message: Message) {
        selectedMessageIds = if (message.id in selectedMessageIds) {
            selectedMessageIds - message.id
        } else {
            selectedMessageIds + message.id
        }
    }

    LaunchedEffect(chatInitializationError) {
        if (!chatInitializationError.isNullOrBlank()) {
            actionMessage =
                "Couldn't open this conversation: $chatInitializationError"
        }
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
        chatViewModel.clearEditingMessage()
        messageText = draftBeforeEdit.orEmpty()
        draftBeforeEdit = null
        keyboardController?.hide()
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
            deliveredTimestamp = otherUserDeliveredTimestamp,
            readTimestamp = otherUserReadTimestamp,
            onReply = {

                messageForActions =
                    null

                chatViewModel.clearEditingMessage()
                draftBeforeEdit = null
                messageText = ""

                chatViewModel
                    .setReplyingTo(
                        message
                    )
            },

            onEdit = {
                messageForActions = null

                if (draftBeforeEdit == null) {
                    draftBeforeEdit = messageText
                }
                messageText = message.text

                chatViewModel.setEditingMessage(
                    message = message,
                    currentUserId = currentUserId
                )
            },

            onDeleteForMe = {
                messageForActions = null
                chatViewModel.deleteMessagesForMe(setOf(message.id)) { success, error ->
                    if (!success) actionMessage = error ?: "Could not delete message for you."
                }
            },

            onDeleteForEveryone = {
                messageForActions = null
                chatViewModel.deleteMessagesForEveryone(
                    messageIds = setOf(message.id),
                    currentUserId = currentUserId
                ) { success, error ->
                    if (!success) actionMessage = error ?: "Could not delete message for everyone."
                }
            },

            onSelectMessages = {
                messageForActions = null
                toggleMessageSelection(message)
            },

            onDismiss = {

                messageForActions =
                    null
            },

            onCopied = {

                messageForActions =
                    null

                Toast.makeText(context, "✓ Copied", Toast.LENGTH_SHORT).show()
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

        modifier = Modifier.imePadding(),

        containerColor =
            MaterialTheme
                .colorScheme
                .background,

        topBar = {
            if (selectedMessageIds.isNotEmpty()) {
                val selectedMessages = messages.filter { it.id in selectedMessageIds }
                val selectedAreAllOwn = selectedMessages.size == selectedMessageIds.size &&
                    selectedMessages.all { it.senderId == currentUserId }
                MessageSelectionTopBar(
                    selectedCount = selectedMessageIds.size,
                    canDeleteForEveryone = selectedAreAllOwn,
                    onClose = { selectedMessageIds = emptySet() },
                    onCopy = {
                        val copiedText = selectedMessages
                            .sortedBy { it.timestamp }
                            .mapNotNull { it.text.takeIf(String::isNotBlank) }
                            .joinToString("\n")
                        if (copiedText.isNotBlank()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("InChat messages", copiedText))
                            Toast.makeText(context, "✓ Copied", Toast.LENGTH_SHORT).show()
                        }
                        selectedMessageIds = emptySet()
                    },
                    onDeleteForMe = {
                        showDeleteSelectedForMeConfirmation = true
                    },
                    onDeleteForEveryone = {
                        showDeleteSelectedForEveryoneConfirmation = true
                    }
                )
            } else {
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
                    onChatInfoClick,
                onClearChatClick = {
                    showClearChatConfirmation = true
                }
            )
            }
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

                chatReady =
                    chatReady,

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

                hasOlderMessages = hasOlderMessages,
                isLoadingOlderMessages = isLoadingOlderMessages,
                onLoadOlderMessages = chatViewModel::loadOlderMessages,

                currentUserId =
                    currentUserId,

                otherUserNickname =
                    otherUserNickname,

                blockState =
                    blockState,

                messagingBlocked =
                    messagingBlocked,

                otherUserDeliveredTimestamp = otherUserDeliveredTimestamp,

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
                    innerPadding,

                selectedMessageIds = selectedMessageIds,
                onToggleMessageSelection = ::toggleMessageSelection
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

    if (showClearChatConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirmation = false },
            title = { Text("Clear chat?") },
            text = {
                Text("This removes the conversation history from your view only. Other participants will keep their messages.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearChatConfirmation = false
                        chatViewModel.clearChatForMe { success, error ->
                            if (success) {
                                actionMessage = "Chat cleared for you"
                            } else {
                                actionMessage = error ?: "Could not clear chat."
                            }
                        }
                    }
                ) { Text("Clear chat", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteSelectedForMeConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedForMeConfirmation = false },
            title = { Text("Delete selected messages for you?") },
            text = {
                Text("These messages will disappear only from your chat. Other participants will keep them.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteSelectedForMeConfirmation = false
                        val ids = selectedMessageIds
                        chatViewModel.deleteMessagesForMe(ids) { success, error ->
                            selectedMessageIds = emptySet()
                            if (!success) actionMessage = error ?: "Could not delete selected messages."
                        }
                    }
                ) { Text("Delete for me", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedForMeConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteSelectedForEveryoneConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedForEveryoneConfirmation = false },
            title = { Text("Delete selected messages?") },
            text = {
                Text("These messages will be removed for every participant. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteSelectedForEveryoneConfirmation = false
                        val ids = selectedMessageIds
                        chatViewModel.deleteMessagesForEveryone(ids, currentUserId) { success, error ->
                            selectedMessageIds = emptySet()
                            if (!success) actionMessage = error ?: "Could not delete selected messages."
                        }
                    }
                ) { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedForEveryoneConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    }
}