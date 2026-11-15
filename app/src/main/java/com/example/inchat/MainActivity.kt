package com.example.inchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.inchat.data.repository.FirebaseServerClock
import com.example.inchat.data.repository.PresenceRepository
import com.example.inchat.notification.InChatFirebaseMessagingService
import com.example.inchat.notification.ActiveConversationTracker
import com.example.inchat.ui.auth.AuthUiState
import com.example.inchat.ui.auth.AuthViewModel
import com.example.inchat.ui.auth.LoginScreen
import com.example.inchat.ui.auth.EmailVerificationScreen
import com.example.inchat.ui.auth.GoogleUsernameSetupScreen
import com.example.inchat.ui.home.HomeViewModel
import com.example.inchat.ui.navigation.InChatApp
import com.example.inchat.ui.navigation.NotificationChatTarget
import com.example.inchat.ui.theme.InChatTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity :
    ComponentActivity() {

    private val authViewModel:
            AuthViewModel by viewModels()

    private val homeViewModel:
            HomeViewModel by viewModels()

    private val notificationTarget =
        MutableStateFlow<NotificationChatTarget?>(
            null
        )

    override fun onStart() {
        super.onStart()
        ActiveConversationTracker.setAppInForeground(true)
    }

    override fun onStop() {
        ActiveConversationTracker.setAppInForeground(false)
        super.onStop()
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        enableEdgeToEdge()

        handleNotificationIntent(
            intent
        )

        setContent {

            /*
             * Apply the actual InChat theme here.
             *
             * This makes the new monochrome Color.kt
             * and Typography.kt apply to the entire app.
             */
            InChatTheme {

                InChatRoot()
            }
        }
    }

    override fun onNewIntent(
        intent: Intent
    ) {

        super.onNewIntent(
            intent
        )

        setIntent(
            intent
        )

        handleNotificationIntent(
            intent
        )

    }

    private fun handleNotificationIntent(
        intent: Intent?
    ) {

        if (
            intent == null
        ) {

            return
        }

        val notificationType =
            intent.getStringExtra(
                InChatFirebaseMessagingService
                    .EXTRA_NOTIFICATION_TYPE
            )

        val chatId =
            intent
                .getStringExtra(
                    InChatFirebaseMessagingService
                        .EXTRA_CHAT_ID
                )
                ?.trim()
                .orEmpty()

        val otherUserId =
            intent
                .getStringExtra(
                    InChatFirebaseMessagingService
                        .EXTRA_OTHER_USER_ID
                )
                ?.trim()
                .orEmpty()

        val otherUserNickname =
            intent
                .getStringExtra(
                    InChatFirebaseMessagingService
                        .EXTRA_OTHER_USER_NICKNAME
                )
                ?.trim()
                ?.removePrefix("@")
                .orEmpty()

        val isChatNotification =
            notificationType ==
                    InChatFirebaseMessagingService
                        .NOTIFICATION_TYPE_CHAT ||
                    (
                            chatId.isNotBlank() &&
                                    otherUserId.isNotBlank()
                            )

        if (
            !isChatNotification
        ) {

            return
        }

        if (
            chatId.isBlank() ||
            otherUserId.isBlank()
        ) {

            return
        }

        notificationTarget.value =
            NotificationChatTarget(

                chatId =
                    chatId,

                otherUserId =
                    otherUserId,

                otherUserNickname =
                    if (
                        otherUserNickname.isNotBlank()
                    ) {

                        otherUserNickname

                    } else {

                        "InChat user"
                    }
            )
    }

@Composable
    private fun InChatRoot() {

        val uiState by
        authViewModel
            .uiState
            .collectAsState()

        val currentNotificationTarget by
        notificationTarget
            .collectAsState()

        var shouldRequestNotificationPermission by
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                false
            )
        }

        var notificationPermissionRequested by
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                false
            )
        }

        LaunchedEffect(
            uiState
        ) {

            when (
                val state =
                    uiState
            ) {

                AuthUiState.Loading -> {
                    /*
                     * Nothing.
                     */
                }

                AuthUiState.LoggedOut,
                is AuthUiState.NeedsUsernameSetup,
                is AuthUiState.NeedsEmailVerification -> {

                    FirebaseServerClock
                        .stop()

                    PresenceRepository
                        .stopPresence()
                }

                is AuthUiState.LoggedIn -> {

                    FirebaseServerClock
                        .start()

                    PresenceRepository
                        .startPresence(
                            state.uid
                        )
                }
            }
        }

        Surface(

            modifier =
                Modifier.fillMaxSize(),

            color =
                MaterialTheme
                    .colorScheme
                    .background
        ) {

            when (
                val state =
                    uiState
            ) {

                AuthUiState.Loading -> {

                    LoadingScreen()
                }

                AuthUiState.LoggedOut -> {

                    LoginScreen(
                        authViewModel =
                            authViewModel
                    )
                }

                is AuthUiState.NeedsUsernameSetup -> {

                    GoogleUsernameSetupScreen(
                        state =
                            state,

                        authViewModel =
                            authViewModel
                    )
                }

                is AuthUiState.NeedsEmailVerification -> {

                    EmailVerificationScreen(
                        state = state,
                        authViewModel = authViewModel
                    )
                }

                is AuthUiState.LoggedIn -> {

                    if (
                        shouldRequestNotificationPermission &&
                        !notificationPermissionRequested
                    ) {
                        notificationPermissionRequested =
                            true

                        NotificationPermissionRequest()
                    }

                    InChatApp(

                        uid =
                            state.uid,

                        username =
                            state.username,

                        authViewModel =
                            authViewModel,

                        homeViewModel =
                            homeViewModel,

                        notificationTarget =
                            currentNotificationTarget,

                        onChatOpened = {
                            shouldRequestNotificationPermission =
                                true
                        },

                        onNotificationHandled = {

                            notificationTarget.value =
                                null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {

    Box(

        modifier =
            Modifier.fillMaxSize(),

        contentAlignment =
            Alignment.Center
    ) {

        CircularProgressIndicator()
    }
}

@Composable
private fun NotificationPermissionRequest() {

    if (
        Build.VERSION.SDK_INT <
        Build.VERSION_CODES.TIRAMISU
    ) {

        return
    }

    val context =
        LocalContext.current

    val launcher =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .RequestPermission()

        ) {
            /*
             * Nothing.
             */
        }

    LaunchedEffect(
        context
    ) {

        val permission =
            Manifest.permission.POST_NOTIFICATIONS

        val alreadyGranted =
            ContextCompat
                .checkSelfPermission(
                    context,
                    permission
                ) ==
                    PackageManager.PERMISSION_GRANTED

        if (
            !alreadyGranted
        ) {

            launcher.launch(
                permission
            )
        }
    }
}