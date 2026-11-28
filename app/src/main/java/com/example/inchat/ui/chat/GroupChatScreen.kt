package com.example.inchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.Info
import com.example.inchat.ui.components.InChatActionDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.example.inchat.ui.theme.GoogleSansFlex
import com.example.inchat.ui.theme.InChatMotion

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

    DisposableEffect(groupId, currentUserId) {
        ActiveConversationTracker.setActiveChat(groupId, currentUserId)
        onDispose {
            ActiveConversationTracker.clearActiveChat(groupId, currentUserId)
        }
    }

    val chatTheme = ChatTheme.BITCHAT_GREEN

    val group by groupViewModel.group.collectAsState()
    val messages by groupViewModel.messages.collectAsState()
    val encryptedMessages by groupViewModel.encryptedMessages.collectAsState()
    val pendingEncryptedMessages by groupViewModel.pendingEncryptedMessages.collectAsState()
    val encryptedMessageIds = remember(messages, encryptedMessages, pendingEncryptedMessages) {
        val legacyIds = messages.mapTo(mutableSetOf()) { it.id }
        buildSet {
            encryptedMessages.forEach { encrypted ->
                if (encrypted.messageId !in legacyIds) add(encrypted.messageId)
            }
            pendingEncryptedMessages.forEach { pending ->
                if (pending.messageId !in legacyIds) add(pending.messageId)
            }
        }
    }
    val displayMessages = remember(messages, encryptedMessages, pendingEncryptedMessages) {
        val legacyIds = messages.mapTo(mutableSetOf()) { it.id }
        val committedEncryptedIds = encryptedMessages.mapTo(mutableSetOf()) { it.messageId }
        val encryptedRows = encryptedMessages
            .filterNot { it.messageId in legacyIds }
            .map { encrypted ->
                Message(
                    id = encrypted.messageId,
                    senderId = encrypted.senderUserId,
                    senderNickname = "🔒 ${encrypted.senderUserId}",
                    text = encrypted.text,
                    timestamp = encrypted.sentAtMillis,
                )
            }
        // A pending local intent is shown immediately while key distribution
        // waits for member-device acknowledgements. The committed encrypted row
        // replaces it once the outbox transaction completes.
        val pendingRows = pendingEncryptedMessages
            .filterNot {
                it.messageId in legacyIds || it.messageId in committedEncryptedIds
            }
            .map { pending ->
                Message(
                    id = pending.messageId,
                    senderId = currentUserId,
                    senderNickname = "🔒 $currentNickname",
                    text = pending.plaintext,
                    timestamp = pending.sentAtMillis,
                )
            }
        (messages + encryptedRows + pendingRows).sortedWith(
            compareBy<Message> { it.timestamp }.thenBy { it.id },
        )
    }
    val messagesLoaded by groupViewModel.messagesLoaded.collectAsState()
    val hasOlderMessages by groupViewModel.hasOlderMessages.collectAsState()
    val isLoadingOlderMessages by groupViewModel.isLoadingOlderMessages.collectAsState()
    val replyingTo by groupViewModel.replyingTo.collectAsState()
    val editingMessage by groupViewModel.editingMessage.collectAsState()
    val pendingMessageIds by groupViewModel.pendingMessageIds.collectAsState()
    val isConnected by groupViewModel.isConnected.collectAsState()
    val groupReadTimestamps by groupViewModel.groupReadTimestamps.collectAsState()

    LaunchedEffect(displayMessages, currentUserId) {
        val newestTimestamp =
            displayMessages
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
    var actionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearChatConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeleteSelectedForMeConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeleteSelectedForEveryoneConfirmation by rememberSaveable { mutableStateOf(false) }
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    fun toggleMessageSelection(message: Message) {
        if (message.id in encryptedMessageIds) return
        selectedMessageIds = if (message.id in selectedMessageIds) {
            selectedMessageIds - message.id
        } else {
            selectedMessageIds + message.id
        }
    }

    BackHandler {
        if (selectedMessageIds.isNotEmpty()) {
            selectedMessageIds = emptySet()
        } else {
            onBackClick()
        }
    }

    val actionTarget =
        messageForActions?.let { id ->
            displayMessages.firstOrNull { it.id == id }
        }

    actionTarget?.let { message ->
        ChatMessageActionsDialog(
            message = message,
            isOwnMessage = message.senderId == currentUserId,
            isEncryptedMessage = message.id in encryptedMessageIds,
            readTimestamp =
                groupReadTimestamps.values
                    .filter { it >= message.timestamp && message.timestamp > 0L }
                    .maxOrNull()
                    ?: 0L,
            seenByCount =
                if (message.senderId == currentUserId && message.timestamp > 0L) {
                    groupReadTimestamps.values.count { it >= message.timestamp }
                } else {
                    0
                },
            onReply = {
                messageForActions = null
                draftBeforeEdit = null
                messageText = ""
                groupViewModel.setReplyingTo(message)
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
            onDismiss = {
                messageForActions = null
            },
            onCopied = { }
        )
    }

    actionMessage?.let { message ->
        InChatActionDialog(
            title = "InChat",
            description = message,
            confirmLabel = "OK",
            confirmIcon = Icons.Outlined.Info,
            confirmDestructive = false,
            showCancel = false,
            onConfirm = { actionMessage = null },
            onDismiss = { actionMessage = null }
        )
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            AnimatedContent(
                targetState = selectedMessageIds.isNotEmpty(),
                transitionSpec = {
                    (
                        fadeIn(animationSpec = InChatMotion.effects(150)) +
                            slideInVertically(
                                animationSpec = InChatMotion.navigationSpring(),
                                initialOffsetY = { it / 8 }
                            )
                        ) togetherWith (
                        fadeOut(animationSpec = InChatMotion.effects(110)) +
                            slideOutVertically(
                                animationSpec = InChatMotion.navigationSpring(),
                                targetOffsetY = { -it / 8 }
                            )
                        )
                },
                label = "groupChatTopBar"
            ) { selectingMessages ->
                if (selectingMessages) {
                    val selectedMessages = messages.filter { it.id in selectedMessageIds }
                    val selectedAreAllOwn =
                        selectedMessages.size == selectedMessageIds.size &&
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
                                    AnimatedContent(
                                        targetState = group?.name?.ifBlank { "Group" } ?: "Group",
                                        transitionSpec = {
                                            (
                                                fadeIn(animationSpec = InChatMotion.effects(150)) +
                                                    slideInVertically(
                                                        animationSpec = InChatMotion.navigationSpring(),
                                                        initialOffsetY = { it / 5 }
                                                    )
                                                ) togetherWith (
                                                fadeOut(animationSpec = InChatMotion.effects(110)) +
                                                    slideOutVertically(
                                                        animationSpec = InChatMotion.navigationSpring(),
                                                        targetOffsetY = { -it / 5 }
                                                    )
                                                )
                                        },
                                        label = "groupHeaderName"
                                    ) { name ->
                                        Text(
                                            text = name,
                                            fontFamily = GoogleSansFlex,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1
                                        )
                                    }
                                    AnimatedContent(
                                        targetState = group?.members?.size ?: 0,
                                        transitionSpec = {
                                            fadeIn(animationSpec = InChatMotion.effects(130)) togetherWith
                                                fadeOut(animationSpec = InChatMotion.effects(100))
                                        },
                                        label = "groupMemberCount"
                                    ) { memberCount ->
                                        Text(
                                            text = "$memberCount members",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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
                                onDismissRequest = { overflowExpanded = false },
                                modifier = Modifier.border(
                                    BorderStroke(
                                        0.8.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    RoundedCornerShape(16.dp)
                                ),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 2.dp
                            ) {
                                DropdownMenuItem(
                                    modifier = Modifier,
                                    text = { Text("Clear chat", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteSweep,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
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
                messages = displayMessages,
                messagesLoaded = messagesLoaded,
                hasOlderMessages = hasOlderMessages,
                isLoadingOlderMessages = isLoadingOlderMessages,
                onLoadOlderMessages = groupViewModel::loadOlderMessages,
                currentUserId = currentUserId,
                otherUserNickname = group?.name ?: "group",
                blockState = BlockState.NONE,
                messagingBlocked = false,
                otherUserDeliveredTimestamp = 0L,
                otherUserReadTimestamp = group?.members?.keys
                    .orEmpty()
                    .filter { it != currentUserId }
                    .map { memberId -> groupReadTimestamps[memberId] ?: 0L }
                    .takeIf { it.isNotEmpty() }
                    ?.minOrNull()
                    ?: 0L,
                pendingMessageIds = pendingMessageIds,
                isConnected = isConnected,
                showSenderNames = true,
                chatTheme = chatTheme,
                onReply = { message ->
                    if (message.id !in encryptedMessageIds) {
                        groupViewModel.setReplyingTo(message)
                    }
                },
                onLongClick = { message ->
                    // A not-yet-distributed encrypted intent is immutable while
                    // its key-acknowledgement gate is pending.
                    if (message.id !in pendingMessageIds) {
                        messageForActions = message.id
                    }
                },
                onReactionClick = { message, reaction ->
                    if (message.id !in encryptedMessageIds) {
                        groupViewModel.toggleReaction(
                            messageId = message.id,
                            reaction = reaction,
                            currentUserId = currentUserId
                        )
                    }
                },
                innerPadding = innerPadding,
                selectedMessageIds = selectedMessageIds,
                onToggleMessageSelection = ::toggleMessageSelection
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                ChatConversationLoadingState(
                    title = "Opening group",
                    description = "Loading group details and messages"
                )
            }
        }

        if (showClearChatConfirmation) {
            ClearChatConfirmationDialog(
                title = "Clear group chat?",
                description = "This removes the group history from your view only. Other group members will keep their messages.",
                onConfirm = {
                    showClearChatConfirmation = false
                    groupViewModel.clearChatForMe { success, error ->
                        actionMessage = if (success) {
                            "Group chat cleared for you"
                        } else {
                            error ?: "Could not clear group chat."
                        }
                    }
                },
                onDismiss = { showClearChatConfirmation = false }
            )
        }

            if (showDeleteSelectedForMeConfirmation) {
            InChatActionDialog(
                title = "Delete selected messages for you?",
                description = "These messages will disappear only from your view. Other group members will keep them.",
                titleIcon = Icons.Outlined.DeleteSweep,
                confirmLabel = "Delete for me",
                confirmIcon = Icons.Outlined.DeleteSweep,
                onConfirm = {
                    showDeleteSelectedForMeConfirmation = false
                    val ids = selectedMessageIds
                    groupViewModel.deleteMessagesForMe(ids) { success, error ->
                        selectedMessageIds = emptySet()
                        if (!success) actionMessage = error ?: "Could not delete selected messages."
                    }
                },
                onDismiss = { showDeleteSelectedForMeConfirmation = false }
            )
        }

    if (showDeleteSelectedForEveryoneConfirmation) {
        InChatActionDialog(
            title = "Delete selected messages?",
            description = "These messages will be removed for all group members. This cannot be undone.",
            titleIcon = Icons.Outlined.DeleteSweep,
            confirmLabel = "Delete for everyone",
            confirmIcon = Icons.Outlined.DeleteSweep,
            onConfirm = {
                showDeleteSelectedForEveryoneConfirmation = false
                val ids = selectedMessageIds
                groupViewModel.deleteMessagesForEveryone(ids, currentUserId) { success, error ->
                    selectedMessageIds = emptySet()
                    if (!success) actionMessage = error ?: "Could not delete selected messages."
                }
            },
            onDismiss = { showDeleteSelectedForEveryoneConfirmation = false }
        )
    }
    }
}