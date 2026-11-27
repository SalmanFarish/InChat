package com.example.inchat.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import com.example.inchat.ui.components.InChatActionDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Presence
import com.example.inchat.data.repository.PresenceRepository
import com.example.inchat.data.repository.UserRepository
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun PrivacySettingsScreen(
    uid: String,
    onBackClick: () -> Unit
) {

    val repository =
        remember {
            PresenceRepository
        }

    val userRepository =
        remember {
            UserRepository()
        }

    val coroutineScope =
        rememberCoroutineScope()

    val presence by
    repository
        .observePresence(
            uid
        )
        .collectAsState(
            initial =
                Presence()
        )

    val readReceiptsVisible by
    userRepository
        .observeReadReceiptsVisible(
            uid
        )
        .collectAsState(
            initial =
                true
        )

    val typingIndicatorVisible by
    userRepository
        .observeTypingIndicatorVisible(
            uid
        )
        .collectAsState(
            initial =
                true
        )

    val discoverableByUsername by
    userRepository
        .observeDiscoverableByUsername(
            uid
        )
        .collectAsState(
            initial =
                true
        )

    var savingVisibility by
    remember {
        mutableStateOf(false)
    }

    var savingReadReceipts by
    remember {
        mutableStateOf(false)
    }

    var savingTypingIndicator by
    remember {
        mutableStateOf(false)
    }

    var savingDiscoverability by
    remember {
        mutableStateOf(false)
    }

    val saving =
        savingVisibility ||
                savingReadReceipts ||
                savingTypingIndicator ||
                savingDiscoverability

    var errorMessage by
    remember {
        mutableStateOf<String?>(null)
    }

    fun updateReadReceiptsVisibility(
        visible: Boolean
    ) {

        if (
            savingReadReceipts
        ) {
            return
        }

        savingReadReceipts =
            true

        coroutineScope.launch {

            userRepository
                .updateReadReceiptsVisible(
                    uid =
                        uid,

                    visible =
                        visible
                )
                .onSuccess {

                    savingReadReceipts =
                        false
                }
                .onFailure { error ->

                    savingReadReceipts =
                        false

                    errorMessage =
                        error.message
                            ?: "Could not update read receipt settings."
                }
        }
    }

    fun updateTypingIndicatorVisibility(
        visible: Boolean
    ) {

        if (
            savingTypingIndicator
        ) {
            return
        }

        savingTypingIndicator =
            true

        coroutineScope.launch {

            userRepository
                .updateTypingIndicatorVisible(
                    uid =
                        uid,

                    visible =
                        visible
                )
                .onSuccess {

                    savingTypingIndicator =
                        false
                }
                .onFailure { error ->

                    savingTypingIndicator =
                        false

                    errorMessage =
                        error.message
                            ?: "Could not update typing indicator settings."
                }
        }
    }

    fun updateDiscoverability(
        discoverable: Boolean
    ) {

        if (
            savingDiscoverability
        ) {
            return
        }

        savingDiscoverability =
            true

        coroutineScope.launch {

            userRepository
                .updateDiscoverableByUsername(
                    uid =
                        uid,

                    discoverable =
                        discoverable
                )
                .onSuccess {

                    savingDiscoverability =
                        false
                }
                .onFailure { error ->

                    savingDiscoverability =
                        false

                    errorMessage =
                        error.message
                            ?: "Could not update discovery settings."
                }
        }
    }

    fun updateVisibility(
        onlineVisible: Boolean,
        lastSeenVisible: Boolean
    ) {

        if (
            savingVisibility
        ) {
            return
        }

        savingVisibility =
            true

        coroutineScope.launch {

            repository
                .updateVisibility(
                    uid =
                        uid,

                    onlineVisible =
                        onlineVisible,

                    lastSeenVisible =
                        lastSeenVisible
                )
                .onSuccess {

                    savingVisibility =
                        false
                }
                .onFailure { error ->

                    savingVisibility =
                        false

                    errorMessage =
                        error.message
                            ?: "Could not update privacy settings."
                }
        }
    }

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
                            "Privacy",

                        fontSize =
                            20.sp,

                        fontWeight = FontWeight.Medium,

                        letterSpacing =
                            (-0.3).sp
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
                                    .background
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

            contentPadding =
                androidx.compose
                    .foundation
                    .layout
                    .PaddingValues(
                        start =
                            16.dp,

                        top =
                            10.dp,

                        end =
                            16.dp,

                        bottom =
                            28.dp
                    ),

            verticalArrangement =
                Arrangement.spacedBy(
                    20.dp
                )
        ) {

            item {
                PrivacyIntro()
            }

            item {
                PrivacySectionCard(title = "Activity status") {
                    PrivacyToggleRow(
                        title = "Last seen",
                        description = "Show other people when you were last active.",
                        checked = presence.lastSeenVisible,
                        enabled = !savingVisibility,
                        onCheckedChange = { checked ->
                            updateVisibility(
                                onlineVisible = presence.onlineVisible,
                                lastSeenVisible = checked
                            )
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    PrivacyToggleRow(
                        title = "Online status",
                        description = "Show other people when you are currently online.",
                        checked = presence.onlineVisible,
                        enabled = !savingVisibility,
                        onCheckedChange = { checked ->
                            updateVisibility(
                                onlineVisible = checked,
                                lastSeenVisible = presence.lastSeenVisible
                            )
                        }
                    )
                }
            }

            item {
                PrivacySectionCard(title = "Messages") {
                    PrivacyToggleRow(
                        title = "Read receipts",
                        description = "Let other people see when you have read their messages.",
                        checked = readReceiptsVisible,
                        enabled = !savingReadReceipts,
                        onCheckedChange = { checked ->
                            updateReadReceiptsVisibility(checked)
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    PrivacyToggleRow(
                        title = "Typing indicator",
                        description = "Let other people see when you are typing a message.",
                        checked = typingIndicatorVisible,
                        enabled = !savingTypingIndicator,
                        onCheckedChange = { checked ->
                            updateTypingIndicatorVisibility(checked)
                        }
                    )
                }
            }

            item {
                PrivacySectionCard(title = "Discoverability") {
                    PrivacyToggleRow(
                        title = "Who can find me",
                        description = "Allow other people to discover your account through username search.",
                        checked = discoverableByUsername,
                        enabled = !saving,
                        onCheckedChange = { checked ->
                            updateDiscoverability(checked)
                        }
                    )
                }
            }

            item {
                PrivacyExplanation()
            }

            if (
                saving
            ) {

                item {

                    Row(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal =
                                        4.dp
                                ),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        CircularProgressIndicator(

                            modifier =
                                Modifier.size(
                                    18.dp
                                ),

                            strokeWidth =
                                2.dp
                        )

                        Spacer(
                            modifier =
                                Modifier.width(
                                    10.dp
                                )
                        )

                        Text(

                            text =
                                "Saving privacy settings…",

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
    }

    errorMessage?.let { message ->
        InChatActionDialog(
            title = "Privacy",
            description = message,
            confirmLabel = "OK",
            confirmIcon = Icons.Outlined.Info,
            confirmDestructive = false,
            showCancel = false,
            onConfirm = { errorMessage = null },
            onDismiss = { errorMessage = null }
        )
    }}

@Composable
private fun PrivacyIntro() {
    Text(
        text = "Choose which activity and message details other people can see.",
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PrivacySectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun PrivacyToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource =
                        null,

                    indication =
                        null,

                    enabled =
                        enabled,

                    onClick = {

                        onCheckedChange(
                            !checked
                        )
                    }
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 13.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(

            modifier =
                Modifier.weight(
                    1f
                )
        ) {

            Text(

                text =
                    title,

                style =
                    MaterialTheme
                        .typography
                        .titleSmall,

                fontWeight = FontWeight.Medium
            )

            Spacer(
                modifier =
                    Modifier.height(
                        3.dp
                    )
            )

            Text(

                text =
                    description,

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

        Spacer(
            modifier =
                Modifier.width(
                    12.dp
                )
        )

        Switch(

            checked =
                checked,

            onCheckedChange =
                if (
                    enabled
                ) {
                    onCheckedChange
                } else {
                    null
                }
        )
    }
}

@Composable
private fun PrivacyExplanation() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(
            text = "When a setting is off, InChat stops publishing that information to other users. Your privacy choice does not remove your account or messages.",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
