package com.example.inchat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

    var saving by
    remember {
        mutableStateOf(false)
    }

    var errorMessage by
    remember {
        mutableStateOf<String?>(null)
    }

    fun updateReadReceiptsVisibility(
        visible: Boolean
    ) {

        if (
            saving
        ) {
            return
        }

        saving =
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

                    saving =
                        false
                }
                .onFailure { error ->

                    saving =
                        false

                    errorMessage =
                        error.message
                            ?: "Could not update read receipt settings."
                }
        }
    }

    fun updateVisibility(
        onlineVisible: Boolean,
        lastSeenVisible: Boolean
    ) {

        if (
            saving
        ) {
            return
        }

        saving =
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

                    saving =
                        false
                }
                .onFailure { error ->

                    saving =
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

                        fontWeight =
                            androidx.compose
                                .ui.text.font
                                .FontWeight
                                .Bold,

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

                PrivacyHeader()
            }

            item {

                PrivacySectionLabel(
                    "ACTIVITY VISIBILITY"
                )
            }

            item {

                PrivacyToggleRow(

                    title =
                        "Last seen",

                    description =
                        "Show other people when you were last active.",

                    checked =
                        presence.lastSeenVisible,

                    enabled =
                        !saving,

                    onCheckedChange = { checked ->

                        updateVisibility(
                            onlineVisible =
                                presence.onlineVisible,

                            lastSeenVisible =
                                checked
                        )
                    }
                )
            }

            item {

                PrivacyToggleRow(

                    title =
                        "Online status",

                    description =
                        "Show other people when you are currently online.",

                    checked =
                        presence.onlineVisible,

                    enabled =
                        !saving,

                    onCheckedChange = { checked ->

                        updateVisibility(
                            onlineVisible =
                                checked,

                            lastSeenVisible =
                                presence.lastSeenVisible
                        )
                    }
                )
            }

            item {

                PrivacySectionLabel(
                    "MESSAGE PRIVACY"
                )
            }

            item {

                PrivacyToggleRow(

                    title =
                        "Read receipts",

                    description =
                        "Let other people see when you have read their messages.",

                    checked =
                        readReceiptsVisible,

                    enabled =
                        !saving,

                    onCheckedChange = { checked ->

                        updateReadReceiptsVisibility(
                            checked
                        )
                    }
                )
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

    if (
        errorMessage != null
    ) {

        AlertDialog(

            onDismissRequest = {

                errorMessage =
                    null
            },

            title = {

                Text(
                    "Privacy"
                )
            },

            text = {

                Text(
                    errorMessage!!
                )
            },

            confirmButton = {

                androidx.compose
                    .material3
                    .TextButton(

                        onClick = {

                            errorMessage =
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
}

@Composable
private fun PrivacyHeader() {

    Column(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start =
                        4.dp,

                    top =
                        4.dp,

                    end =
                        4.dp
                )
    ) {

        Text(

            text =
                "Choose what other people can see.",

            style =
                MaterialTheme
                    .typography
                    .titleMedium,

            fontWeight =
                androidx.compose
                    .ui.text.font
                    .FontWeight
                    .Bold
        )

        Spacer(
            modifier =
                Modifier.height(
                    5.dp
                )
        )

        Text(

            text =
                "These controls affect presence and message-read information that other people can see.",

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
private fun PrivacySectionLabel(
    title: String
) {

    Text(

        text =
            title,

        modifier =
            Modifier.padding(
                horizontal =
                    4.dp
            ),

        style =
            MaterialTheme
                .typography
                .labelSmall,

        fontWeight =
            androidx.compose
                .ui.text.font
                .FontWeight
                .Bold,

        letterSpacing =
            0.8.sp,

        color =
            MaterialTheme
                .colorScheme
                .onSurfaceVariant
    )
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
                    horizontal =
                        4.dp,

                    vertical =
                        4.dp
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

                fontWeight =
                    androidx.compose
                        .ui.text.font
                        .FontWeight
                        .SemiBold
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

    Text(

        text =
            "When a setting is off, InChat stops publishing that information to other users. Your privacy choice does not remove your account or messages.",

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal =
                        4.dp
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
