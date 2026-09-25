package com.example.inchat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import com.example.inchat.data.model.Group
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.ChatAppearanceRepository
import com.example.inchat.data.repository.GroupChatRepository
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
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
    onGroupThemeClick: () -> Unit,
    onGroupLeft: () -> Unit = {}
) {
    val groupRepository =
        remember { GroupChatRepository() }

    val appearanceRepository =
        remember { ChatAppearanceRepository() }

    val userRepository =
        remember { UserRepository() }

    val group by
        groupRepository
            .observeGroup(groupId)
            .collectAsState(initial = null)

    val selectedThemeId by
        appearanceRepository
            .observeTheme(groupId)
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
        AlertDialog(
            onDismissRequest = {
                if (!isActionRunning) showAddMembersDialog = false
            },
            title = { Text("Add members") },
            text = {
                Column {
                    OutlinedTextField(
                        value = memberSearch,
                        onValueChange = { memberSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search username") }
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
                                Text(text = "@" + user.username, fontWeight = FontWeight.SemiBold)
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
            },
            confirmButton = {
                TextButton(
                    enabled = !isActionRunning,
                    onClick = { showAddMembersDialog = false }
                ) { Text("Done") }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isActionRunning) showRenameDialog = false
            },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(50) },
                    singleLine = true,
                    label = { Text("Group name") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isActionRunning && renameText.trim().isNotBlank(),
                    onClick = {
                        val name = renameText.trim()
                        isActionRunning = true
                        actionScope.launch {
                            groupRepository
                                .renameGroup(
                                    currentUserId = currentUserId,
                                    groupId = groupId,
                                    newName = name
                                )
                                .onSuccess {
                                    showRenameDialog = false
                                }
                                .onFailure {
                                    actionError =
                                        it.message ?: "Could not rename group."
                                }
                            isActionRunning = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isActionRunning,
                    onClick = { showRenameDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isActionRunning) showLeaveDialog = false
            },
            title = { Text("Leave group?") },
            text = {
                Text(
                    "You will no longer receive messages from this group."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isActionRunning,
                    onClick = {
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
                                    actionError =
                                        it.message ?: "Could not leave group."
                                }
                            isActionRunning = false
                        }
                    }
                ) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isActionRunning,
                    onClick = { showLeaveDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    memberActionTarget?.let { target ->
        val targetRole = group?.members?.get(target.uid)
        val canManageTarget =
            group?.members?.get(currentUserId) == "admin" &&
                target.uid != group?.createdBy &&
                target.uid != currentUserId

        if (canManageTarget) {
            AlertDialog(
                onDismissRequest = { memberActionTarget = null },
                title = { Text("@" + target.username) },
                text = { Text("Choose a member action.") },
                confirmButton = {
                    TextButton(
                        enabled = !isActionRunning,
                        onClick = {
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
                        }
                    ) { Text(if (targetRole == "admin") "Remove admin" else "Make admin") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isActionRunning,
                        onClick = {
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
                        }
                    ) { Text("Remove") }
                }
            )
        }
    }

    actionError?.let { message ->
        AlertDialog(
            onDismissRequest = { actionError = null },
            title = { Text("Group") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { actionError = null }) {
                    Text("OK")
                }
            }
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
                        fontWeight = FontWeight.SemiBold
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
            selectedTheme =
                ChatTheme.fromId(selectedThemeId),
            onGroupThemeClick = onGroupThemeClick,
            onAddMembersClick = {
                memberSearch = ""
                showAddMembersDialog = true
            },
            onRenameGroupClick = {
                renameText = group.name
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
    selectedTheme: ChatTheme,
    onGroupThemeClick: () -> Unit,
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
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(76.dp),
                        shape = CircleShape,
                        color =
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                    ) {
                        Box(
                            contentAlignment =
                                Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = null,
                                modifier = Modifier.size(38.dp),
                                tint =
                                    MaterialTheme
                                        .colorScheme
                                        .onPrimaryContainer
                            )
                        }
                    }

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
                                    .titleLarge,
                            fontWeight = FontWeight.Bold,
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

        item {
            GroupInfoSectionLabel(
                text = "APPEARANCE"
            )
        }

        item {
            GroupInfoActionRow(
                title = "Chat theme",
                subtitle =
                    selectedTheme.title +
                            " · shared with everyone",
                icon = Icons.Default.Palette,
                onClick = onGroupThemeClick
            )
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

        item {
            GroupInfoActionRow(
                title = "Leave group",
                subtitle =
                    if (group.createdBy == currentUserId) {
                        "The creator cannot leave this group"
                    } else {
                        "Remove yourself from this group"
                    },
                icon = Icons.Default.ExitToApp,
                onClick = onLeaveGroupClick
            )
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

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = canManage,
                    interactionSource = null,
                    indication = null,
                    onClick = onManageClick
                )
                .padding(
                    horizontal = 20.dp,
                    vertical = 8.dp
                ),
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
                fontWeight = FontWeight.SemiBold,
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
                    fontWeight = FontWeight.SemiBold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSecondaryContainer
                )
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
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
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
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )

            Spacer(
                modifier = Modifier.width(14.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
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
