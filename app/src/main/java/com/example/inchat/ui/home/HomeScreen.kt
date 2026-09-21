package com.example.inchat.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import com.example.inchat.ui.auth.AuthViewModel
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
                                )
                }
            }
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
                    "Delete conversation?"
                )
            },

            text = {

                Text(
                    "This removes the conversation from your chats. " +
                            "Your messages will not be deleted."
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
                                        conversation.chatId

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

        topBar = {

            TopAppBar(

                title = {

                    Text(

                        text =
                            "InChat",

                        fontSize =
                            20.sp,

                        fontWeight =
                            FontWeight.Bold,

                        letterSpacing =
                            (-0.4).sp
                    )
                },

                colors =
                    TopAppBarDefaults
                        .topAppBarColors(

                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .background,

                            scrolledContainerColor =
                                MaterialTheme
                                    .colorScheme
                                    .background,

                            titleContentColor =
                                MaterialTheme
                                    .colorScheme
                                    .onBackground
                        )
            )
        }

    ) { innerPadding ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        innerPadding
                    )
        ) {

            /*
             * =================================================
             * SEARCH EXISTING CHATS
             * =================================================
             */
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

                EmptyConversationState()

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
                            bottom =
                                24.dp
                        ),

                    verticalArrangement =
                        Arrangement.spacedBy(
                            0.dp
                        )
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

        value =
            query,

        onValueChange =
            onQueryChange,

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start =
                        14.dp,

                    top =
                        8.dp,

                    end =
                        14.dp,

                    bottom =
                        8.dp
                ),

        singleLine =
            true,

        shape =
            RoundedCornerShape(
                22.dp
            ),

        placeholder = {

            Text(
                "Search chats"
            )
        },

        leadingIcon = {

            Icon(

                imageVector =
                    Icons.Default.Search,

                contentDescription =
                    "Search chats"
            )
        },

        trailingIcon = {

            if (
                query.isNotEmpty()
            ) {

                IconButton(

                    onClick =
                        onClear
                ) {

                    Icon(

                        imageVector =
                            Icons.Default.Clear,

                        contentDescription =
                            "Clear search"
                    )
                }
            }
        },

        colors =
            TextFieldDefaults.colors(

                focusedContainerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant,

                unfocusedContainerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant,

                disabledContainerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant,

                focusedIndicatorColor =
                    androidx.compose
                        .ui.graphics
                        .Color
                        .Transparent,

                unfocusedIndicatorColor =
                    androidx.compose
                        .ui.graphics
                        .Color
                        .Transparent,

                disabledIndicatorColor =
                    androidx.compose
                        .ui.graphics
                        .Color
                        .Transparent
            )
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
private fun EmptyConversationState() {

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
                "Find someone in Search to start a private conversation.",

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
 * CONVERSATION ITEM
 * ============================================================
 */
@OptIn(
    ExperimentalFoundationApi::class
)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    currentUserId: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {

    val userRepository =
        remember {
            UserRepository()
        }

    var otherUserProfilePhoto by
    remember(
        conversation.otherUserId
    ) {
        mutableStateOf("")
    }

    LaunchedEffect(
        conversation.otherUserId
    ) {

        val user =
            userRepository
                .getUserByIdFast(
                    conversation.otherUserId
                )

        otherUserProfilePhoto =
            user
                ?.profilePhotoData
                ?.ifBlank {
                    user.profilePhotoUrl
                }
                .orEmpty()
    }

    val hasUnread =
        conversation.unreadCount > 0L

    Column {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,

                        onClick =
                            onClick,

                        onLongClick =
                            onLongClick
                    )
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

            /*
             * =================================================
             * AVATAR
             * =================================================
             */
            InChatProfileAvatar(

                profilePhotoUrl =
                    otherUserProfilePhoto,

                modifier =
                    Modifier.size(
                        48.dp
                    ),

                iconSize =
                    25.dp,

                contentDescription =
                    "Profile picture"
            )

            Spacer(
                modifier =
                    Modifier.width(
                        13.dp
                    )
            )

            /*
             * =================================================
             * CONVERSATION
             * =================================================
             */
            Column(

                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {

                Text(

                    text =
                        "@${conversation.otherUsername}",

                    fontSize =
                        17.sp,

                    fontWeight =
                        if (
                            hasUnread
                        ) {

                            FontWeight.Bold

                        } else {

                            FontWeight.SemiBold
                        },

                    color =
                        MaterialTheme
                            .colorScheme
                            .onBackground,

                    maxLines =
                        1,

                    overflow =
                        TextOverflow.Ellipsis
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            3.dp
                        )
                )

                val messagePrefix =
                    if (
                        conversation.lastSenderId ==
                        currentUserId
                    ) {

                        "You: "

                    } else {

                        ""
                    }

                Text(

                    text =
                        messagePrefix +
                                conversation.lastMessage,

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,

                    fontWeight =
                        if (
                            hasUnread
                        ) {

                            FontWeight.Medium

                        } else {

                            FontWeight.Normal
                        },

                    color =
                        if (
                            hasUnread
                        ) {

                            MaterialTheme
                                .colorScheme
                                .onBackground

                        } else {

                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                        },

                    maxLines =
                        1,

                    overflow =
                        TextOverflow.Ellipsis
                )
            }

            Spacer(
                modifier =
                    Modifier.width(
                        12.dp
                    )
            )

            /*
             * =================================================
             * TIME + UNREAD
             * =================================================
             */
            Column(

                horizontalAlignment =
                    Alignment.End
            ) {

                Text(

                    text =
                        formatConversationTime(
                            conversation.lastTimestamp
                        ),

                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,

                    fontWeight =
                        if (
                            hasUnread
                        ) {

                            FontWeight.SemiBold

                        } else {

                            FontWeight.Normal
                        },

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                if (
                    hasUnread
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(
                                6.dp
                            )
                    )

                    Surface(

                        modifier =
                            Modifier.size(
                                20.dp
                            ),

                        shape =
                            CircleShape,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onBackground
                    ) {

                        Box(

                            contentAlignment =
                                Alignment.Center
                        ) {

                            Text(

                                text =
                                    if (
                                        conversation.unreadCount >
                                        99L
                                    ) {

                                        "99+"

                                    } else {

                                        conversation
                                            .unreadCount
                                            .toString()
                                    },

                                fontSize =
                                    9.sp,

                                lineHeight =
                                    10.sp,

                                fontWeight =
                                    FontWeight.Bold,

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .background
                            )
                        }
                    }
                }
            }
        }

        HomeDivider()
    }
}

/*
 * ============================================================
 * DIVIDER
 * ============================================================
 */
@Composable
private fun HomeDivider() {

    Box(

        modifier =
            Modifier
                .fillMaxWidth()
                .height(
                    1.dp
                )
                .background(
                    MaterialTheme
                        .colorScheme
                        .outlineVariant
                )
    )
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