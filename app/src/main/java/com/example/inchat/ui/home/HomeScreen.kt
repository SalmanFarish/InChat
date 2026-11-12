package com.example.inchat.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.widthIn
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.inchat.data.model.Conversation
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.chat.GroupAvatar
import com.example.inchat.ui.profile.InChatProfileAvatar
import com.example.inchat.ui.theme.JetBrainsMono
import com.example.inchat.ui.auth.AuthViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    authViewModel: AuthViewModel,
    homeViewModel: HomeViewModel,
    onConversationClick: (Conversation) -> Unit,
    onCreateGroupClick: () -> Unit,
    onProfileClick: () -> Unit
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
     * composes each individual row. This removes the per-row startup delay.
     */
    LaunchedEffect(
        conversations
    ) {

        val userIds =
            conversations
                .asSequence()
                .filter {
                    it.chatType != "group"
                }
                .map {
                    it.otherUserId
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()
                .toList()

        for (otherUserId in userIds) {
            launch {
                userRepository
                    .getUserByIdFast(
                        otherUserId
                    )
            }
        }
    }

    var searchQuery by
    rememberSaveable {
        mutableStateOf("")
    }

    var selectedConversation by remember {
        mutableStateOf<Conversation?>(null)
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
                                .lastMessage
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

    val visibleFilteredConversations =
        remember(filteredConversations) {
            filteredConversations.filterNot { it.isHidden }
        }

    val hiddenFilteredConversations =
        remember(filteredConversations) {
            filteredConversations.filter { it.isHidden }
        }

    val visibleChatCount =
        remember(conversations) {
            conversations.count { !it.isHidden }
        }

    val totalUnreadCount =
        remember(conversations) {
            conversations.filterNot { it.isHidden }
                .sumOf { it.unreadCount }
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

    selectedConversation?.let { conversation ->
        ConversationActionsCard(
            conversation = conversation,
            userRepository = userRepository,
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
            onDismiss = {
                selectedConversation = null
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

            HomeHeader(
                chatCount = visibleChatCount,
                unreadCount = totalUnreadCount,
                onProfileClick = onProfileClick,
                onCreateGroupClick = onCreateGroupClick
            )

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

            if (conversationsLoaded && conversations.isNotEmpty()) {
                HomeSectionHeading(
                    totalChats = visibleChatCount,
                    unreadCount = totalUnreadCount
                )
            }

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
                    onCreateGroupClick =
                        onCreateGroupClick
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

                    items(
                        items = visibleFilteredConversations,
                        key = { it.chatId }
                    ) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            userRepository = userRepository,
                            currentUserId = currentUserId,
                            onClick = {
                                onConversationClick(conversation)
                            },
                            onLongClick = {
                                selectedConversation = conversation
                            }
                        )
                    }

                    if (hiddenFilteredConversations.isNotEmpty()) {
                        item(key = "hidden_chats_toggle") {
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
                                key = { "hidden_${it.chatId}" }
                            ) { conversation ->
                                ConversationItem(
                                    conversation = conversation,
                                    userRepository = userRepository,
                                    currentUserId = currentUserId,
                                    onClick = {
                                        onConversationClick(conversation)
                                    },
                                    onLongClick = {
                                        selectedConversation = conversation
                                    }
                                )
                            }
                        }
                    }
                }
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
    chatCount: Int,
    unreadCount: Long,
    onProfileClick: () -> Unit,
    onCreateGroupClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 22.dp,
                top = 22.dp,
                end = 20.dp,
                bottom = 18.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                )

                Spacer(Modifier.width(7.dp))

                Text(
                    text = "INCHAT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(7.dp))

            Text(
                text = "Messages",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 27.sp,
                    lineHeight = 32.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = when {
                    unreadCount > 0L ->
                        "$chatCount CHATS  /  $unreadCount UNREAD"
                    else ->
                        "$chatCount CHATS IN YOUR INBOX"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    letterSpacing = 0.65.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onProfileClick,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(43.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Profile",
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        IconButton(
            onClick = onCreateGroupClick,
            modifier = Modifier
                .size(43.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = Icons.Default.GroupAdd,
                contentDescription = "Create group",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
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
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 18.dp,
                end = 18.dp,
                bottom = 16.dp
            )
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(17.dp)
            ),
        singleLine = true,
        shape = RoundedCornerShape(17.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        placeholder = {
            Text(
                text = "Search conversations",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search chats",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
        )
    )
}

/*
 * ============================================================
 * SECTION HEADING
 * ============================================================
 */
@Composable
private fun HomeSectionHeading(
    totalChats: Int,
    unreadCount: Long
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 22.dp,
                end = 22.dp,
                bottom = 7.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "RECENT CONVERSATIONS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 1.0.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        if (unreadCount > 0L) {
            Text(
                text = "$unreadCount UNREAD",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(12.dp))
        }

        Text(
            text = totalChats.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 9.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
                FontWeight.Bold
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
            count =
                6
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
    onCreateGroupClick: () -> Unit
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
            onClick = onCreateGroupClick,
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
                imageVector = Icons.Default.GroupAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = "Create a group",
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/*
 * ============================================================
 * CONVERSATION ACTION CARD
 * ============================================================
 */
@Composable
private fun ConversationActionsCard(
    conversation: Conversation,
    userRepository: UserRepository,
    onTogglePin: () -> Unit,
    onToggleHidden: () -> Unit,
    onDismiss: () -> Unit
) {
    var profilePhoto by remember(conversation.otherUserId) {
        mutableStateOf(
            userRepository.getCachedUser(conversation.otherUserId)
                ?.profilePhotoData
                ?.ifBlank { userRepository.getCachedUser(conversation.otherUserId)?.profilePhotoUrl }
                .orEmpty()
        )
    }

    if (conversation.chatType != "group") {
        LaunchedEffect(conversation.otherUserId) {
            val user = userRepository.getUserByIdFast(conversation.otherUserId)
            profilePhoto = user?.profilePhotoData
                ?.ifBlank { user.profilePhotoUrl }
                .orEmpty()
        }
    }

    val title = if (conversation.chatType == "group") {
        conversation.groupName
    } else {
        conversation.otherUsername
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 5.dp,
                shadowElevation = 20.dp,
                border = BorderStroke(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .padding(2.dp)
                        ) {
                            if (conversation.chatType == "group") {
                                GroupAvatar(
                                    groupName = conversation.groupName,
                                    modifier = Modifier.fillMaxSize(),
                                    fontSize = 22.sp
                                )
                            } else {
                                InChatProfileAvatar(
                                    profilePhotoUrl = profilePhoto,
                                    modifier = Modifier.fillMaxSize(),
                                    iconSize = 22.dp,
                                    contentDescription = "Profile picture"
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 15.sp
                                ),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(Modifier.height(3.dp))

                            Text(
                                text = if (conversation.chatType == "group") {
                                    "GROUP  ·  ${conversation.memberCount} MEMBERS"
                                } else {
                                    "PRIVATE CHAT"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    fontSize = 8.sp,
                                    letterSpacing = 0.55.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
                    )

                    ConversationActionRow(
                        icon = Icons.Outlined.PushPin,
                        title = if (conversation.isPinned) "Unpin chat" else "Pin to top",
                        onClick = onTogglePin
                    )

                    ConversationActionRow(
                        icon = if (conversation.isHidden) {
                            Icons.Outlined.Visibility
                        } else {
                            Icons.Outlined.VisibilityOff
                        },
                        title = if (conversation.isHidden) "Show on Home" else "Hide from Home",
                        onClick = onToggleHidden
                    )

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 13.sp
                ),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

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
            width = 0.75.dp,
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
                text = "HIDDEN CHATS  /  " + hiddenCount,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    letterSpacing = 0.55.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = if (expanded) "HIDE" else "SHOW",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
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
    userRepository: UserRepository,
    currentUserId: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var otherUserProfilePhoto by remember(conversation.otherUserId) {
        mutableStateOf(
            userRepository
                .getCachedUser(conversation.otherUserId)
                ?.profilePhotoData
                ?.ifBlank {
                    userRepository
                        .getCachedUser(conversation.otherUserId)
                        ?.profilePhotoUrl
                }
                .orEmpty()
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

    val hasUnread = conversation.unreadCount > 0L
    val avatarBorderColor =
        if (hasUnread) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
        }

    val messagePrefix =
        if (conversation.chatType == "group") {
            when {
                conversation.lastSenderId == currentUserId -> "You: "
                conversation.lastSenderNickname.isNotBlank() ->
                    conversation.lastSenderNickname + ": "
                else -> ""
            }
        } else if (conversation.lastSenderId == currentUserId) {
            "You: "
        } else {
            ""
        }

    val previewText =
        conversation.lastMessage.ifBlank {
            if (conversation.chatType == "group") "No messages yet" else ""
        }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(18.dp),
        color = if (hasUnread) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = 0.75.dp,
            color = if (hasUnread) {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.78f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
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
                            FontWeight.Bold
                        } else {
                            FontWeight.SemiBold
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
                    text = messagePrefix + previewText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    fontWeight = if (hasUnread) {
                        FontWeight.Medium
                    } else {
                        FontWeight.Normal
                    },
                    color = if (hasUnread) {
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
                    text = formatConversationTime(conversation.lastTimestamp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 9.sp,
                        letterSpacing = 0.1.sp
                    ),
                    fontWeight = if (hasUnread) {
                        FontWeight.SemiBold
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
                            fontFamily = JetBrainsMono,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
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