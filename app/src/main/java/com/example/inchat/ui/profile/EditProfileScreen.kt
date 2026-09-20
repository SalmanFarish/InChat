package com.example.inchat.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.inchat.data.repository.UserRepository
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun EditProfileScreen(
    username: String,
    uid: String,
    currentDisplayName: String,
    currentBio: String,
    onBackClick: () -> Unit,
    onProfileSaved: (
        String,
        String
    ) -> Unit
) {

    val repository =
        remember {
            UserRepository()
        }

    val coroutineScope =
        rememberCoroutineScope()

    var displayName by
    remember(
        currentDisplayName
    ) {
        mutableStateOf(
            currentDisplayName
        )
    }

    var bio by
    remember(
        currentBio
    ) {
        mutableStateOf(
            currentBio
        )
    }

    var saving by
    remember {
        mutableStateOf(false)
    }

    var errorMessage by
    remember {
        mutableStateOf<String?>(
            null
        )
    }

    val cleanName =
        displayName.trim()

    val cleanBio =
        bio.trim()

    val canSave =
        !saving &&
                cleanName.length <= 30 &&
                cleanBio.length <= 160

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
                            "Edit profile",

                        fontWeight =
                            FontWeight.Bold
                    )
                },

                navigationIcon = {

                    IconButton(

                        enabled =
                            !saving,

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

        Column(

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
                            24.dp,

                        end =
                            20.dp,

                        bottom =
                            24.dp
                    )
        ) {

            Text(

                text =
                    "Display name",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )

            OutlinedTextField(

                value =
                    displayName,

                onValueChange = {

                    if (
                        it.length <= 30
                    ) {

                        displayName =
                            it

                        errorMessage =
                            null
                    }
                },

                modifier =
                    Modifier.fillMaxWidth(),

                singleLine =
                    true,

                label = {

                    Text(
                        "Name"
                    )
                },

                supportingText = {

                    Text(
                        "${displayName.length}/30"
                    )
                },

                enabled =
                    !saving
            )

            Spacer(
                modifier =
                    Modifier.height(
                        24.dp
                    )
            )

            Text(

                text =
                    "Bio",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )

            OutlinedTextField(

                value =
                    bio,

                onValueChange = {

                    if (
                        it.length <= 160
                    ) {

                        bio =
                            it

                        errorMessage =
                            null
                    }
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            150.dp
                        ),

                label = {

                    Text(
                        "About you"
                    )
                },

                placeholder = {

                    Text(
                        "Tell people a little about yourself..."
                    )
                },

                supportingText = {

                    Text(
                        "${bio.length}/160"
                    )
                },

                enabled =
                    !saving,

                maxLines =
                    6
            )

            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )

            Text(

                text =
                    "@$username",

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,

                textAlign =
                    TextAlign.Start
            )

            Spacer(
                modifier =
                    Modifier.height(
                        4.dp
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
                        .onSurfaceVariant
            )

            if (
                errorMessage != null
            ) {

                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )

                Text(

                    text =
                        errorMessage!!,

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .error
                )
            }

            Spacer(
                modifier =
                    Modifier.height(
                        24.dp
                    )
            )

            Button(

                onClick = {

                    if (
                        cleanName.length >
                        30
                    ) {

                        errorMessage =
                            "Display name must be 30 characters or less."

                        return@Button
                    }

                    if (
                        cleanBio.length >
                        160
                    ) {

                        errorMessage =
                            "Bio must be 160 characters or less."

                        return@Button
                    }

                    if (
                        uid.isBlank()
                    ) {

                        errorMessage =
                            "Unable to identify your account."

                        return@Button
                    }

                    saving =
                        true

                    errorMessage =
                        null

                    coroutineScope.launch {

                        repository
                            .updateProfile(

                                uid =
                                    uid,

                                displayName =
                                    cleanName,

                                bio =
                                    cleanBio
                            )
                            .onSuccess {

                                saving =
                                    false

                                onProfileSaved(
                                    cleanName,
                                    cleanBio
                                )
                            }
                            .onFailure { error ->

                                saving =
                                    false

                                errorMessage =
                                    error.message
                                        ?: "Could not save profile."
                            }
                    }
                },

                enabled =
                    canSave,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            52.dp
                        )
            ) {

                if (
                    saving
                ) {

                    CircularProgressIndicator(

                        modifier =
                            Modifier.size(
                                20.dp
                            ),

                        strokeWidth =
                            2.dp,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onPrimary
                    )

                } else {

                    Text(

                        text =
                            "Save changes",

                        fontWeight =
                            FontWeight.SemiBold
                    )
                }
            }
        }
    }
}