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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    currentUserId: String,
    groupId: String,
    onBackClick: () -> Unit,
    onGroupThemeClick: () -> Unit
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
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp,
                            top = 24.dp,
                            end = 20.dp,
                            bottom = 22.dp
                        ),
                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(88.dp),
                    shape = CircleShape,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primaryContainer
                ) {
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint =
                                MaterialTheme
                                    .colorScheme
                                    .onPrimaryContainer
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(14.dp)
                )

                Text(
                    text = group.name.ifBlank { "Group" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(
                    modifier = Modifier.height(4.dp)
                )

                Text(
                    text =
                        group.members.size.toString() +
                                " members",
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
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
                            " · shared with all group members",
                onClick = onGroupThemeClick
            )
        }

        item {
            GroupInfoSectionLabel(
                text = "MEMBERS"
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
                isCurrentUser = uid == currentUserId
            )
        }

        item {
            GroupInfoSectionLabel(
                text = "GROUP"
            )
        }

        item {
            val creator =
                memberUsers[group.createdBy]

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 20.dp,
                            vertical = 12.dp
                        )
            ) {
                Text(
                    text = "Created by",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text =
                        when {
                            group.createdBy == currentUserId ->
                                "You"
                            !creator?.username.isNullOrBlank() ->
                                creator?.username.orEmpty()
                            else ->
                                group.createdBy
                        },
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    user: User?,
    uid: String,
    role: String,
    isCurrentUser: Boolean
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
    onClick: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickableForGroupInfo(onClick)
                .padding(
                    horizontal = 20.dp,
                    vertical = 14.dp
                )
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
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
