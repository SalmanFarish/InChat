package com.example.inchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.inchat.data.repository.PresenceRepository
import com.example.inchat.notification.InChatFirebaseMessagingService
import com.example.inchat.ui.auth.AuthUiState
import com.example.inchat.ui.auth.AuthViewModel
import com.example.inchat.ui.auth.LoginScreen
import com.example.inchat.ui.home.HomeViewModel
import com.example.inchat.ui.navigation.InChatApp
import com.example.inchat.ui.navigation.NotificationChatTarget
import com.example.inchat.ui.theme.InChatTheme
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        handleNotificationIntent(
            intent
        )

        preloadNotificationChat()

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

        preloadNotificationChat()
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

    private fun preloadNotificationChat() {

        val target =
            notificationTarget.value
                ?: return

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                FirebaseDatabase
                    .getInstance()
                    .getReference(
                        "chats"
                    )
                    .child(
                        target.chatId
                    )
                    .child(
                        "messages"
                    )
                    .orderByChild(
                        "timestamp"
                    )
                    .limitToLast(
                        200
                    )
                    .get()
                    .await()

            } catch (
                _: Exception
            ) {

                /*
                 * ChatScreen handles realtime loading itself.
                 */
            }
        }
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

                AuthUiState.LoggedOut -> {

                    PresenceRepository
                        .stopPresence()
                }

                is AuthUiState.LoggedIn -> {

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

                is AuthUiState.LoggedIn -> {

                    NotificationPermissionRequest()

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