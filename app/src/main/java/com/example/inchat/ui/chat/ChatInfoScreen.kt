package com.example.inchat.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import com.example.inchat.ui.theme.GoogleSansFlex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(
    currentUserId: String,
    otherUserId: String,
    otherUserNickname: String,
    chatViewModel: ChatViewModel,
    onBackClick: () -> Unit
) {
    val userRepository = remember { UserRepository() }
    var otherUserProfilePhoto by remember(otherUserId) { mutableStateOf("") }

    LaunchedEffect(otherUserId) {
        val user = userRepository.getUserByIdFast(otherUserId)
        otherUserProfilePhoto = user
            ?.profilePhotoData
            ?.ifBlank { user.profilePhotoUrl }
            .orEmpty()
    }

    val blockState by chatViewModel.blockState.collectAsState()
    val otherUserPresence by chatViewModel.otherUserPresence.collectAsState()

    LaunchedEffect(currentUserId, otherUserId) {
        chatViewModel.startListening(
            currentUserId = currentUserId,
            otherUserId = otherUserId
        )
    }

    var showBlockDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = {
                Text(
                    when (blockState) {
                        BlockState.NONE -> "Block $otherUserNickname?"
                        BlockState.I_BLOCKED_THEM -> "Unblock $otherUserNickname?"
                        BlockState.THEY_BLOCKED_ME -> "You can't unblock this user"
                    }
                )
            },
            text = {
                Text(
                    when (blockState) {
                        BlockState.NONE ->
                            "They won't be able to message you, and you won't receive their chat activity."
                        BlockState.I_BLOCKED_THEM ->
                            "This will allow messages from this person again."
                        BlockState.THEY_BLOCKED_ME ->
                            "This person has blocked you. Only they can change that."
                    }
                )
            },
            confirmButton = {
                when (blockState) {
                    BlockState.NONE -> {
                        TextButton(
                            onClick = {
                                chatViewModel.blockUser(
                                    currentUserId = currentUserId,
                                    otherUserId = otherUserId,
                                    otherUsername = otherUserNickname
                                ) { success, error ->
                                    actionMessage = if (success) {
                                        "User blocked"
                                    } else {
                                        error ?: "Could not block user"
                                    }
                                }
                                showBlockDialog = false
                            }
                        ) {
                            Text("Block", color = MaterialTheme.colorScheme.error)
                        }
                    }

                    BlockState.I_BLOCKED_THEM -> {
                        TextButton(
                            onClick = {
                                chatViewModel.unblockUser(
                                    currentUserId = currentUserId,
                                    otherUserId = otherUserId
                                ) { success, error ->
                                    actionMessage = if (success) {
                                        "User unblocked"
                                    } else {
                                        error ?: "Could not unblock user"
                                    }
                                }
                                showBlockDialog = false
                            }
                        ) {
                            Text("Unblock")
                        }
                    }

                    BlockState.THEY_BLOCKED_ME -> Unit
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Report $otherUserNickname") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Choose the reason that best describes the issue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(7.dp))
                    ChatReportReason(
                        label = "Spam",
                        selected = reportReason == "Spam",
                        onClick = { reportReason = "Spam" }
                    )
                    ChatReportReason(
                        label = "Harassment",
                        selected = reportReason == "Harassment",
                        onClick = { reportReason = "Harassment" }
                    )
                    ChatReportReason(
                        label = "Inappropriate content",
                        selected = reportReason == "Inappropriate content",
                        onClick = { reportReason = "Inappropriate content" }
                    )
                    ChatReportReason(
                        label = "Other",
                        selected = reportReason == "Other",
                        onClick = { reportReason = "Other" }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reportReason.isNotBlank(),
                    onClick = {
                        chatViewModel.reportUser(
                            reporterId = currentUserId,
                            reportedUserId = otherUserId,
                            reason = reportReason
                        ) { success, error ->
                            actionMessage = if (success) {
                                "Report submitted"
                            } else {
                                error ?: "Could not submit report"
                            }
                        }
                        reportReason = ""
                        showReportDialog = false
                    }
                ) {
                    Text("Submit report", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        reportReason = ""
                        showReportDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    actionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { actionMessage = null },
            title = { Text("InChat") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { actionMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Chat info",
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 14.dp,
                end = 20.dp,
                bottom = 30.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ChatInfoProfileCard(
                    nickname = otherUserNickname,
                    profilePhoto = otherUserProfilePhoto,
                    presence = otherUserPresence
                )
            }

            item {
                ChatInfoSectionLabel("SHARED CONTENT")
                ChatInfoCard {
                    ChatInfoPlaceholderRow(
                        icon = Icons.Outlined.PhotoLibrary,
                        title = "Photos & videos",
                        subtitle = "Images and clips shared in this chat"
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    ChatInfoPlaceholderRow(
                        icon = Icons.Outlined.Link,
                        title = "Links",
                        subtitle = "Websites shared in messages"
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    ChatInfoPlaceholderRow(
                        icon = Icons.Outlined.InsertDriveFile,
                        title = "Files",
                        subtitle = "Documents and other attachments"
                    )
                }
            }

            item {
                ChatInfoSectionLabel("SAFETY & PRIVACY")
                ChatInfoCard {
                    val blockTitle = when (blockState) {
                        BlockState.NONE -> "Block user"
                        BlockState.I_BLOCKED_THEM -> "Unblock user"
                        BlockState.THEY_BLOCKED_ME -> "They blocked you"
                    }
                    val blockSubtitle = when (blockState) {
                        BlockState.NONE ->
                            "Stop messages and activity from this person"
                        BlockState.I_BLOCKED_THEM ->
                            "Allow messages from this person again"
                        BlockState.THEY_BLOCKED_ME ->
                            "You can't change another person's block settings"
                    }

                    ChatInfoActionRow(
                        icon = if (blockState == BlockState.I_BLOCKED_THEM) {
                            Icons.Outlined.Person
                        } else {
                            Icons.Outlined.Block
                        },
                        title = blockTitle,
                        subtitle = blockSubtitle,
                        accent = if (blockState == BlockState.NONE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        enabled = blockState != BlockState.THEY_BLOCKED_ME,
                        showChevron = blockState != BlockState.THEY_BLOCKED_ME,
                        onClick = { showBlockDialog = true }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    ChatInfoActionRow(
                        icon = Icons.Outlined.Flag,
                        title = "Report user",
                        subtitle = "Report spam, harassment, or inappropriate behavior",
                        accent = MaterialTheme.colorScheme.error,
                        onClick = {
                            reportReason = ""
                            showReportDialog = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInfoProfileCard(
    nickname: String,
    profilePhoto: String,
    presence: com.example.inchat.data.model.Presence
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
                )
            ) {
                Box(
                    modifier = Modifier.padding(5.dp)
                ) {
                    InChatProfileAvatar(
                        profilePhotoUrl = profilePhoto,
                        modifier = Modifier.fillMaxSize(),
                        iconSize = 42.dp,
                        contentDescription = "Profile picture"
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = nickname,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 21.sp,
                    lineHeight = 27.sp
                ),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                border = BorderStroke(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PresenceStatus(
                        presence = presence,
                        isTyping = false
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInfoSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 7.dp),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = GoogleSansFlex,
            fontSize = 10.sp,
            letterSpacing = 0.9.sp,
            fontWeight = FontWeight.Medium
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChatInfoCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun ChatInfoActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f)
        ),
        shadowElevation = if (enabled) 2.dp else 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(13.dp),
                color = accent.copy(alpha = 0.14f),
                border = BorderStroke(
                    width = 0.7.dp,
                    color = accent.copy(alpha = 0.18f)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = accent
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showChevron) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChatInfoPlaceholderRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(8.dp))

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface
        ) {
            Text(
                text = "SOON",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = GoogleSansFlex,
                    fontSize = 8.sp,
                    letterSpacing = 0.45.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatReportReason(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
