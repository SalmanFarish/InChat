package com.example.inchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.DeviceKeyDirectoryRepository
import com.example.inchat.data.worker.EncryptedOlmDeviceWorkScheduler
import com.example.inchat.data.worker.EncryptedOlmInboxWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private var isActivityStarted = false
    private var presenceUid: String? = null
    private var encryptedInboxSetupJob: Job? = null
    private var encryptedInboxReference: DatabaseReference? = null
    private var encryptedInboxChildListener: ChildEventListener? = null

    private val encryptedInboxAuthListener = FirebaseAuth.AuthStateListener { auth ->
        restartForegroundEncryptedInboxMonitor(auth.currentUser?.uid)
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        ActiveConversationTracker.setAppInForeground(true)
        FirebaseAuth.getInstance().addAuthStateListener(encryptedInboxAuthListener)
        presenceUid?.let(PresenceRepository::startPresence)
    }

    override fun onStop() {
        isActivityStarted = false
        FirebaseAuth.getInstance().removeAuthStateListener(encryptedInboxAuthListener)
        stopForegroundEncryptedInboxMonitor()
        if (!isChangingConfigurations) {
            PresenceRepository.stopPresence()
        }
        ActiveConversationTracker.setAppInForeground(false)
        super.onStop()
    }

    /**
     * Foreground RTDB events wake the durable encrypted inbox worker immediately.
     * The listener never reads or decrypts payloads and never changes ACK state.
     */
    private fun restartForegroundEncryptedInboxMonitor(ownerUserId: String?) {
        stopForegroundEncryptedInboxMonitor()
        if (!isActivityStarted || ownerUserId.isNullOrBlank()) return

        encryptedInboxSetupJob = lifecycleScope.launch {
            val deviceId = try {
                withContext(Dispatchers.IO) {
                    if (FirebaseAuth.getInstance().currentUser?.uid != ownerUserId) {
                        return@withContext null
                    }
                    DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("InChatInboxMonitor", "Could not resolve the active encrypted inbox device.", failure)
                null
            } ?: return@launch

            if (!isActivityStarted || FirebaseAuth.getInstance().currentUser?.uid != ownerUserId) {
                return@launch
            }

            val reference = FirebaseDatabase.getInstance().reference
                .child("olmInbox")
                .child(ownerUserId)
                .child(deviceId)
            val listener = object : ChildEventListener {
                private fun wakeInbox() {
                    if (isActivityStarted && FirebaseAuth.getInstance().currentUser?.uid == ownerUserId) {
                        EncryptedOlmInboxWorker.wake(
                            context = applicationContext,
                            ownerUserId = ownerUserId,
                            recipientDeviceId = deviceId,
                        )
                    }
                }

                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) = wakeInbox()
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = wakeInbox()
                override fun onChildRemoved(snapshot: DataSnapshot) = Unit
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
                override fun onCancelled(error: DatabaseError) {
                    Log.w("InChatInboxMonitor", "Foreground encrypted inbox listener was cancelled: ${error.code}")
                }
            }

            // Fence account changes both before and after the asynchronous identity lookup.
            if (isActivityStarted && FirebaseAuth.getInstance().currentUser?.uid == ownerUserId) {
                encryptedInboxReference = reference
                encryptedInboxChildListener = listener
                reference.addChildEventListener(listener)
            }
        }
    }

    private fun stopForegroundEncryptedInboxMonitor() {
        encryptedInboxSetupJob?.cancel()
        encryptedInboxSetupJob = null
        val reference = encryptedInboxReference
        val listener = encryptedInboxChildListener
        if (reference != null && listener != null) {
            reference.removeEventListener(listener)
        }
        encryptedInboxReference = null
        encryptedInboxChildListener = null
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

        val recipientUserId =
            intent
                .getStringExtra(
                    InChatFirebaseMessagingService
                        .EXTRA_RECIPIENT_USER_ID
                )
                ?.trim()
                .orEmpty()

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

        val isGroupNotification =
            notificationType ==
                    InChatFirebaseMessagingService
                        .NOTIFICATION_TYPE_GROUP

        val isChatNotification =
            notificationType ==
                    InChatFirebaseMessagingService
                        .NOTIFICATION_TYPE_CHAT

        if (!isGroupNotification && !isChatNotification) {
            return
        }

        if (
            recipientUserId.isBlank() ||
            chatId.isBlank() ||
            (isChatNotification && otherUserId.isBlank())
        ) {
            return
        }

        notificationTarget.value =
            NotificationChatTarget(
                recipientUserId = recipientUserId,
                chatId = chatId,
                otherUserId = otherUserId,
                otherUserNickname =
                    if (otherUserNickname.isNotBlank()) {
                        otherUserNickname
                    } else {
                        "InChat user"
                    },
                isGroup = isGroupNotification
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

        val appContext = LocalContext.current.applicationContext
        val loggedInUid = (uiState as? AuthUiState.LoggedIn)?.uid

        // Device identity publication and encrypted inbox work are app-managed.
        // They must not depend on opening a security settings screen.
        LaunchedEffect(loggedInUid) {
            val uid = loggedInUid ?: return@LaunchedEffect
            try {
                withContext(Dispatchers.IO) {
                    val auth = FirebaseAuth.getInstance()
                    check(auth.currentUser?.uid == uid) {
                        "Signed-in account changed before automatic device setup"
                    }
                    val identity = DeviceIdentityRepository(appContext)
                    // Do not create a brand-new identity here: a clean install
                    // may still choose the optional encrypted identity transfer.
                    val existingDeviceId = identity.existingDeviceIdForDelivery()
                        ?: return@withContext
                    val directory = DeviceKeyDirectoryRepository(auth = auth)
                    val bundle = directory.ensureMyDevicePublished(identity)
                    check(bundle.deviceId == existingDeviceId) {
                        "The restored device identity changed during automatic setup"
                    }
                    check(auth.currentUser?.uid == uid) {
                        "Signed-in account changed during automatic device setup"
                    }
                    EncryptedOlmDeviceWorkScheduler.schedule(
                        context = appContext,
                        ownerUserId = uid,
                        deviceId = bundle.deviceId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The direct/group send coordinators retry provisioning before
                // sending; the next app start retries this background setup.
                Log.w(
                    "InChatDeviceSetup",
                    "Automatic encrypted-device setup could not finish; messaging will retry provisioning.",
                    failure,
                )
            }
        }

        var shouldRequestNotificationPermission by
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                true
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

                    presenceUid = null

                    FirebaseServerClock
                        .stop()

                    PresenceRepository
                        .stopPresence()
                }

                is AuthUiState.LoggedIn -> {

                    presenceUid = state.uid

                    FirebaseServerClock
                        .start()

                    if (isActivityStarted) {
                        PresenceRepository.startPresence(state.uid)
                    }
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