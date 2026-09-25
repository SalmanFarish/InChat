package com.example.inchat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.repository.ChatAppearanceRepository
import com.example.inchat.ui.profile.InChatProfileAvatar

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

    val appearanceRepository =
        remember { ChatAppearanceRepository() }

    val selectedThemeId by
        appearanceRepository
            .observeTheme(groupId)
            .collectAsState(initial = null)

    val chatTheme =
        ChatTheme.fromId(selectedThemeId)

    val group by groupViewModel.group.collectAsState()
    val messages by groupViewModel.messages.collectAsState()
    val messagesLoaded by groupViewModel.messagesLoaded.collectAsState()
    val replyingTo by groupViewModel.replyingTo.collectAsState()
    val editingMessage by groupViewModel.editingMessage.collectAsState()
    val pendingMessageIds by groupViewModel.pendingMessageIds.collectAsState()

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
    var messageForActions by rememberSaveable { mutableStateOf<String?>(null) }
    var actionMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val actionTarget =
        messageForActions?.let { id ->
            messages.firstOrNull { it.id == id }
        }

    actionTarget?.let { message ->
        ChatMessageActionsDialog(
            message = message,
            isOwnMessage = message.senderId == currentUserId,
            currentUserId = currentUserId,
            onReaction = { reaction ->
                groupViewModel.toggleReaction(
                    messageId = message.id,
                    reaction = reaction,
                    currentUserId = currentUserId
                )
                messageForActions = null
            },
            onReply = {
                messageForActions = null
                groupViewModel.setReplyingTo(message)
            },
            onEdit = {
                messageForActions = null
                groupViewModel.setEditingMessage(
                    message = message,
                    currentUserId = currentUserId
                )
            },
            onDelete = {
                messageForActions = null
                groupViewModel.deleteMessage(message.id) { success, error ->
                    if (!success) {
                        actionMessage =
                            error ?: "Could not delete message."
                    }
                }
            },
            onDismiss = {
                messageForActions = null
            },
            onCopied = {
                messageForActions = null
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
        topBar = {
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
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            if (group?.groupPhotoData?.isNotBlank() == true) {
                                InChatProfileAvatar(
                                    profilePhotoUrl = group?.groupPhotoData.orEmpty(),
                                    modifier = Modifier.fillMaxSize(),
                                    iconSize = 22.dp,
                                    contentDescription = "Group photo"
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Groups,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(10.dp))

                        Column {
                            Text(
                                text = group?.name?.ifBlank { "Group" } ?: "Group",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = (group?.members?.size ?: 0).toString() + " members",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
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
                        messageText = ""
                    },
                    onActionMessage = { actionMessage = it },
                    onMessageSent = { messageText = "" }
                )
            }
        }
    ) { innerPadding ->

        if (group != null) {
            ChatMessageList(
                messages = messages,
                messagesLoaded = messagesLoaded,
                currentUserId = currentUserId,
                otherUserNickname = group?.name ?: "group",
                blockState = BlockState.NONE,
                messagingBlocked = false,
                otherUserReadTimestamp = 0L,
                pendingMessageIds = pendingMessageIds,
                isConnected = true,
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
                innerPadding = innerPadding
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
    }
}