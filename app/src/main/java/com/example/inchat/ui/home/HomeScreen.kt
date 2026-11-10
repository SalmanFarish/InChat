package com.example.inchat.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Conversation
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import com.example.inchat.ui.theme.JetBrainsMono
import com.example.inchat.ui.auth.AuthViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(
    ExperimentalMaterial3Api::class
)
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

    var conversationToDelete by
    remember {
        mutableStateOf<Conversation?>(
            null
        )
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

    val totalUnreadCount =
        remember(conversations) {
            conversations.sumOf { it.unreadCount }
        }

    /*
     * =========================================================
     * DELETE CONVERSATION DIALOG
     * =========================================================
     */
    if (
        conversationToDelete != null
    ) {

        AlertDialog(

            onDismissRequest = {

                conversationToDelete =
                    null
            },

            title = {

                Text(
                    if (conversationToDelete?.chatType == "group") {
                        "Hide group from Home?"
                    } else {
                        "Delete conversation?"
                    }
                )
            },

            text = {

                val conversation =
                    conversationToDelete

                Text(
                    if (conversation?.chatType == "group") {
                        "This removes the group from your chats. " +
                                "You remain a member of the group."
                    } else {
                        "This removes the conversation from your chats. " +
                                "Your messages will not be deleted."
                    }
                )
            },

            confirmButton = {

                TextButton(

                    onClick = {

                        val conversation =
                            conversationToDelete

                        if (
                            conversation != null
                        ) {

                            homeViewModel
                                .deleteConversation(

                                    currentUserId =
                                        currentUserId,

                                    chatId =
                                        conversation.chatId,

                                    chatType =
                                        conversation.chatType

                                ) { success, error ->

                                    actionMessage =
                                        if (
                                            success
                                        ) {

                                            "Conversation deleted"

                                        } else {

                                            error
                                                ?: "Could not delete conversation"
                                        }
                                }
                        }

                        conversationToDelete =
                            null
                    }
                ) {

                    Text(
                        "Delete"
                    )
                }
            },

            dismissButton = {

                TextButton(

                    onClick = {

                        conversationToDelete =
                            null
                    }
                ) {

                    Text(
                        "Cancel"
                    )
                }
            }
        )
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

            HomeHeader(
                chatCount = conversations.size,
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
                    totalChats = conversations.size,
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

                        items =
                            filteredConversations,

                        key = {
                            it.chatId
                        }

                    ) { conversation ->

                        ConversationItem(

                            conversation =
                                conversation,

                            userRepository =
                                userRepository,

                            currentUserId =
                                currentUserId,

                            onClick = {

                                onConversationClick(
                                    conversation
                                )
                            },

                            onLongClick = {

                                conversationToDelete =
                                    conversation
                            }
                        )
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
                    58.dp
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
                        Icons.Default.Person,

                    contentDescription =
                        null,

                    modifier =
                        Modifier.size(
                            27.dp
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
                    16.dp
                )
        )

        Text(

            text =
                "No conversations yet",

            fontSize =
                19.sp,

            fontWeight =
                FontWeight.Bold,

            letterSpacing =
                (-0.2).sp
        )

        Spacer(
            modifier =
                Modifier.height(
                    6.dp
                )
        )

        Text(

            text =
                "Find someone in Search to start a private chat, or create a group.",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Spacer(
            modifier =
                Modifier.height(
                    18.dp
                )
        )

        OutlinedButton(
            onClick =
                onCreateGroupClick
        ) {
            Icon(
                imageVector =
                    Icons.Default.GroupAdd,
                contentDescription = null
            )

            Spacer(
                modifier =
                    Modifier.width(
                        8.dp
                    )
            )

            Text(
                text = "Create a group"
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
                    if (conversation.groupPhotoData.isNotBlank()) {
                        InChatProfileAvatar(
                            profilePhotoUrl = conversation.groupPhotoData,
                            modifier = Modifier.fillMaxSize(),
                            iconSize = 25.dp,
                            contentDescription = "Group photo"
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = "Group",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
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
                Text(
                    text = if (conversation.chatType == "group") {
                        conversation.groupName
                    } else {
                        conversation.otherUsername
                    },
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