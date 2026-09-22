package com.example.inchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.data.repository.ChatAppearanceRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun ChatInfoScreen(
    currentUserId: String,
    otherUserId: String,
    otherUserNickname: String,
    chatViewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onChatThemeClick: () -> Unit
) {

    val userRepository =
        remember {
            UserRepository()
        }

    val appearanceRepository =
        remember {
            ChatAppearanceRepository()
        }

    val chatId =
        remember(currentUserId, otherUserId) {
            com.example.inchat.data.repository.ChatRepository()
                .getChatRoomId(
                    currentUserId,
                    otherUserId
                )
        }

    val selectedThemeId by
        appearanceRepository
            .observeTheme(chatId)
            .collectAsState(
                initial = ChatTheme.DESSERT.id
            )

    val selectedTheme =
        ChatTheme.fromId(
            selectedThemeId
        )

    var otherUserProfilePhoto by
    remember(otherUserId) {
        mutableStateOf("")
    }

    LaunchedEffect(otherUserId) {
        val user =
            userRepository
                .getUserByIdFast(otherUserId)

        otherUserProfilePhoto =
            user
                ?.profilePhotoData
                ?.ifBlank {
                    user.profilePhotoUrl
                }
                .orEmpty()
    }

    val blockState by
        chatViewModel
            .blockState
            .collectAsState()

    val otherUserPresence by
        chatViewModel
            .otherUserPresence
            .collectAsState()

    LaunchedEffect(currentUserId, otherUserId) {
        chatViewModel.startListening(
            currentUserId = currentUserId,
            otherUserId = otherUserId
        )
    }

    var showBlockDialog by
    remember {
        mutableStateOf(false)
    }

    var showReportDialog by
    remember {
        mutableStateOf(false)
    }

    var reportReason by
    remember {
        mutableStateOf("")
    }

    var actionMessage by
    remember {
        mutableStateOf<String?>(
            null
        )
    }

    /*
     * =========================================================
     * BLOCK / UNBLOCK DIALOG
     * =========================================================
     */
    if (
        showBlockDialog
    ) {

        AlertDialog(

            onDismissRequest = {

                showBlockDialog =
                    false
            },

            title = {

                Text(

                    when (
                        blockState
                    ) {

                        BlockState.NONE ->
                            "Block $otherUserNickname?"

                        BlockState.I_BLOCKED_THEM ->
                            "Unblock $otherUserNickname?"

                        BlockState.THEY_BLOCKED_ME ->
                            "You can't unblock this user"
                    }
                )
            },

            text = {

                Text(

                    when (
                        blockState
                    ) {

                        BlockState.NONE ->
                            "You will no longer be able to send messages to this user."

                        BlockState.I_BLOCKED_THEM ->
                            "You will be able to chat with this user again."

                        BlockState.THEY_BLOCKED_ME ->
                            "This user has blocked you. You cannot change their block."
                    }
                )
            },

            confirmButton = {

                when (
                    blockState
                ) {

                    BlockState.NONE -> {

                        TextButton(

                            onClick = {

                                chatViewModel
                                    .blockUser(

                                        currentUserId =
                                            currentUserId,

                                        otherUserId =
                                            otherUserId,

                                        otherUsername =
                                            otherUserNickname

                                    ) { success, error ->

                                        actionMessage =
                                            if (
                                                success
                                            ) {
                                                "User blocked"
                                            } else {
                                                error
                                                    ?: "Could not block user"
                                            }
                                    }

                                showBlockDialog =
                                    false
                            }
                        ) {

                            Text(
                                "Block"
                            )
                        }
                    }

                    BlockState.I_BLOCKED_THEM -> {

                        TextButton(

                            onClick = {

                                chatViewModel
                                    .unblockUser(

                                        currentUserId =
                                            currentUserId,

                                        otherUserId =
                                            otherUserId

                                    ) { success, error ->

                                        actionMessage =
                                            if (
                                                success
                                            ) {
                                                "User unblocked"
                                            } else {
                                                error
                                                    ?: "Could not unblock user"
                                            }
                                    }

                                showBlockDialog =
                                    false
                            }
                        ) {

                            Text(
                                "Unblock"
                            )
                        }
                    }

                    BlockState.THEY_BLOCKED_ME -> {
                        /*
                         * No confirm action.
                         */
                    }
                }
            },

            dismissButton = {

                TextButton(

                    onClick = {

                        showBlockDialog =
                            false
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
     * REPORT DIALOG
     * =========================================================
     */
    if (
        showReportDialog
    ) {

        AlertDialog(

            onDismissRequest = {

                showReportDialog =
                    false
            },

            title = {

                Text(
                    "Report $otherUserNickname"
                )
            },

            text = {

                Column {

                    Text(
                        text =
                            "Why are you reporting this user?",

                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                12.dp
                            )
                    )

                    ChatReportReason(
                        label =
                            "Spam",

                        selected =
                            reportReason ==
                                    "Spam",

                        onClick = {

                            reportReason =
                                "Spam"
                        }
                    )

                    ChatReportReason(
                        label =
                            "Harassment",

                        selected =
                            reportReason ==
                                    "Harassment",

                        onClick = {

                            reportReason =
                                "Harassment"
                        }
                    )

                    ChatReportReason(
                        label =
                            "Inappropriate content",

                        selected =
                            reportReason ==
                                    "Inappropriate content",

                        onClick = {

                            reportReason =
                                "Inappropriate content"
                        }
                    )

                    ChatReportReason(
                        label =
                            "Other",

                        selected =
                            reportReason ==
                                    "Other",

                        onClick = {

                            reportReason =
                                "Other"
                        }
                    )
                }
            },

            confirmButton = {

                TextButton(

                    enabled =
                        reportReason
                            .isNotBlank(),

                    onClick = {

                        chatViewModel
                            .reportUser(

                                reporterId =
                                    currentUserId,

                                reportedUserId =
                                    otherUserId,

                                reportedUsername =
                                    otherUserNickname,

                                reason =
                                    reportReason

                            ) { success, error ->

                                actionMessage =
                                    if (
                                        success
                                    ) {
                                        "Report submitted"
                                    } else {
                                        error
                                            ?: "Could not submit report"
                                    }
                            }

                        reportReason =
                            ""

                        showReportDialog =
                            false
                    }
                ) {

                    Text(
                        "Report"
                    )
                }
            },

            dismissButton = {

                TextButton(

                    onClick = {

                        reportReason =
                            ""

                        showReportDialog =
                            false
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
     * CHAT INFO
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
                            "Chat info",

                        fontWeight =
                            androidx.compose
                                .ui.text.font
                                .FontWeight.Bold
                    )
                },

                navigationIcon = {

                    IconButton(

                        onClick =
                            onBackClick
                    ) {

                        Icon(

                            imageVector =
                                Icons.AutoMirrored
                                    .Filled
                                    .ArrowBack,

                            contentDescription =
                                "Back"
                        )
                    }
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
                                    .onBackground,

                            navigationIconContentColor =
                                MaterialTheme
                                    .colorScheme
                                    .onBackground
                        )
            )
        }

    ) { innerPadding ->

        LazyColumn(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        innerPadding
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            /*
             * =================================================
             * USER HEADER
             * =================================================
             */
            item {

                Column(

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start =
                                    20.dp,

                                top =
                                    24.dp,

                                end =
                                    20.dp,

                                bottom =
                                    24.dp
                            ),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    InChatProfileAvatar(

                        profilePhotoUrl =
                            otherUserProfilePhoto,

                        modifier =
                            Modifier.size(
                                82.dp
                            ),

                        iconSize =
                            40.dp,

                        contentDescription =
                            "Profile picture"
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                14.dp
                            )
                    )

                    Text(

                        text =
                            "$otherUserNickname",

                        fontSize =
                            24.sp,

                        fontWeight =
                            androidx.compose
                                .ui.text.font
                                .FontWeight.Bold,

                        letterSpacing =
                            (-0.5).sp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                3.dp
                            )
                    )

                    PresenceStatus(
                        presence =
                            otherUserPresence,
                        isTyping = false
                    )
                }
            }

            item {

                ChatInfoDivider()
            }

            /*
             * =================================================
             * APPEARANCE TITLE
             * =================================================
             */
            item {

                Text(

                    text =
                        "APPEARANCE",

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start =
                                    20.dp,

                                top =
                                    18.dp,

                                end =
                                    20.dp,

                                bottom =
                                    7.dp
                            ),

                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,

                    fontWeight =
                        androidx.compose
                            .ui.text.font
                            .FontWeight
                            .SemiBold,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            item {

                Text(

                    text =
                        "Choose the look for this conversation.",

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start =
                                    20.dp,

                                end =
                                    20.dp,

                                bottom =
                                    9.dp
                            ),

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

            item {

                ChatInfoActionRow(
                    title = "Chat theme",
                    subtitle =
                        selectedTheme.title +
                                " · " +
                                selectedTheme.description,
                    enabled = true,
                    onClick = onChatThemeClick
                )
            }

            item {

                ChatInfoDivider()
            }

            /*
             * =================================================
             * SAFETY
             * =================================================
             */
            item {

                Text(

                    text =
                        "SAFETY",

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start =
                                    20.dp,

                                top =
                                    18.dp,

                                end =
                                    20.dp,

                                bottom =
                                    7.dp
                            ),

                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,

                    fontWeight =
                        androidx.compose
                            .ui.text.font
                            .FontWeight
                            .SemiBold,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            item {

                ChatInfoActionRow(

                    title =
                        when (
                            blockState
                        ) {

                            BlockState.I_BLOCKED_THEM ->
                                "Unblock user"

                            BlockState.THEY_BLOCKED_ME ->
                                "Blocked you"

                            BlockState.NONE ->
                                "Block user"
                        },

                    subtitle =
                        when (
                            blockState
                        ) {

                            BlockState.I_BLOCKED_THEM ->
                                "Allow messaging again"

                            BlockState.THEY_BLOCKED_ME ->
                                "This user has blocked you"

                            BlockState.NONE ->
                                "Stop messages from this user"
                        },

                    enabled =
                        blockState !=
                                BlockState.THEY_BLOCKED_ME,

                    onClick = {

                        showBlockDialog =
                            true
                    }
                )
            }

            item {

                ChatInfoActionRow(

                    title =
                        "Report user",

                    subtitle =
                        "Report inappropriate behavior or content",

                    enabled =
                        true,

                    onClick = {

                        reportReason =
                            ""

                        showReportDialog =
                            true
                    }
                )
            }

            item {

                Text(

                    text =
                        "Chat appearance changes are shared with both participants.",

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start =
                                    20.dp,

                                top =
                                    18.dp,

                                end =
                                    20.dp,

                                bottom =
                                    28.dp
                            ),

                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,

                    textAlign =
                        androidx.compose
                            .ui.text.style
                            .TextAlign
                            .Center
                )
            }
        }
    }
}

/*
 * ============================================================
 * ACTION ROW
 * ============================================================
 */
@Composable
private fun ChatInfoActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {

    Column(

        modifier =
            Modifier
                .fillMaxWidth()
                .then(

                    if (
                        enabled
                    ) {

                        Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick =
                                onClick
                        )

                    } else {

                        Modifier
                    }
                )
                .padding(
                    start =
                        20.dp,

                    top =
                        15.dp,

                    end =
                        20.dp,

                    bottom =
                        15.dp
                )
    ) {

        Text(

            text =
                title,

            fontSize =
                16.sp,

            fontWeight =
                androidx.compose
                    .ui.text.font
                    .FontWeight
                    .SemiBold,

            color =
                if (
                    enabled
                ) {

                    MaterialTheme
                        .colorScheme
                        .onBackground

                } else {

                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
                }
        )

        Text(

            text =
                subtitle,

            modifier =
                Modifier.padding(
                    top =
                        2.dp
                ),

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

/*
 * ============================================================
 * DIVIDER
 * ============================================================
 */
@Composable
private fun ChatInfoDivider() {

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
 * REPORT REASON
 * ============================================================
 */
@Composable
private fun ChatReportReason(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {

    androidx.compose
        .foundation
        .layout
        .Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick =
                            onClick
                    )
                    .padding(
                        vertical =
                            2.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            RadioButton(

                selected =
                    selected,

                onClick =
                    onClick
            )

            Text(

                text =
                    label,

                modifier =
                    Modifier.padding(
                        start =
                            8.dp
                    )
            )
        }
}