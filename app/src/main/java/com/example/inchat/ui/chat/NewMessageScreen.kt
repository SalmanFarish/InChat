package com.example.inchat.ui.chat

import com.example.inchat.ui.components.CompactSearchField

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Conversation
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    currentUserId: String,
    onBackClick: () -> Unit,
    onUserClick: (User) -> Unit
) {
    val userRepository = remember { UserRepository() }
    val chatRepository = remember { ChatRepository() }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    var recentUsers by remember { mutableStateOf<List<User>>(emptyList()) }
    var isLoadingRecentUsers by remember { mutableStateOf(true) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, currentUserId) {
        val cleanQuery = query.trim().removePrefix("@")
        if (cleanQuery.isBlank()) {
            results = emptyList()
            searchError = null
            isSearching = false
            return@LaunchedEffect
        }

        results = emptyList()
        searchError = null
        isSearching = true
        delay(240)

        try {
            results = withContext(Dispatchers.IO) {
                userRepository.searchUsersByUsernamePrefix(
                    prefix = cleanQuery,
                    currentUserId = currentUserId,
                    limit = 20
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            searchError = "Couldn't search right now. Please try again."
        }

        isSearching = false
    }

    // Show the same recent direct-chat partners used by group creation.
    // The inbox index is already ordered by last activity and updates live.
    LaunchedEffect(currentUserId) {
        if (currentUserId.isBlank()) {
            isLoadingRecentUsers = false
            return@LaunchedEffect
        }

        isLoadingRecentUsers = true
        chatRepository.getConversationsFlow(currentUserId)
            .map { conversations ->
                conversations.asSequence()
                    .filter { conversation ->
                        conversation.chatType != "group" &&
                            conversation.lastTimestamp > 0L &&
                            conversation.otherUserId.isNotBlank() &&
                            conversation.otherUsername.isNotBlank()
                    }
                    .sortedByDescending(Conversation::lastTimestamp)
                    .distinctBy(Conversation::otherUserId)
                    .take(20)
                    .toList()
            }
            .distinctUntilChanged()
            .catch {
                recentUsers = emptyList()
                isLoadingRecentUsers = false
            }
            .collectLatest { recentConversations ->
                recentUsers = coroutineScope {
                    recentConversations.map { conversation ->
                        async(Dispatchers.IO) {
                            userRepository.getUserByIdFast(conversation.otherUserId)
                                ?: User(
                                    uid = conversation.otherUserId,
                                    username = conversation.otherUsername
                                )
                        }
                    }.awaitAll()
                }
                isLoadingRecentUsers = false
            }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New message",
                        fontWeight = FontWeight.Normal
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
            CompactSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search people",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                onClear = { query = "" }
            )

            when {
                isSearching -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                searchError != null -> {
                    Text(
                        text = searchError.orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                query.isBlank() && isLoadingRecentUsers -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                query.isBlank() && recentUsers.isEmpty() -> {
                    NewMessageEmptyState(
                        title = "Find someone to message",
                        description = "Search by username to start a private conversation."
                    )
                }

                query.isBlank() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item(key = "recent_messages_header") {
                            Text(
                                text = "Recently chatted",
                                modifier = Modifier.padding(
                                    start = 20.dp,
                                    top = 8.dp,
                                    bottom = 8.dp
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(
                            items = recentUsers,
                            key = { it.uid }
                        ) { user ->
                            NewMessageUserRow(
                                user = user,
                                onClick = { onUserClick(user) }
                            )
                        }
                    }
                }

                results.isEmpty() -> {
                    NewMessageEmptyState(
                        title = "No people found",
                        description = "Try another username."
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 4.dp,
                            bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = results,
                            key = { it.uid }
                        ) { user ->
                            NewMessageUserRow(
                                user = user,
                                onClick = { onUserClick(user) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewMessageUserRow(
    user: User,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InChatProfileAvatar(
            profilePhotoUrl = user.profilePhotoData.ifBlank {
                user.profilePhotoUrl
            },
            modifier = Modifier.size(48.dp),
            iconSize = 25.dp,
            contentDescription = "Profile picture"
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = user.displayName.ifBlank { user.username },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${user.username}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NewMessageEmptyState(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(27.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
