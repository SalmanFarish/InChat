package com.example.inchat.ui.chat

import com.example.inchat.ui.components.inChatOutlinedTextFieldColors

import com.example.inchat.ui.components.CompactSearchField
import com.example.inchat.ui.components.InChatActionDialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.GroupChatRepository
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import com.example.inchat.ui.theme.GoogleSansFlex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    currentUserId: String,
    groupId: String,
    onBackClick: () -> Unit,
    onGroupLeft: () -> Unit = {}
) {
    val groupRepository =
        remember { GroupChatRepository() }

    val userRepository =
        remember { UserRepository() }

    val group by
        groupRepository
            .observeGroup(groupId)
            .collectAsState(initial = null)

    var memberUsers by
        remember(group?.members) {
            mutableStateOf<Map<String, User>>(emptyMap())
        }

    var membersLoading by
        remember(group?.members) {
            mutableStateOf(false)
        }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var isActionRunning by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showAddMembersDialog by remember { mutableStateOf(false) }
    var memberSearch by remember { mutableStateOf("") }
    var memberSearchResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var memberActionTarget by remember { mutableStateOf<User?>(null) }

    LaunchedEffect(group?.members, memberSearch) {
        val query = memberSearch.trim().removePrefix("@")
        if (query.isBlank()) {
            memberSearchResults = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(220)
        memberSearchResults =
            runCatching {
                userRepository.searchUsersByUsernamePrefix(
                    prefix = query,
                    currentUserId = currentUserId,
                    limit = 8
                )
            }.getOrDefault(emptyList())
                .filterNot { group?.members?.containsKey(it.uid) == true }
    }

    LaunchedEffect(group?.members) {
        val memberIds =
            group
                ?.members
                ?.keys
                ?.toList()
                .orEmpty()

        if (memberIds.isEmpty()) {
            memberUsers = emptyMap()
            membersLoading = false
            return@LaunchedEffect
        }

        membersLoading = true

        memberUsers =
            coroutineScope {
                memberIds
                    .map { uid ->
                        async {
                            uid to
                                (
                                    userRepository
                                        .getUserByIdFast(uid)
                                        ?: User(uid = uid)
                                )
                        }
                    }
                    .awaitAll()
                    .toMap()
            }

        membersLoading = false
    }

    val actionScope = rememberCoroutineScope()

    if (showAddMembersDialog) {
        InChatActionDialog(
            title = "Add members",
            titleIcon = Icons.Filled.PersonAdd,
            confirmLabel = "Done",
            confirmIcon = Icons.Filled.PersonAdd,
            confirmDestructive = false,
            confirmEnabled = !isActionRunning,
            dismissEnabled = !isActionRunning,
            onConfirm = { showAddMembersDialog = false },
            onDismiss = { if (!isActionRunning) showAddMembersDialog = false },
            content = {
                Column {
                    CompactSearchField(
                        value = memberSearch,
                        onValueChange = { memberSearch = it },
                        placeholder = "Search username",
                        modifier = Modifier.fillMaxWidth(),
                        onClear = { memberSearch = "" }
                    )
                    Spacer(Modifier.height(10.dp))
                    memberSearchResults.forEach { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isActionRunning) {
                                    isActionRunning = true
                                    actionScope.launch {
                                        groupRepository.addMembers(
                                            currentUserId = currentUserId,
                                            groupId = groupId,
                                            memberIds = listOf(user.uid)
                                        ).onSuccess {
                                            memberSearch = ""
                                            memberSearchResults = emptyList()
                                            showAddMembersDialog = false
                                        }.onFailure {
                                            actionError = it.message ?: "Could not add member."
                                        }
                                        isActionRunning = false
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            InChatProfileAvatar(
                                profilePhotoUrl = user.profilePhotoData.ifBlank { user.profilePhotoUrl },
                                modifier = Modifier.size(42.dp),
                                iconSize = 21.dp,
                                contentDescription = null
                            )
                            Column(
                                modifier = Modifier.weight(1f).padding(start = 10.dp)
                            ) {
                                Text(text = "@" + user.username, fontWeight = FontWeight.Medium)
                                if (user.displayName.isNotBlank()) {
                                    Text(
                                        text = user.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = "Add"
                            )
                        }
                    }
                }
            }
        )
    }

    if (showRenameDialog) {
        InChatActionDialog(
            title = "Rename group",
            titleIcon = Icons.Filled.Edit,
            confirmLabel = "Save",
            confirmIcon = Icons.Filled.Edit,
            confirmDestructive = false,
            confirmEnabled = !isActionRunning && renameText.trim().isNotBlank(),
            dismissEnabled = !isActionRunning,
            isLoading = isActionRunning,
            onConfirm = {
                val name = renameText.trim()
                isActionRunning = true
                actionScope.launch {
                    groupRepository
                        .renameGroup(
                            currentUserId = currentUserId,
                            groupId = groupId,
                            newName = name
                        )
                        .onSuccess { showRenameDialog = false }
                        .onFailure {
                            actionError = it.message ?: "Could not rename group."
                        }
                    isActionRunning = false
                }
            },
            onDismiss = { showRenameDialog = false },
            content = {
                OutlinedTextField(
                    colors = inChatOutlinedTextFieldColors(),
                    value = renameText,
                    onValueChange = { renameText = it.take(50) },
                    singleLine = true,
                    label = { Text("Group name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    if (showLeaveDialog) {
        InChatActionDialog(
            title = "Leave group?",
            description = "You will no longer receive messages from this group.",
            titleIcon = Icons.Filled.ExitToApp,
            confirmLabel = "Leave",
            confirmIcon = Icons.Filled.ExitToApp,
            confirmEnabled = !isActionRunning,
            dismissEnabled = !isActionRunning,
            isLoading = isActionRunning,
            onConfirm = {
                isActionRunning = true
                actionScope.launch {
                    groupRepository
                        .leaveGroup(
                            currentUserId = currentUserId,
                            groupId = groupId
                        )
                        .onSuccess {
                            showLeaveDialog = false
                            onGroupLeft()
                        }
                        .onFailure {
                            actionError = it.message ?: "Could not leave group."
                        }
                    isActionRunning = false
                }
            },
            onDismiss = { showLeaveDialog = false }
        )
    }

    memberActionTarget?.let { target ->
        val targetRole = group?.members?.get(target.uid)
        val canManageTarget =
            group?.members?.get(currentUserId) == "admin" &&
                target.uid != group?.createdBy &&
                target.uid != currentUserId

        if (canManageTarget) {
            InChatActionDialog(
                title = "@" + target.username,
                description = "Choose a member action.",
                titleIcon = Icons.Filled.Edit,
                confirmLabel = if (targetRole == "admin") "Remove admin" else "Make admin",
                confirmIcon = Icons.Filled.Edit,
                confirmDestructive = false,
                confirmEnabled = !isActionRunning,
                showCancel = false,
                secondaryActionLabel = "Remove member",
                secondaryActionIcon = Icons.Outlined.DeleteOutline,
                secondaryActionDestructive = true,
                secondaryActionEnabled = !isActionRunning,
                onSecondaryAction = {
                    isActionRunning = true
                    actionScope.launch {
                        groupRepository.removeMember(
                            currentUserId = currentUserId,
                            groupId = groupId,
                            memberId = target.uid
                        ).onFailure {
                            actionError = it.message ?: "Could not remove member."
                        }
                        isActionRunning = false
                        memberActionTarget = null
                    }
                },
                onConfirm = {
                    val newRole = if (targetRole == "admin") "member" else "admin"
                    isActionRunning = true
                    actionScope.launch {
                        groupRepository.setMemberRole(
                            currentUserId = currentUserId,
                            groupId = groupId,
                            memberId = target.uid,
                            role = newRole
                        ).onFailure {
                            actionError = it.message ?: "Could not change member role."
                        }
                        isActionRunning = false
                        memberActionTarget = null
                    }
                },
                onDismiss = { memberActionTarget = null }
            )
        }
    }

    actionError?.let { message ->
        InChatActionDialog(
            title = "Group",
            description = message,
            confirmLabel = "OK",
            confirmIcon = Icons.Outlined.Info,
            confirmDestructive = false,
            showCancel = false,
            onConfirm = { actionError = null },
            onDismiss = { actionError = null }
        )
    }

    Scaffold(
        containerColor =
            MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Group info",
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick
                    ) {
                        Icon(
                            imageVector =
                                Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            MaterialTheme.colorScheme.background
                    )
            )
        }
    ) { innerPadding ->

        val currentGroup = group

        if (currentGroup == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            return@Scaffold
        }

        GroupInfoContent(
            group = currentGroup,
            currentUserId = currentUserId,
            memberUsers = memberUsers,
            membersLoading = membersLoading,
            onAddMembersClick = {
                memberSearch = ""
                showAddMembersDialog = true
            },
            onRenameGroupClick = {
                renameText = currentGroup.name
                showRenameDialog = true
            },
            onLeaveGroupClick = {
                showLeaveDialog = true
            },
            onMemberClick = { user ->
                memberActionTarget = user
            },
            innerPadding = innerPadding
        )
    }
}

@Composable
private fun GroupInfoContent(
    group: Group,
    currentUserId: String,
    memberUsers: Map<String, User>,
    membersLoading: Boolean,
    onAddMembersClick: () -> Unit,
    onRenameGroupClick: () -> Unit,
    onLeaveGroupClick: () -> Unit,
    onMemberClick: (User) -> Unit,
    innerPadding: PaddingValues
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        contentPadding =
            PaddingValues(bottom = 28.dp)
    ) {
        item {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            top = 14.dp,
                            end = 16.dp,
                            bottom = 8.dp
                        ),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GroupAvatar(
                        groupName = group.name,
                        modifier = Modifier.size(76.dp),
                        fontSize = 36.sp
                    )

                    Spacer(
                        modifier = Modifier.width(16.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = group.name.ifBlank { "Group" },
                            style =
                                MaterialTheme
                                    .typography
                                    .titleLarge
                                    .copy(
                                        fontFamily = GoogleSansFlex,
                                        fontSize = 21.sp,
                                        lineHeight = 26.sp
                                    ),
                            fontWeight = FontWeight.Medium,
                            maxLines = 2
                        )

                        Text(
                            text =
                                group.members.size.toString() +
                                        if (
                                            group.members.size == 1
                                        ) {
                                            " member"
                                        } else {
                                            " members"
                                        },
                            modifier =
                                Modifier.padding(top = 3.dp),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )

                        val creator =
                            memberUsers[group.createdBy]

                        Text(
                            text =
                                when {
                                    group.createdBy == currentUserId ->
                                        "Created by you"
                                    !creator?.username.isNullOrBlank() ->
                                        "Created by @" +
                                                creator?.username.orEmpty()
                                    else ->
                                        "Group creator"
                                },
                            modifier =
                                Modifier.padding(top = 2.dp),
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (group.members[currentUserId] == "admin") {
            item {
                GroupInfoSectionLabel(
                    text = "GROUP"
                )
            }

            item {
                GroupInfoActionRow(
                    title = "Add members",
                    subtitle = "Invite people to this group",
                    icon = Icons.Default.PersonAdd,
                    onClick = onAddMembersClick
                )
            }

            item {
                GroupInfoActionRow(
                    title = "Rename group",
                    subtitle = "Change the name for everyone",
                    icon = Icons.Default.Edit,
                    onClick = onRenameGroupClick
                )
            }
        }

        if (group.createdBy != currentUserId) {
            item {
                GroupInfoActionRow(
                    title = "Leave group",
                    subtitle = "Remove yourself from this group",
                    icon = Icons.Default.ExitToApp,
                    onClick = onLeaveGroupClick
                )
            }
        }

        item {
            GroupInfoSectionLabel(
                text =
                    "MEMBERS · " +
                            group.members.size
            )
        }

        if (membersLoading && memberUsers.isEmpty()) {
            item {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        items(
            items =
                group.members
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, String>> {
                            it.value == "admin"
                        }.thenBy {
                            memberUsers[it.key]
                                ?.username
                                .orEmpty()
                                .lowercase()
                        }
                    ),
            key = { it.key }
        ) { entry ->
            val uid = entry.key
            val role = entry.value
            val user = memberUsers[uid]

            GroupMemberRow(
                user = user,
                uid = uid,
                role = role,
                isCurrentUser = uid == currentUserId,
                canManage = group.members[currentUserId] == "admin" &&
                    uid != group.createdBy &&
                    uid != currentUserId,
                onManageClick = {
                    user?.let(onMemberClick)
                }
            )
        }

    }
}

@Composable
private fun GroupMemberRow(
    user: User?,
    uid: String,
    role: String,
    isCurrentUser: Boolean,
    canManage: Boolean = false,
    onManageClick: () -> Unit = {}
) {
    val username =
        user
            ?.username
            ?.ifBlank { uid }
            ?: uid

    val photoData =
        user
            ?.profilePhotoData
            .orEmpty()

    val photoUrl =
        if (photoData.isNotBlank()) {
            photoData
        } else {
            user
                ?.profilePhotoUrl
                .orEmpty()
        }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = canManage,
                    interactionSource = null,
                    indication = null,
                    onClick = onManageClick
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        InChatProfileAvatar(
            profilePhotoUrl = photoUrl,
            modifier = Modifier.size(46.dp),
            iconSize = 23.dp,
            contentDescription = null
        )

        Spacer(
            modifier = Modifier.width(12.dp)
        )

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text =
                    if (isCurrentUser) {
                        "$username · You"
                    } else {
                        username
                    },
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )

            val displayName =
                user
                    ?.displayName
                    ?.trim()
                    .orEmpty()

            if (displayName.isNotBlank()) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        if (role == "admin") {
            Surface(
                shape = MaterialTheme.shapes.small,
                color =
                    MaterialTheme
                        .colorScheme
                        .secondaryContainer
            ) {
                Text(
                    text = "Admin",
                    modifier =
                        Modifier.padding(
                            horizontal = 9.dp,
                            vertical = 5.dp
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSecondaryContainer
                )
            }
        }
        }
    }
}

@Composable
private fun GroupInfoSectionLabel(
    text: String
) {
    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    top = 18.dp,
                    end = 20.dp,
                    bottom = 6.dp
                ),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = GoogleSansFlex,
            letterSpacing = 1.sp
        ),
        fontWeight = FontWeight.Medium,
        color =
            MaterialTheme
                .colorScheme
                .onSurfaceVariant
    )
}

@Composable
private fun GroupInfoActionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 4.dp
                )
                .clickableForGroupInfo(onClick),
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f)
        ),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 14.dp
                    ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                border = BorderStroke(
                    width = 0.7.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }

            Spacer(
                modifier = Modifier.width(14.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    maxLines = 2
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open",
                tint =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun Modifier.clickableForGroupInfo(
    onClick: () -> Unit
): Modifier =
    this.then(
        clickable(
            interactionSource = null,
            indication = null,
            onClick = onClick
        )
    )
