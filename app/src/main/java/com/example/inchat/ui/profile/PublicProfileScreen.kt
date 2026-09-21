package com.example.inchat.ui.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Presence
import com.example.inchat.data.model.User

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun PublicProfileScreen(
    username: String,
    viewModel: PublicProfileViewModel,
    onStartChatClick: (User) -> Unit,
    onBackClick: () -> Unit
) {

    val uiState by
    viewModel
        .uiState
        .collectAsState()

    val presence by
    viewModel
        .presence
        .collectAsState()

    LaunchedEffect(
        username
    ) {

        viewModel.loadProfile(
            username
        )
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
                            "Profile",

                        fontWeight =
                            FontWeight.Bold,

                        letterSpacing =
                            (-0.2).sp
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

        Box(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        innerPadding
                    )
                    .padding(
                        start =
                            20.dp,

                        top =
                            12.dp,

                        end =
                            20.dp,

                        bottom =
                            20.dp
                    ),

            contentAlignment =
                Alignment.TopCenter
        ) {

            when (
                val state =
                    uiState
            ) {

                PublicProfileUiState.Loading -> {

                    CircularProgressIndicator()
                }

                is PublicProfileUiState.Success -> {

                    ProfileContent(

                        user =
                            state.user,

                        presence =
                            presence,

                        onStartChatClick = {

                            onStartChatClick(
                                state.user
                            )
                        }
                    )
                }

                PublicProfileUiState.NotFound -> {

                    ProfileError(

                        title =
                            "User not found",

                        message =
                            "This InChat username does not exist."
                    )
                }

                is PublicProfileUiState.Error -> {

                    ProfileError(

                        title =
                            "Unable to load profile",

                        message =
                            state.message
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileContent(
    user: User,
    presence: Presence,
    onStartChatClick: () -> Unit
) {

    val scrollState =
        rememberScrollState()

    val displayName =
        user.displayName
            .trim()
            .ifBlank {
                user.username
            }

    val bio =
        user.bio
            .trim()

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    scrollState
                )
                .padding(
                    top =
                        12.dp
                ),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.Top
    ) {

        Box(

            modifier =
                Modifier.size(
                    116.dp
                )
        ) {

            InChatProfileAvatar(

                profilePhotoUrl =
                    user.profilePhotoData
                        .ifBlank {
                            user.profilePhotoUrl
                        },

                modifier =
                    Modifier
                        .size(
                            108.dp
                        )
                        .align(
                            Alignment.Center
                        ),

                iconSize =
                    54.dp,

                contentDescription =
                    "Profile picture"
            )

            if (
                presence.online
            ) {

                Surface(

                    modifier =
                        Modifier
                            .size(
                                19.dp
                            )
                            .align(
                                Alignment.BottomEnd
                            )
                            .border(
                                width =
                                    3.dp,

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .background,

                                shape =
                                    CircleShape
                            ),

                    shape =
                        CircleShape,

                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                ) {}
            }
        }

        Spacer(
            modifier =
                Modifier.height(
                    18.dp
                )
        )

        Text(

            text =
                displayName,

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal =
                            8.dp
                    ),

            fontSize =
                28.sp,

            fontWeight =
                FontWeight.Bold,

            textAlign =
                TextAlign.Center,

            maxLines =
                2,

            overflow =
                TextOverflow.Ellipsis,

            color =
                MaterialTheme
                    .colorScheme
                    .onBackground
        )

        Spacer(
            modifier =
                Modifier.height(
                    4.dp
                )
        )

        Text(

            text =
                user.username,

            fontSize =
                16.sp,

            fontWeight =
                FontWeight.Medium,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,

            textAlign =
                TextAlign.Center
        )

        Spacer(
            modifier =
                Modifier.height(
                    8.dp
                )
        )

        PresenceText(
            presence =
                presence
        )

        if (
            bio.isNotBlank()
        ) {

            Spacer(
                modifier =
                    Modifier.height(
                        22.dp
                    )
            )

            Surface(

                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(
                        20.dp
                    ),

                color =
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant,

                tonalElevation =
                    1.dp
            ) {

                Text(

                    text =
                        bio,

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal =
                                    20.dp,

                                vertical =
                                    18.dp
                            ),

                    style =
                        MaterialTheme
                            .typography
                            .bodyLarge,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,

                    textAlign =
                        TextAlign.Center
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(
                    28.dp
                )
        )

        Button(

            onClick =
                onStartChatClick,

            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        54.dp
                    ),

            shape =
                RoundedCornerShape(
                    18.dp
                )
        ) {

            Text(

                text =
                    "Start Private Chat",

                fontSize =
                    16.sp,

                fontWeight =
                    FontWeight.Bold
            )
        }

        Spacer(
            modifier =
                Modifier.height(
                    8.dp
                )
        )

        Text(

            text =
                "Private messaging on InChat",

            style =
                MaterialTheme
                    .typography
                    .bodySmall,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,

            textAlign =
                TextAlign.Center
        )

        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )
    }
}

@Composable
private fun PresenceText(
    presence: Presence
) {

    if (
        presence.online
    ) {

        Text(

            text =
                "Online",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium,

            color =
                MaterialTheme
                    .colorScheme
                    .primary,

            fontWeight =
                FontWeight.SemiBold
        )

    } else {

        Text(

            text =
                formatLastSeen(
                    presence.lastSeen
                ),

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

private fun formatLastSeen(
    timestamp: Long
): String {

    if (
        timestamp <= 0L
    ) {

        return "Offline"
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
            "Last seen just now"

        difference < hour ->
            "Last seen \${difference / minute} min ago"

        difference < day ->
            "Last seen \${difference / hour} hr ago"

        difference < 7 * day ->
            "Last seen \${difference / day} days ago"

        else ->
            "Last seen recently"
    }
}

@Composable
private fun ProfileError(
    title: String,
    message: String
) {

    Column(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    top =
                        96.dp
                ),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.Center
    ) {

        Text(

            text =
                title,

            style =
                MaterialTheme
                    .typography
                    .titleLarge,

            fontWeight =
                FontWeight.Bold,

            textAlign =
                TextAlign.Center
        )

        Spacer(
            modifier =
                Modifier.height(
                    8.dp
                )
        )

        Text(

            text =
                message,

            style =
                MaterialTheme
                    .typography
                    .bodyMedium,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,

            textAlign =
                TextAlign.Center
        )
    }
}
