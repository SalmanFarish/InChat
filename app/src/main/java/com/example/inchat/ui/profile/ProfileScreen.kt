package com.example.inchat.ui.profile

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.auth.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    username: String,
    uid: String,
    authViewModel: AuthViewModel,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onProfilePhotoClick: () -> Unit
) {
    /*
     * Read Compose locals at the composable level.
     *
     * Do not call LocalContext.current from inside an
     * onClick lambda because onClick is not a composable
     * context.
     */
    val context =
        LocalContext.current

    val clipboardManager =
        LocalClipboardManager.current

    val currentDisplayName by
    authViewModel
        .displayName
        .collectAsState()

    val currentBio by
    authViewModel
        .bio
        .collectAsState()

    val repository =
        remember {
            UserRepository()
        }

    var profilePhotoData by
    remember(uid) {
        mutableStateOf("")
    }

    /*
     * Load the latest profile photo from Realtime Database.
     */
    LaunchedEffect(
        uid
    ) {

        if (
            uid.isBlank()
        ) {
            return@LaunchedEffect
        }

        val user =
            repository
                .getUserByIdFast(
                    uid
                )

        if (
            user != null
        ) {

            profilePhotoData =
                user
                    .profilePhotoData
                    .ifBlank {
                        user.profilePhotoUrl
                    }
        }
    }

    val displayName =
        currentDisplayName
            .trim()
            .ifBlank {
                username
            }

    val bio =
        currentBio
            .trim()

    val profileText =
        buildString {

            append(
                displayName
            )

            append(
                "\n@"
            )

            append(
                username
            )

            if (
                bio.isNotBlank()
            ) {

                append(
                    "\n\n"
                )

                append(
                    bio
                )
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
                            "My Profile",

                        fontSize =
                            20.sp,

                        fontWeight =
                            FontWeight.Bold,

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

                actions = {

                    IconButton(

                        onClick =
                            onSettingsClick
                    ) {

                        Icon(

                            imageVector =
                                Icons.Default.Settings,

                            contentDescription =
                                "Settings"
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
                                    .onBackground,

                            actionIconContentColor =
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
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(
                        horizontal =
                            20.dp,

                        vertical =
                            28.dp
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            /*
             * =====================================================
             * PROFILE AVATAR
             * =====================================================
             */

            InChatProfileAvatar(

                profilePhotoUrl =
                    profilePhotoData,

                modifier =
                    Modifier
                        .size(
                            112.dp
                        )
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick =
                                onProfilePhotoClick
                        ),

                iconSize =
                    56.dp,

                contentDescription =
                    "Edit profile picture"
            )

            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )

            TextButton(

                onClick =
                    onProfilePhotoClick
            ) {

                Text(

                    text =
                        if (
                            profilePhotoData.isBlank()
                        ) {

                            "Add profile photo"

                        } else {

                            "Change profile photo"
                        }
                )
            }

            Spacer(
                modifier =
                    Modifier.height(
                        12.dp
                    )
            )

            /*
             * =====================================================
             * DISPLAY NAME
             * =====================================================
             */

            Text(

                text =
                    displayName,

                fontSize =
                    28.sp,

                fontWeight =
                    FontWeight.Bold,

                textAlign =
                    TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(
                        5.dp
                    )
            )

            /*
             * =====================================================
             * USERNAME
             * =====================================================
             */

            Text(

                text =
                    "@$username",

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

            /*
             * =====================================================
             * BIO
             * =====================================================
             */

            if (
                bio.isNotBlank()
            ) {

                Spacer(
                    modifier =
                        Modifier.height(
                            16.dp
                        )
                )

                Text(

                    text =
                        bio,

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal =
                                    12.dp
                            ),

                    style =
                        MaterialTheme
                            .typography
                            .bodyLarge,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurface,

                    textAlign =
                        TextAlign.Center
                )
            }

            Spacer(
                modifier =
                    Modifier.height(
                        30.dp
                    )
            )

            /*
             * =====================================================
             * PROFILE INFORMATION
             * =====================================================
             */

            ProfileInfoRow(

                title =
                    "Username",

                value =
                    "@$username"
            )

            ProfileInfoRow(

                title =
                    "Account ID",

                value =
                    uid
            )

            Spacer(
                modifier =
                    Modifier.height(
                        20.dp
                    )
            )

            /*
             * =====================================================
             * COPY PROFILE
             * =====================================================
             */

            OutlinedButton(

                onClick = {

                    clipboardManager.setText(
                        AnnotatedString(
                            profileText
                        )
                    )
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            52.dp
                        )
            ) {

                Icon(

                    imageVector =
                        Icons.Default.ContentCopy,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    text =
                        "Copy Profile"
                )
            }

            Spacer(
                modifier =
                    Modifier.height(
                        12.dp
                    )
            )

            /*
             * =====================================================
             * SHARE PROFILE
             * =====================================================
             */

            OutlinedButton(

                onClick = {

                    val shareLink =
                        "https://inchat.app/user/$username"

                    val shareText =
                        buildString {

                            append(
                                displayName
                            )

                            append(
                                "\n@"
                            )

                            append(
                                username
                            )

                            if (
                                bio.isNotBlank()
                            ) {

                                append(
                                    "\n\n"
                                )

                                append(
                                    bio
                                )
                            }

                            append(
                                "\n\nChat with me anonymously on InChat:"
                            )

                            append(
                                "\n"
                            )

                            append(
                                shareLink
                            )
                        }

                    val sendIntent =
                        Intent().apply {

                            action =
                                Intent.ACTION_SEND

                            putExtra(
                                Intent.EXTRA_TEXT,
                                shareText
                            )

                            type =
                                "text/plain"
                        }

                    /*
                     * Use the context captured above.
                     * This is the fix for the compiler error.
                     */
                    context.startActivity(
                        Intent.createChooser(
                            sendIntent,
                            "Share InChat Profile"
                        )
                    )
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            52.dp
                        )
            ) {

                Icon(

                    imageVector =
                        Icons.Default.Share,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    text =
                        "Share Profile"
                )
            }

            Spacer(
                modifier =
                    Modifier.height(
                        26.dp
                    )
            )

            TextButton(

                onClick =
                    onSettingsClick,

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Icon(

                    imageVector =
                        Icons.Default.Settings,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    text =
                        "Settings"
                )
            }

            Spacer(
                modifier =
                    Modifier.height(
                        26.dp
                    )
            )

            Text(

                text =
                    "Your username is your permanent InChat identity.",

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
                        8.dp
                    )
            )

            Text(

                text =
                    "Display name and bio can be changed anytime.",

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
        }
    }
}

@Composable
private fun ProfileInfoRow(
    title: String,
    value: String
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical =
                        10.dp
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
                        .labelMedium,

                fontWeight =
                    FontWeight.SemiBold,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Spacer(
                modifier =
                    Modifier.height(
                        3.dp
                    )
            )

            Text(

                text =
                    value,

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium
            )
        }
    }
}