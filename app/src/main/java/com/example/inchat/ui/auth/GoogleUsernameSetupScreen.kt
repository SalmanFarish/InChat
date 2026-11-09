package com.example.inchat.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GoogleUsernameSetupScreen(
    state: AuthUiState.NeedsUsernameSetup,
    authViewModel: AuthViewModel
) {

    var username by remember(state.uid) {
        mutableStateOf(
            state.suggestedUsername
        )
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    Surface(
        modifier =
            Modifier.fillMaxSize(),

        color =
            MaterialTheme.colorScheme.background
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(24.dp),

            verticalArrangement =
                Arrangement.Center,

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                text =
                    "Choose your InChat username",

                fontSize =
                    28.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    MaterialTheme.colorScheme.primary,

                textAlign =
                    TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Text(
                text =
                    "Google account: ${state.email}\nYour username is how other people find you on InChat.",

                style =
                    MaterialTheme.typography.bodyMedium,

                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,

                textAlign =
                    TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(28.dp)
            )

            OutlinedTextField(
                value =
                    username,

                onValueChange = {
                    username =
                        it.take(20)

                    errorMessage =
                        null
                },

                modifier =
                    Modifier.fillMaxWidth(),

                singleLine =
                    true,

                enabled =
                    !isLoading,

                label = {
                    Text("InChat username")
                },

                supportingText = {
                    Text("3-20 characters: letters, numbers, _ or .")
                }
            )

            if (errorMessage != null) {

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        errorMessage.orEmpty(),

                    style =
                        MaterialTheme.typography.bodySmall,

                    color =
                        MaterialTheme.colorScheme.error,

                    textAlign =
                        TextAlign.Center
                )
            }

            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )

            Button(
                onClick = {

                    isLoading =
                        true

                    errorMessage =
                        null

                    authViewModel
                        .completeGoogleRegistration(
                            username
                        ) { error ->

                            isLoading =
                                false

                            errorMessage =
                                error
                        }
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp),

                enabled =
                    !isLoading &&
                            username.isNotBlank()
            ) {

                if (isLoading) {

                    CircularProgressIndicator()

                } else {

                    Text(
                        text =
                            "Create InChat profile",

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            TextButton(
                onClick = {
                    authViewModel
                        .cancelGoogleRegistration()
                },

                enabled =
                    !isLoading
            ) {

                Text(
                    "Use a different Google account"
                )
            }
        }
    }
}
