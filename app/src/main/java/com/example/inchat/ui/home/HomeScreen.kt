package com.example.inchat.ui.home

import com.example.inchat.ui.components.CompactSearchField

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.inchat.data.model.Conversation
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.chat.GroupAvatar
import com.example.inchat.ui.profile.InChatProfileAvatar
import com.example.inchat.ui.theme.GoogleSansFlex
import com.example.inchat.ui.auth.AuthViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    authViewModel: AuthViewModel,
    homeViewModel: HomeViewModel,
    onConversationClick: (Conversation) -> Unit,
    onNewMessageClick: () -> Unit,
    onCreateGroupClick: () -> Unit
) {

    val currentUserId by
    authViewModel
        .currentUserId
        .collectAsState()

    val conversations by
    homeViewModel
        .conversations
        .collectAsState()

    val conversationsLoaded by
    homeViewModel
        .conversationsLoaded
        .collectAsState()

    /*
     * Keep one repository for the whole Home screen so all visible
     * conversation rows share the same in-memory profile cache.
     */
    val userRepository =
        remember {
            UserRepository()
        }

    /*
     * Start profile reads as soon as the inbox arrives, before LazyColumn
     * composes each individual row. Keep the key limited to user IDs so
     * message previews and unread-count changes do not restart profile loads.
     */
    val profileUserIds = remember(conversations) {
        conversations
            .asSequence()
            .filter { it.chatType != "group" }
            .map { it.otherUserId }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    LaunchedEffect(profileUserIds) {
        // Avoid opening a burst of Firebase reads for every inbox contact.
        // Loads run six at a time while still warming the whole conversation list.
        for (userIdBatch in profileUserIds.chunked(6)) {
            kotlinx.coroutines.coroutineScope {
                userIdBatch.forEach { otherUserId ->
                    launch {
                        userRepository.getUserByIdFast(otherUserId)
                    }
                }
            }
        }
    }

    var searchQuery by
    rememberSaveable {
        mutableStateOf("")
    }

    var selectedInboxFilter by rememberSaveable {
        mutableStateOf("All")
    }

    var showNewChatOptions by rememberSaveable {
        mutableStateOf(false)
    }

    var selectedConversation by remember {
        mutableStateOf<Conversation?>(null)
    }

    var pendingDeleteConversation by remember {
        mutableStateOf<Conversation?>(null)
    }

    var actionPopupOnLeft by remember {
        mutableStateOf(false)
    }

    var showHiddenChats by rememberSaveable {
        mutableStateOf(false)
    }

    var actionMessage by
    remember {
        mutableStateOf<String?>(
            null
        )
    }

    LaunchedEffect(
        currentUserId
    ) {

        if (
            currentUserId.isNotBlank()
        ) {

            homeViewModel
                .startConversationListener(
                    currentUserId
                )
        }
    }

    /*
     * =========================================================
     * FILTERED CONVERSATIONS
     * =========================================================
     *
     * This search is intentionally kept inside HomeScreen.
     *
     * It searches only the user's existing conversations.
     *
     * The separate Search screen will search for InChat users.
     */
    val filteredConversations =
        remember(
            conversations,
            searchQuery
        ) {

            val query =
                searchQuery
                    .trim()
                    .lowercase(
                        Locale.getDefault()
                    )

            if (
                query.isBlank()
            ) {

                conversations

            } else {

                conversations.filter { conversation ->

                    conversation
                        .otherUsername
                        .lowercase(
                            Locale.getDefault()
                        )
                        .contains(
                            query
                        ) ||

                            conversation
                                .safeInboxPreview()
                                .lowercase(
                                    Locale.getDefault()
                                )
                                .contains(
                                    query
                                ) ||

                            (
                                conversation.chatType ==
                                        "group" &&
                                conversation.groupName
                                    .lowercase(
                                        Locale.getDefault()
                                    )
                                    .contains(
                                        query
                                    )
                            )
                }
            }
        }

    val conversationsMatchingInboxFilter = remember(
        filteredConversations,
        selectedInboxFilter
    ) {
        when (selectedInboxFilter) {
            "Unread" -> filteredConversations.filter { it.unreadCount > 0L }
            "Favorites" -> filteredConversations.filter { it.isFavorite }
            "Groups" -> filteredConversations.filter { it.chatType == "group" }
            "Pinned" -> filteredConversations.filter { it.isPinned }
            else -> filteredConversations
        }
    }

    val visibleFilteredConversations =
        remember(conversationsMatchingInboxFilter) {
            conversationsMatchingInboxFilter.filterNot { it.isHidden }
        }

    val hiddenFilteredConversations =
        remember(conversationsMatchingInboxFilter) {
            conversationsMatchingInboxFilter.filter { it.isHidden }
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
     * HOME
     * =========================================================
     */
    Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background,



    ) { innerPadding ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        innerPadding
                    )
        ) {

            HomeHeader(onNewChatClick = { showNewChatOptions = true })

            HomeSearchBar(

                query =
                    searchQuery,

                onQueryChange = {
                        newQuery ->

                    searchQuery =
                        newQuery
                },

                onClear = {

                    searchQuery =
                        ""
                }
            )

            HomeInboxFilterChips(
                selectedFilter = selectedInboxFilter,
                onFilterSelected = { selectedInboxFilter = it }
            )

            /*
             * =================================================
             * CONTENT
             * =================================================
             */
            if (
                !conversationsLoaded
            ) {

                HomeConversationLoadingState()

            } else if (
                conversations.isEmpty()
            ) {

                EmptyConversationState(
                    onNewChatClick = { showNewChatOptions = true }
                )

            } else if (
                filteredConversations.isEmpty()
            ) {

                NoSearchResults(

                    query =
                        searchQuery
                )

            } else {

                LazyColumn(

                    modifier =
                        Modifier
                            .fillMaxSize(),

                    contentPadding =
                        PaddingValues(
                            top = 2.dp,
                            bottom = 24.dp
                        ),

                    verticalArrangement =
                        Arrangement.spacedBy(2.dp)
                ) {

                    if (visibleFilteredConversations.isEmpty()) {
                        item(
                            key = "inbox_filter_empty_state",
                            contentType = "inbox_filter_empty_state"
                        ) {
                            HomeInboxFilterEmptyState(
                                selectedFilter = selectedInboxFilter,
                                onShowAll = { selectedInboxFilter = "All" }
                            )
                        }
                    } else {
                    items(
                        items = visibleFilteredConversations,
                        key = { it.chatId },
                        contentType = {
                            if (it.chatType == "group") {
                                "group_conversation"
                            } else {
                                "direct_conversation"
                            }
                        }
                    ) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            homeViewModel = homeViewModel,
                            userRepository = userRepository,
                            currentUserId = currentUserId,
                            onClick = {
                                pendingDeleteConversation = null
                                selectedConversation = null
                                onConversationClick(conversation)
                            },
                            onLongClick = {
                                pendingDeleteConversation = null
                                actionPopupOnLeft = kotlin.random.Random.nextBoolean()
                                selectedConversation = conversation
                            },
                            showActions = selectedConversation?.chatId == conversation.chatId,
                            popupOnLeft = actionPopupOnLeft,
                            onDismissActions = {
                                pendingDeleteConversation = null
                                selectedConversation = null
                            },
                            onToggleFavorite = {
                                homeViewModel.setConversationFavorite(
                                    currentUserId = currentUserId,
                                    chatId = conversation.chatId,
                                    favorite = !conversation.isFavorite
                                ) { success, error ->
                                    selectedConversation = null
                                    if (!success) {
                                        actionMessage = error ?: "Could not update favorites."
                                    }
                                }
                            },
                            onTogglePin = {
                                homeViewModel.setConversationPinned(
                                    currentUserId = currentUserId,
                                    chatId = conversation.chatId,
                                    pinned = !conversation.isPinned
                                ) { success, error ->
                                    selectedConversation = null
                                    if (!success) {
                                        actionMessage = error ?: "Could not update the pin."
                                    }
                                }
                            },
                            onToggleHidden = {
                                homeViewModel.setConversationHidden(
                                    currentUserId = currentUserId,
                                    chatId = conversation.chatId,
                                    hidden = !conversation.isHidden
                                ) { success, error ->
                                    selectedConversation = null
                                    if (!success) {
                                        actionMessage = error ?: "Could not update hidden chats."
                                    }
                                }
                            },
                            confirmingDelete = pendingDeleteConversation?.chatId == conversation.chatId,
                            onCancelDelete = { pendingDeleteConversation = null },
                            onConfirmDelete = {
                                pendingDeleteConversation = null
                                selectedConversation = null
                                homeViewModel.deleteConversation(
                                    currentUserId = currentUserId,
                                    chatId = conversation.chatId,
                                    chatType = conversation.chatType
                                ) { success, error ->
                                    if (!success) actionMessage = error ?: "Could not delete chat."
                                }
                            },
                            onDelete = { pendingDeleteConversation = conversation }
                        )
                    }

                    }

                    if (hiddenFilteredConversations.isNotEmpty()) {
                        item(
                            key = "hidden_chats_toggle",
                            contentType = "hidden_chats_toggle"
                        ) {
                            HiddenChatsToggle(
                                hiddenCount = hiddenFilteredConversations.size,
                                expanded = showHiddenChats,
                                onClick = {
                                    showHiddenChats = !showHiddenChats
                                }
                            )
                        }

                        if (showHiddenChats) {
                            items(
                                items = hiddenFilteredConversations,
                                key = { "hidden_${it.chatId}" },
                                contentType = {
                            if (it.chatType == "group") {
                                "group_conversation"
                            } else {
                                "direct_conversation"
                            }
                        }
                            ) { conversation ->
                                ConversationItem(
                                    conversation = conversation,
                                    homeViewModel = homeViewModel,
                                    userRepository = userRepository,
                                    currentUserId = currentUserId,
                                    onClick = {
                                        pendingDeleteConversation = null
                                        selectedConversation = null
                                        onConversationClick(conversation)
                                    },
                                    onLongClick = {
                                        pendingDeleteConversation = null
                                        actionPopupOnLeft = kotlin.random.Random.nextBoolean()
                                        selectedConversation = conversation
                                    },
                                    showActions = selectedConversation?.chatId == conversation.chatId,
                                    popupOnLeft = actionPopupOnLeft,
                                    onDismissActions = {
                                        pendingDeleteConversation = null
                                        selectedConversation = null
                                    },
                                    onToggleFavorite = {
                                homeViewModel.setConversationFavorite(
                                    currentUserId = currentUserId,
                                    chatId = conversation.chatId,
                                    favorite = !conversation.isFavorite
                                ) { success, error ->
                                    selectedConversation = null
                                    if (!success) {
                                        actionMessage = error ?: "Could not update favorites."
                                    }
                                }
                            },
                            onTogglePin = {
                                        homeViewModel.setConversationPinned(
                                            currentUserId = currentUserId,
                                            chatId = conversation.chatId,
                                            pinned = !conversation.isPinned
                                        ) { success, error ->
                                            selectedConversation = null
                                            if (!success) {
                                                actionMessage = error ?: "Could not update the pin."
                                            }
                                        }
                                    },
                                    onToggleHidden = {
                                        homeViewModel.setConversationHidden(
                                            currentUserId = currentUserId,
                                            chatId = conversation.chatId,
                                            hidden = !conversation.isHidden
                                        ) { success, error ->
                                            selectedConversation = null
                                            if (!success) {
                                                actionMessage = error ?: "Could not update hidden chats."
                                            }
                                        }
                                    },
                                    confirmingDelete = pendingDeleteConversation?.chatId == conversation.chatId,
                                    onCancelDelete = { pendingDeleteConversation = null },
                                    onConfirmDelete = {
                                        pendingDeleteConversation = null
                                        selectedConversation = null
                                        homeViewModel.deleteConversation(
                                            currentUserId = currentUserId,
                                            chatId = conversation.chatId,
                                            chatType = conversation.chatType
                                        ) { success, error ->
                                            if (!success) actionMessage = error ?: "Could not delete chat."
                                        }
                                    },
                                    onDelete = { pendingDeleteConversation = conversation }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewChatOptions) {
        NewChatOptionsSheet(
            onDismiss = { showNewChatOptions = false },
            onNewMessage = {
                showNewChatOptions = false
                onNewMessageClick()
            },
            onNewGroup = {
                showNewChatOptions = false
                onCreateGroupClick()
            }
        )
    }
}

/*
 * ============================================================
 * NEW CHAT OPTIONS
 * ============================================================*/
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatOptionsSheet(
    onDismiss: () -> Unit,
    onNewMessage: () -> Unit,
    onNewGroup: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "New chat",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Choose how you want to start a conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            NewChatOptionRow(
                icon = Icons.Default.Person,
                title = "New message",
                subtitle = "Start a private conversation",
                onClick = onNewMessage
            )
            NewChatOptionRow(
                icon = Icons.Default.GroupAdd,
                title = "New group",
                subtitle = "Create a chat with multiple people",
                onClick = onNewGroup
            )
        }
    }
}

@Composable
private fun NewChatOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 13.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/*
 * ============================================================
 * HOME HEADER
 * ============================================================
 */
@Composable
private fun HomeHeader(
    onNewChatClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 22.dp, end = 20.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Messages",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 27.sp,
                lineHeight = 32.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            IconButton(
                onClick = onNewChatClick,
                modifier = Modifier.size(42.dp)
            ) {
                val plusColor = MaterialTheme.colorScheme.onPrimary
                Canvas(modifier = Modifier.size(18.dp)) {
                    val arm = 6.5.dp.toPx()
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = plusColor,
                        start = Offset(centerX - arm, centerY),
                        end = Offset(centerX + arm, centerY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = plusColor,
                        start = Offset(centerX, centerY - arm),
                        end = Offset(centerX, centerY + arm),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
/*
 * ============================================================
 * SEARCH BAR
 * ============================================================
 */
@Composable
private fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    CompactSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Search conversations",
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
        onClear = onClear
    )
}

/*
 * ============================================================
 * NO SEARCH RESULTS
 * ============================================================
 */
@Composable
private fun NoSearchResults(
    query: String
) {

    Column(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start =
                        28.dp,

                    top =
                        70.dp,

                    end =
                        28.dp,

                    bottom =
                        40.dp
                ),

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Surface(

            modifier =
                Modifier.size(
                    56.dp
                ),

            shape =
                CircleShape,

            color =
                MaterialTheme
                    .colorScheme
                    .surfaceVariant
        ) {

            Box(

                contentAlignment =
                    Alignment.Center
            ) {

                Icon(

                    imageVector =
                        Icons.Default.Search,

                    contentDescription =
                        null,

                    modifier =
                        Modifier.size(
                            26.dp
                        ),

                    tint =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(
                    15.dp
                )
        )

        Text(

            text =
                "No chats found",

            fontSize =
                19.sp,

            fontWeight =
                FontWeight.Medium
        )

        Spacer(
            modifier =
                Modifier.height(
                    6.dp
                )
        )

        Text(

            text =
                "No conversation matches \"$query\".",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}


@Composable
private fun HomeInboxFilterChips(
    selectedFilter: String,
    onFilterSelected: (String) -> Unit
) {
    val filters = listOf("All", "Unread", "Favorites", "Groups", "Pinned")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        filters.forEach { filter ->
            val selected = selectedFilter == filter
            Surface(
                modifier = Modifier
                    .height(32.dp)
                    .clickable { onFilterSelected(filter) },
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                }
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filter,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeInboxFilterEmptyState(
    selectedFilter: String,
    onShowAll: () -> Unit
) {
    val message = when (selectedFilter) {
        "Unread" -> "No unread conversations"
        "Favorites" -> "No favorite conversations yet"
        "Groups" -> "No group conversations yet"
        "Pinned" -> "No pinned conversations yet"
        else -> "No conversations"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp
        )
        TextButton(onClick = onShowAll) {
            Text("Show all chats")
        }
    }
}

/*
 * ============================================================
 * INITIAL LOAD STATE
 * ============================================================
 *
 * Keep the Home layout stable while Firebase delivers the first
 * conversation snapshot. This avoids briefly showing the empty
 * state before the real chat list appears.
 */
@Composable
private fun HomeConversationLoadingState() {

    LazyColumn(

        modifier =
            Modifier.fillMaxSize(),

        contentPadding =
            PaddingValues(
                bottom =
                    24.dp
            )
    ) {

        items(
            count = 6,
            contentType = { "conversation_skeleton" }
        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start =
                                18.dp,

                            top =
                                14.dp,

                            end =
                                18.dp,

                            bottom =
                                14.dp
                        ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Surface(

                    modifier =
                        Modifier.size(
                            48.dp
                        ),

                    shape =
                        CircleShape,

                    color =
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                ) {}

                Spacer(
                    modifier =
                        Modifier.width(
                            13.dp
                        )
                )

                Column(
                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {

                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth(
                                    0.60f
                                )
                                .height(
                                    15.dp
                                ),
                        shape =
                            RoundedCornerShape(
                                8.dp
                            ),
                        color =
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                    ) {}

                    Spacer(
                        modifier =
                            Modifier.height(
                                7.dp
                            )
                    )

                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth(
                                    0.86f
                                )
                                .height(
                                    13.dp
                                ),
                        shape =
                            RoundedCornerShape(
                                7.dp
                            ),
                        color =
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                    ) {}
                }
            }
        }
    }
}

/*
 * ============================================================
 * EMPTY STATE
 * ============================================================
 */
@Composable
private fun EmptyConversationState(
    onNewChatClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 30.dp,
                top = 48.dp,
                end = 30.dp,
                bottom = 40.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
            )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(31.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "No conversations yet",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 19.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Your chats will appear here. Search for someone to start a private conversation, or create a group.",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 19.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick = onNewChatClick,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline
            ),
            contentPadding = PaddingValues(
                horizontal = 18.dp,
                vertical = 11.dp
            )
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = "Start a conversation",
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/*
 * ============================================================
 * CONVERSATION ACTION POPUP
 * ============================================================
 */
@Composable
private fun ConversationActionsPopup(
    conversation: Conversation,
    popupOnLeft: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleHidden: () -> Unit,
    confirmingDelete: Boolean,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val positionProvider = remember(popupOnLeft, density) {
        ConversationActionsPopupPositionProvider(
            preferLeft = popupOnLeft,
            edgeMarginPx = with(density) { 14.dp.roundToPx() }
        )
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 190.dp, max = 228.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 4.dp,
            shadowElevation = 18.dp,
            border = BorderStroke(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
            )
        ) {
            Column(
                modifier = Modifier.padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (confirmingDelete) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Delete this chat?",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "This chat will be removed from your inbox. You can't undo this.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ConversationActionRow(
                        icon = Icons.Default.Clear,
                        title = "Cancel",
                        onClick = onCancelDelete
                    )
                    ConversationActionRow(
                        icon = Icons.Outlined.DeleteOutline,
                        title = "Delete Chat",
                        destructive = true,
                        onClick = onConfirmDelete
                    )
                } else {
                    ConversationActionRow(
                        icon = if (conversation.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        title = if (conversation.isFavorite) "Remove from Favorites" else "Add to Favorites",
                        onClick = onToggleFavorite
                    )
                    ConversationActionRow(
                        icon = Icons.Outlined.PushPin,
                        title = if (conversation.isPinned) "Unpin" else "Pin",
                        onClick = onTogglePin
                    )
                    ConversationActionRow(
                        icon = if (conversation.isHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        title = if (conversation.isHidden) "Show on Home" else "Hide",
                        onClick = onToggleHidden
                    )
                    ConversationActionRow(
                        icon = Icons.Outlined.DeleteOutline,
                        title = "Delete Chat",
                        destructive = true,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

private class ConversationActionsPopupPositionProvider(
    private val preferLeft: Boolean,
    private val edgeMarginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - edgeMarginPx)
            .coerceAtLeast(edgeMarginPx)
        val x = if (preferLeft) edgeMarginPx else maxX

        val maxY = (windowSize.height - popupContentSize.height - edgeMarginPx)
            .coerceAtLeast(edgeMarginPx)
        val y = (anchorBounds.center.y - popupContentSize.height / 2)
            .coerceIn(edgeMarginPx, maxY)

        return IntOffset(x, y)
    }
}

@Composable
private fun ConversationActionRow(
    icon: ImageVector,
    title: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val foreground = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (destructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = foreground
            )

            Spacer(Modifier.width(11.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 13.sp
                ),
                fontWeight = FontWeight.Medium,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun HiddenChatsToggle(
    hiddenCount: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            width = 0.7.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 12.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.Visibility
                } else {
                    Icons.Outlined.VisibilityOff
                },
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(10.dp))

            Text(
                text = "HIDDEN CHATS  /  $hiddenCount",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = GoogleSansFlex,
                    fontSize = 9.sp,
                    letterSpacing = 0.55.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = if (expanded) "HIDE" else "SHOW",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = GoogleSansFlex,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/*
 * ============================================================
 * CONVERSATION ITEM
 * ============================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    homeViewModel: HomeViewModel,
    userRepository: UserRepository,
    currentUserId: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showActions: Boolean,
    popupOnLeft: Boolean,
    onDismissActions: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleHidden: () -> Unit,
    confirmingDelete: Boolean,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDelete: () -> Unit
) {
    val localPreviewFlow = remember(
        currentUserId,
        conversation.chatId,
        conversation.chatType,
        conversation.otherUserId
    ) {
        homeViewModel.observeLocalPreview(currentUserId, conversation)
    }
    val localPreview by localPreviewFlow.collectAsState(initial = null)

    var otherUserProfilePhoto by remember(conversation.otherUserId) {
        mutableStateOf(
            userRepository.getCachedUser(conversation.otherUserId)?.let { cachedUser ->
                cachedUser.profilePhotoData
                    .ifBlank { cachedUser.profilePhotoUrl }
            }.orEmpty()
        )
    }

    if (conversation.chatType != "group") {
        LaunchedEffect(conversation.otherUserId) {
            val user = userRepository.getUserByIdFast(conversation.otherUserId)
            otherUserProfilePhoto = user
                ?.profilePhotoData
                ?.ifBlank { user.profilePhotoUrl }
                .orEmpty()
        }
    }

    var otherUserTyping by remember(conversation.chatId, conversation.otherUserId) {
        mutableStateOf(false)
    }

    // Observe the same per-chat typing node used by ChatViewModel. The listener
    // exists only while this inbox row is composed and is removed when it leaves.
    DisposableEffect(conversation.chatId, conversation.otherUserId, conversation.chatType) {
        if (conversation.chatType != "group" &&
            conversation.chatId.isNotBlank() &&
            conversation.otherUserId.isNotBlank()
        ) {
            val typingRef = FirebaseDatabase.getInstance()
                .getReference("typing")
                .child(conversation.chatId)
                .child(conversation.otherUserId)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    otherUserTyping = snapshot.getValue(Boolean::class.java) == true
                }

                override fun onCancelled(error: DatabaseError) {
                    otherUserTyping = false
                }
            }
            typingRef.addValueEventListener(listener)
            onDispose {
                typingRef.removeEventListener(listener)
            }
        } else {
            otherUserTyping = false
            onDispose { }
        }
    }

    val hasUnread = conversation.unreadCount > 0L
    val avatarBorderColor =
        if (hasUnread) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
        }

    // Worker metadata timestamps match the authenticated sentAtMillis value.
    // Use local plaintext only for the latest item (or a newer queued send).
    val visibleLocalPreview = localPreview?.takeIf {
        it.timestamp >= conversation.lastTimestamp
    }
    val previewSenderId = visibleLocalPreview?.senderUserId ?: conversation.lastSenderId
    val previewTimestamp = maxOf(
        conversation.lastTimestamp,
        visibleLocalPreview?.timestamp ?: 0L
    )
    val messagePrefix =
        if (conversation.chatType == "group") {
            when {
                previewSenderId == currentUserId -> "You: "
                visibleLocalPreview == null ||
                    visibleLocalPreview.timestamp == conversation.lastTimestamp ->
                    conversation.lastSenderNickname
                        .takeIf { it.isNotBlank() }
                        ?.plus(": ")
                        .orEmpty()
                else -> ""
            }
        } else if (previewSenderId == currentUserId) {
            "You: "
        } else {
            ""
        }

    // Decrypted text is read only from this account's encrypted local store.
    // Firebase inbox metadata remains a non-sensitive fallback for uncached messages.
    val previewText = visibleLocalPreview?.text ?: conversation.safeInboxPreview()

    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp)
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            shape = RoundedCornerShape(18.dp),
            color = if (hasUnread) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = BorderStroke(
                width = 0.75.dp,
                color = if (hasUnread) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .border(
                            width = 1.dp,
                            color = avatarBorderColor,
                            shape = CircleShape
                        )
                        .padding(2.dp)
                ) {
                    if (conversation.chatType == "group") {
                        GroupAvatar(
                            groupName = conversation.groupName,
                            modifier = Modifier.fillMaxSize(),
                            fontSize = 24.sp
                        )
                    } else {
                        InChatProfileAvatar(
                            profilePhotoUrl = otherUserProfilePhoto,
                            modifier = Modifier.fillMaxSize(),
                            iconSize = 24.dp,
                            contentDescription = "Profile picture"
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (conversation.chatType == "group") {
                                conversation.groupName
                            } else {
                                conversation.otherUsername
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = 14.sp,
                                lineHeight = 19.sp
                            ),
                            fontWeight = if (hasUnread) {
                                FontWeight.Medium
                            } else {
                                FontWeight.Medium
                            },
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (conversation.isPinned) {
                            Icon(
                                imageVector = Icons.Outlined.PushPin,
                                contentDescription = "Pinned",
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(13.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.height(5.dp))

                    Text(
                        text = if (otherUserTyping) "typing…" else "$messagePrefix$previewText",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        ),
                        fontWeight = if (otherUserTyping || hasUnread) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                        color = if (otherUserTyping) {
                            MaterialTheme.colorScheme.primary
                        } else if (hasUnread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = formatConversationTime(previewTimestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = GoogleSansFlex,
                            fontSize = 10.sp,
                            letterSpacing = 0.1.sp
                        ),
                        fontWeight = if (hasUnread) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                        color = if (hasUnread) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )

                    if (hasUnread) {
                        Surface(
                            modifier = Modifier.padding(top = 7.dp),
                            shape = RoundedCornerShape(9.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = if (conversation.unreadCount > 99L) {
                                    "99+"
                                } else {
                                    conversation.unreadCount.toString()
                                },
                                modifier = Modifier.padding(
                                    horizontal = 7.dp,
                                    vertical = 3.dp
                                ),
                                fontFamily = GoogleSansFlex,
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
        if (showActions) {
            ConversationActionsPopup(
                conversation = conversation,
                popupOnLeft = popupOnLeft,
                onDismiss = onDismissActions,
                onToggleFavorite = onToggleFavorite,
                onTogglePin = onTogglePin,
                onToggleHidden = onToggleHidden,
                confirmingDelete = confirmingDelete,
                onCancelDelete = onCancelDelete,
                onConfirmDelete = onConfirmDelete,
                onDelete = onDelete
            )
        }
    }
}

/*
 * Firebase may still contain plaintext previews written before the
 * encrypted-preview rules were deployed. Treat only fixed, non-sensitive
 * labels as displayable; mask every legacy or unexpected value.
 */
private fun Conversation.safeInboxPreview(): String =
    when (lastMessage) {
        "Encrypted message" -> "Encrypted message"
        "No messages yet" -> "No messages yet"
        "Group updated" -> if (chatType == "group") "Group updated" else "Encrypted message"
        "" -> if (chatType == "group") "No messages yet" else ""
        else -> "Encrypted message"
    }

/*
 * ============================================================
 * CONVERSATION TIME
 * ============================================================
 */
private fun formatConversationTime(
    timestamp: Long
): String {

    if (
        timestamp <= 0L
    ) {

        return ""
    }

    val difference =
        System.currentTimeMillis() -
                timestamp

    val minute =
        60_000L

    val hour =
        60 *
                minute

    val day =
        24 *
                hour

    return when {

        difference < minute ->
            "now"

        difference < hour ->
            "${difference / minute}m"

        difference < day ->
            "${difference / hour}h"

        difference < 7 * day ->
            "${difference / day}d"

        else ->
            SimpleDateFormat(
                "dd/MM",
                Locale.getDefault()
            ).format(
                Date(
                    timestamp
                )
            )
    }
}