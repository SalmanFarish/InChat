package com.example.inchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.example.inchat.notification.ActiveConversationTracker
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.FirebaseServerClock
import com.example.inchat.ui.theme.JetBrainsMono

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun GroupChatScreen(
    currentUserId: String,
    currentNickname: String,
    groupId: String,
    groupViewModel: GroupChatViewModel,
    onBackClick: () -> Unit,
    onGroupInfoClick: () -> Unit = {}
) {

    LaunchedEffect(groupId, currentUserId) {
        groupViewModel.startListening(
            groupId = groupId,
            currentUserId = currentUserId
        )
    }

    DisposableEffect(groupId) {
        ActiveConversationTracker.setActiveChat(groupId)
        onDispose {
            ActiveConversationTracker.clearActiveChat(groupId)
        }
    }

    val chatTheme = ChatTheme.BITCHAT_GREEN

    val group by groupViewModel.group.collectAsState()
    val messages by groupViewModel.messages.collectAsState()
    val messagesLoaded by groupViewModel.messagesLoaded.collectAsState()
    val hasOlderMessages by groupViewModel.hasOlderMessages.collectAsState()
    val isLoadingOlderMessages by groupViewModel.isLoadingOlderMessages.collectAsState()
    val replyingTo by groupViewModel.replyingTo.collectAsState()
    val editingMessage by groupViewModel.editingMessage.collectAsState()
    val pendingMessageIds by groupViewModel.pendingMessageIds.collectAsState()
    val isConnected by groupViewModel.isConnected.collectAsState()
    val groupReadTimestamps by groupViewModel.groupReadTimestamps.collectAsState()

    LaunchedEffect(messages, currentUserId) {
        val newestTimestamp =
            messages
                .filter { it.senderId != currentUserId }
                .maxOfOrNull { it.timestamp }
                ?: 0L

        if (newestTimestamp > 0L) {
            groupViewModel.markGroupRead(
                currentUserId = currentUserId,
                timestamp = newestTimestamp
            )
        }
    }

    var messageText by rememberSaveable { mutableStateOf("") }
    var draftBeforeEdit by rememberSaveable { mutableStateOf<String?>(null) }
    var messageForActions by rememberSaveable { mutableStateOf<String?>(null) }
    var messageForInfo by rememberSaveable { mutableStateOf<String?>(null) }
    var actionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var messageForTextSelection by remember { mutableStateOf<Message?>(null) }
    var showClearChatConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeleteSelectedForMeConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeleteSelectedForEveryoneConfirmation by rememberSaveable { mutableStateOf(false) }
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    fun toggleMessageSelection(message: Message) {
        selectedMessageIds = if (message.id in selectedMessageIds) {
            selectedMessageIds - message.id
        } else {
            selectedMessageIds + message.id
        }
    }

    val actionTarget =
        messageForActions?.let { id ->
            messages.firstOrNull { it.id == id }
        }

    actionTarget?.let { message ->
        ChatMessageActionsDialog(
            message = message,
            isOwnMessage = message.senderId == currentUserId,
            onReply = {
                messageForActions = null
                draftBeforeEdit = null
                messageText = ""
                groupViewModel.setReplyingTo(message)
            },
            onInfo = {
                messageForActions = null
                messageForInfo = message.id
            },
            onEdit = {
                messageForActions = null
                if (draftBeforeEdit == null) {
                    draftBeforeEdit = messageText
                }
                messageText = message.text
                groupViewModel.setEditingMessage(
                    message = message,
                    currentUserId = currentUserId
                )
            },
            onDeleteForMe = {
                messageForActions = null
                groupViewModel.deleteMessagesForMe(setOf(message.id)) { success, error ->
                    if (!success) actionMessage = error ?: "Could not delete message for you."
                }
            },
            onDeleteForEveryone = {
                messageForActions = null
                groupViewModel.deleteMessagesForEveryone(
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
            onSelectText = {
                messageForActions = null
                messageForTextSelection = message
            },
            onDismiss = {
                messageForActions = null
            },
            onCopied = {
                messageForActions = null
            }
        )
    }

    messageForTextSelection?.let { message ->
        SelectMessageTextDialog(
            message = message,
            onDismiss = { messageForTextSelection = null }
        )
    }

    val infoTarget =
        messageForInfo?.let { id ->
            messages.firstOrNull { it.id == id }
        }

    infoTarget?.let { message ->
        MessageInfoDialog(
            message = message,
            isOwnMessage =
                message.senderId == currentUserId,
            readTimestamp =
                groupReadTimestamps.values
                    .filter { it >= message.timestamp && message.timestamp > 0L }
                    .maxOrNull()
                    ?: 0L,
            seenByCount =
                if (message.senderId == currentUserId && message.timestamp > 0L) {
                    groupReadTimestamps.values.count {
                        it >= message.timestamp
                    }
                } else {
                    0
                },
            currentTimeMillis =
                FirebaseServerClock.now(),
            onDismiss = {
                messageForInfo = null
            }
        )
    }

    actionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { actionMessage = null },
            title = { Text("InChat") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = { actionMessage = null }
                ) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
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
                            actionMessage = "Messages copied"
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
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Row(
                        modifier = Modifier
                            .clickable { onGroupInfoClick() }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GroupAvatar(
                            groupName = group?.name ?: "Group",
                            modifier = Modifier.size(40.dp),
                            fontSize = 20.sp
                        )

                        Spacer(Modifier.width(10.dp))

                        Column {
                            Text(
                                text = group?.name?.ifBlank { "Group" } ?: "Group",
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = (group?.members?.size ?: 0).toString() + " members",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More group chat options"
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear chat") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                overflowExpanded = false
                                showClearChatConfirmation = true
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
            }
        },
        bottomBar = {
            if (group != null) {
                GroupChatComposer(
                    messageText = messageText,
                    onMessageTextChange = { messageText = it },
                    currentUserId = currentUserId,
                    currentNickname = currentNickname,
                    groupViewModel = groupViewModel,
                    replyingTo = replyingTo,
                    editingMessage = editingMessage,
                    onClearReply = {
                        groupViewModel.clearReplyingTo()
                        messageText = ""
                    },
                    onClearEdit = {
                        groupViewModel.clearEditingMessage()
                        messageText = draftBeforeEdit.orEmpty()
                        draftBeforeEdit = null
                    },
                    onActionMessage = { actionMessage = it },
                    onMessageSent = { messageText = "" }
                )
            }
        }
    ) { innerPadding ->

        if (group != null || messages.isNotEmpty()) {
            ChatMessageList(
                messages = messages,
                messagesLoaded = messagesLoaded,
                hasOlderMessages = hasOlderMessages,
                isLoadingOlderMessages = isLoadingOlderMessages,
                onLoadOlderMessages = groupViewModel::loadOlderMessages,
                currentUserId = currentUserId,
                otherUserNickname = group?.name ?: "group",
                blockState = BlockState.NONE,
                messagingBlocked = false,
                otherUserDeliveredTimestamp = 0L,
                otherUserReadTimestamp =
                    groupReadTimestamps.values.maxOrNull() ?: 0L,
                pendingMessageIds = pendingMessageIds,
                isConnected = isConnected,
                showSenderNames = true,
                chatTheme = chatTheme,
                onReply = { message ->
                    groupViewModel.setReplyingTo(message)
                },
                onLongClick = { message ->
                    messageForActions = message.id
                },
                onReactionClick = { message, reaction ->
                    groupViewModel.toggleReaction(
                        messageId = message.id,
                        reaction = reaction,
                        currentUserId = currentUserId
                    )
                },
                innerPadding = innerPadding,
                selectedMessageIds = selectedMessageIds,
                onToggleMessageSelection = ::toggleMessageSelection
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading group...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showClearChatConfirmation) {
            AlertDialog(
                onDismissRequest = { showClearChatConfirmation = false },
                title = { Text("Clear group chat?") },
                text = {
                    Text("This removes the group history from your view only. Other members will keep their messages.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearChatConfirmation = false
                            groupViewModel.clearChatForMe { success, error ->
                                actionMessage = if (success) {
                                    "Group chat cleared for you"
                                } else {
                                    error ?: "Could not clear group chat."
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
                    Text("These messages will disappear only from your view. Other group members will keep them.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteSelectedForMeConfirmation = false
                            val ids = selectedMessageIds
                            groupViewModel.deleteMessagesForMe(ids) { success, error ->
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
                    Text("These messages will be removed for all group members. This cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteSelectedForEveryoneConfirmation = false
                            val ids = selectedMessageIds
                            groupViewModel.deleteMessagesForEveryone(ids, currentUserId) { success, error ->
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