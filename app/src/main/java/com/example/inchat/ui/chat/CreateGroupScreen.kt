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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.GroupChatRepository
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    currentUserId: String,
    onBackClick: () -> Unit,
    onGroupCreated: (String) -> Unit
) {

    val userRepository = remember { UserRepository() }
    val groupRepository = remember { GroupChatRepository() }

    var groupName by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    val selectedUsers = remember { mutableStateListOf<User>() }
    var isSearching by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, currentUserId) {
        val cleanQuery = query.trim().removePrefix("@")

        if (cleanQuery.isBlank()) {
            results = emptyList()
            isSearching = false
            return@LaunchedEffect
        }

        kotlinx.coroutines.delay(220)
        isSearching = true

        results = withContext(Dispatchers.IO) {
            runCatching {
                userRepository.searchUsersByUsernamePrefix(
                    prefix = cleanQuery,
                    currentUserId = currentUserId,
                    limit = 12
                )
            }.getOrDefault(emptyList())
        }

        isSearching = false
    }

    LaunchedEffect(isCreating) {
        if (!isCreating) return@LaunchedEffect

        groupRepository
            .createGroup(
                currentUserId = currentUserId,
                groupName = groupName,
                memberIds = selectedUsers.map { it.uid }
            )
            .onSuccess { groupId ->
                isCreating = false
                onGroupCreated(groupId)
            }
            .onFailure { error ->
                isCreating = false
                errorMessage =
                    error.message ?: "Could not create group."
            }
    }

    fun toggleUser(user: User) {
        val index = selectedUsers.indexOfFirst { it.uid == user.uid }

        if (index >= 0) {
            selectedUsers.removeAt(index)
        } else if (selectedUsers.size < 49) {
            selectedUsers.add(user)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GroupAdd,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "New group",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        enabled = !isCreating
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (
                                groupName.trim().isBlank() ||
                                selectedUsers.isEmpty()
                            ) {
                                errorMessage = if (
                                    groupName.trim().isBlank()
                                ) {
                                    "Enter a group name."
                                } else {
                                    "Select at least one other person."
                                }
                            } else {
                                errorMessage = null
                                isCreating = true
                            }
                        },
                        enabled =
                            !isCreating &&
                                    groupName.trim().isNotBlank() &&
                                    selectedUsers.isNotEmpty()
                    ) {
                        Text("Create")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it.take(50) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                label = { Text("Group name") },
                singleLine = true
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        decorationBox = { innerTextField ->
                            if (query.isBlank()) {
                                Text("Search people")
                            }
                            innerTextField()
                        }
                    )
                }
            }

            Text(
                text = (selectedUsers.size + 1).toString() +
                        " members selected",
                modifier = Modifier.padding(
                    start = 18.dp,
                    top = 16.dp,
                    bottom = 8.dp
                ),
                fontWeight = FontWeight.SemiBold
            )

            errorMessage?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier.padding(
                        horizontal = 18.dp,
                        vertical = 4.dp
                    ),
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (isSearching || isCreating) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = 24.dp
                )
            ) {
                items(
                    items = results,
                    key = { it.uid }
                ) { user ->

                    val selected = selectedUsers.any { it.uid == user.uid }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isCreating) {
                                toggleUser(user)
                            }
                            .padding(
                                horizontal = 18.dp,
                                vertical = 10.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InChatProfileAvatar(
                            profilePhotoUrl = user.profilePhotoData.ifBlank {
                                user.profilePhotoUrl
                            },
                            modifier = Modifier.size(46.dp),
                            iconSize = 24.dp,
                            contentDescription = null
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                text = user.username,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (user.displayName.isNotBlank()) {
                                Text(
                                    text = user.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (selected) {
                            Surface(
                                modifier = Modifier.size(34.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}