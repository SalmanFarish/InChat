package com.example.inchat.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.text.format.DateUtils
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.worker.EncryptedOlmDeviceWorkScheduler
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.DeviceKeyDirectoryRepository
import com.example.inchat.crypto.FirebaseSasVerificationTransport
import com.example.inchat.crypto.PublicDeviceKeyBundle
import com.example.inchat.crypto.SasVerificationCoordinator
import com.example.inchat.crypto.SasVerificationPhase
import com.example.inchat.crypto.SasVerificationUiState
import com.example.inchat.crypto.SasVerificationMessageLedger
import com.example.inchat.crypto.TrustedDeviceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceVerificationScreen(uid: String, onBackClick: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val identityRepository = remember(context) { DeviceIdentityRepository(context) }
    val directoryRepository = remember { DeviceKeyDirectoryRepository() }
    val trustedDeviceRepository = remember(context) { TrustedDeviceRepository(context) }
    var trustedAtByDevice by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var localDeviceId by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<PublicDeviceKeyBundle>>(emptyList()) }
    var peerUsernameQuery by remember { mutableStateOf("") }
    var peerAccountUserId by remember { mutableStateOf<String?>(null) }
    var peerAccountUsername by remember { mutableStateOf<String?>(null) }
    var peerDevices by remember { mutableStateOf<List<PublicDeviceKeyBundle>>(emptyList()) }
    var isLoadingPeerDevices by remember { mutableStateOf(false) }
    var coordinator by remember { mutableStateOf<SasVerificationCoordinator?>(null) }
    var states by remember { mutableStateOf<Map<String, SasVerificationUiState>>(emptyMap()) }
    var isPublishing by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(true) }
    var initializationReady by remember { mutableStateOf(false) }
    var initializationAttempt by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var devicePendingRemoval by remember { mutableStateOf<PublicDeviceKeyBundle?>(null) }

    // Restore this installation's existing identity before offering first-time publication.
    LaunchedEffect(uid, initializationAttempt) {
        isInitializing = true
        initializationReady = false
        localDeviceId = null
        coordinator = null
        devices = emptyList()
        peerAccountUserId = null
        peerAccountUsername = null
        peerDevices = emptyList()
        error = null
        try {
            val restored = withContext(Dispatchers.IO) {
                check(directoryRepository.authenticatedUserId == uid) {
                    "The signed-in account changed. Sign in again."
                }
                val bundle = identityRepository.ensurePublicKeyBundle()
                val registeredDevices = directoryRepository.getUserDevices(uid)
                val isAlreadyPublished = registeredDevices.any { it.deviceId == bundle.deviceId }
                val restoredCoordinator = if (isAlreadyPublished) {
                    directoryRepository.touchMyDevice(bundle.deviceId)
                    val ledger = SasVerificationMessageLedger(
                        dao = LocalChatDatabase.getInstance(context).sasVerificationMessageDao(),
                        localUserId = uid,
                        localDeviceId = bundle.deviceId
                    )
                    SasVerificationCoordinator(
                        localUserId = uid,
                        identityRepository = identityRepository,
                        directoryRepository = directoryRepository,
                        transport = FirebaseSasVerificationTransport(localUserId = uid, localDeviceId = bundle.deviceId),
                        ledger = ledger,
                        trustedDeviceRepository = trustedDeviceRepository,
                    )
                } else {
                    null
                }
                Triple(bundle.deviceId, registeredDevices, restoredCoordinator)
            }
            devices = restored.second
            if (restored.third != null) {
                if (directoryRepository.authenticatedUserId == uid) {
                    runCatching {
                        EncryptedOlmDeviceWorkScheduler.schedule(
                            context = context,
                            ownerUserId = uid,
                            deviceId = restored.first,
                        )
                    }.onFailure { failure ->
                        Log.e(
                            "DeviceVerification",
                            "Could not restore encrypted delivery and device push jobs.",
                            failure,
                        )
                    }
                }
                localDeviceId = restored.first
                coordinator = restored.third
                notice = "This device was restored automatically."
            }
            initializationReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not restore this device."
        } finally {
            isInitializing = false
        }
    }

    LaunchedEffect(localDeviceId, uid) {
        val deviceId = localDeviceId ?: return@LaunchedEffect
        while (isActive) {
            try {
                withContext(Dispatchers.IO) { directoryRepository.touchMyDevice(deviceId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not update this device's activity."
            }
            delay(5 * 60 * 1000L)
        }
    }

    LaunchedEffect(uid, devices, peerAccountUserId, peerDevices, states) {
        trustedAtByDevice = withContext(Dispatchers.IO) {
            buildList {
                devices.forEach { add(uid to it) }
                val peerUid = peerAccountUserId
                if (peerUid != null) {
                    peerDevices.forEach { add(peerUid to it) }
                }
            }.associate { (ownerUserId, device) ->
                deviceTrustMapKey(ownerUserId, device.deviceId) to
                    trustedDeviceRepository.verifiedAtMillis(
                        ownerUserId,
                        device.deviceId,
                        device.ed25519IdentityKey,
                    )
            }
        }
    }

    val activeCoordinator = coordinator
    LaunchedEffect(activeCoordinator) {
        val current = activeCoordinator ?: return@LaunchedEffect
        val stateJob = launch { current.states.collect { states = it } }
        try {
            while (isActive) {
                try {
                    withContext(Dispatchers.IO) {
                        current.expireStaleAttempts()
                        current.receivePending()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Could not receive verification messages."
                }
                delay(1_500)
            }
        } finally {
            stateJob.cancel()
        }
    }

    fun refreshDevices() {
        scope.launch {
            isRefreshing = true
            error = null
            try {
                devices = withContext(Dispatchers.IO) { directoryRepository.getUserDevices(uid) }
                notice = "Device list updated."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not load registered devices."
            } finally {
                isRefreshing = false
            }
        }
    }

    fun loadPeerDevices() {
        val username = peerUsernameQuery.trim().removePrefix("@")
        if (username.isBlank()) {
            error = "Enter an InChat username."
            return
        }
        peerAccountUserId = null
        peerAccountUsername = null
        peerDevices = emptyList()
        scope.launch {
            isLoadingPeerDevices = true
            error = null
            notice = null
            try {
                val result = withContext(Dispatchers.IO) {
                    check(directoryRepository.authenticatedUserId == uid) {
                        "The signed-in account changed. Sign in again."
                    }
                    val user = UserRepository().getUserByUsername(username)
                    check(user != null && user.discoverableByUsername) {
                        "No discoverable InChat account was found for that username."
                    }
                    require(user.uid != uid) {
                        "Use the Other devices section to verify another phone on your own account."
                    }
                    user to directoryRepository.getUserDevices(user.uid)
                }
                peerAccountUserId = result.first.uid
                peerAccountUsername = result.first.username
                peerDevices = result.second
                notice = if (result.second.isEmpty()) {
                    "That account has no published devices yet."
                } else {
                    "Loaded the published devices for @${result.first.username}."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                peerAccountUserId = null
                peerAccountUsername = null
                peerDevices = emptyList()
                error = failure.message ?: "Could not load that account's devices."
            } finally {
                isLoadingPeerDevices = false
            }
        }
    }

    fun publishThisDevice() {
        scope.launch {
            isPublishing = true
            error = null
            notice = null
            try {
                val result = withContext(Dispatchers.IO) {
                    check(directoryRepository.authenticatedUserId == uid) {
                        "The signed-in account changed. Sign in again."
                    }
                    val bundle = identityRepository.ensurePublicKeyBundle()
                    directoryRepository.publishMyDevice(bundle)
                    identityRepository.markPublicKeyBundlePublished()
                    val ledger = SasVerificationMessageLedger(
                        dao = LocalChatDatabase.getInstance(context).sasVerificationMessageDao(),
                        localUserId = uid,
                        localDeviceId = bundle.deviceId
                    )
                    val transport = FirebaseSasVerificationTransport(localUserId = uid, localDeviceId = bundle.deviceId)
                    val newCoordinator = SasVerificationCoordinator(
                        localUserId = uid,
                        identityRepository = identityRepository,
                        directoryRepository = directoryRepository,
                        transport = transport,
                        ledger = ledger,
                        trustedDeviceRepository = trustedDeviceRepository,
                    )
                    val registeredDevices = directoryRepository.getUserDevices(uid)
                    bundle.deviceId to (newCoordinator to registeredDevices)
                }
                if (directoryRepository.authenticatedUserId == uid) {
                    runCatching {
                        EncryptedOlmDeviceWorkScheduler.schedule(
                            context = context,
                            ownerUserId = uid,
                            deviceId = result.first,
                        )
                    }.onFailure { failure ->
                        Log.e(
                            "DeviceVerification",
                            "Device was published, but encrypted delivery/push jobs could not be scheduled.",
                            failure,
                        )
                    }
                }
                localDeviceId = result.first
                coordinator = result.second.first
                devices = result.second.second
                notice = "This device is published. You can now verify another device on this account."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not publish this device."
            } finally {
                isPublishing = false
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Device verification", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Your devices and safety checks", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Secure messaging is set up automatically. Comparing safety numbers is optional and adds extra confidence that a device belongs to the person you expect. Use this screen to review devices, remove old ones, or complete that extra check. Private keys stay on their respective phones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isInitializing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Text("Restoring this device…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (!initializationReady) {
                Text(
                    "InChat couldn't check this device list. Retry the check; ordinary messaging will attempt secure setup automatically when needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { initializationAttempt += 1 },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Retry") }
            } else if (localDeviceId == null) {
                Button(onClick = ::publishThisDevice, enabled = !isPublishing, modifier = Modifier.fillMaxWidth()) {
                    if (isPublishing) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retry secure setup")
                    }
                }
                Text(
                    "InChat normally registers this phone automatically. Retry here only if setup could not finish; this shares public keys and does not verify another device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("THIS DEVICE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(5.dp))
                        Text(localDeviceId.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Other devices", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = ::refreshDevices, enabled = !isRefreshing) {
                        Text(if (isRefreshing) "Refreshing…" else "Refresh")
                    }
                }
                val otherDevices = devices.filter { it.deviceId != localDeviceId }
                if (otherDevices.isEmpty()) {
                    Text(
                        "No other devices are published to this account yet. Publish this account on another installation of InChat to verify it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                otherDevices.forEach { device ->
                    val state = latestDeviceVerificationState(
                        states = states,
                        peerUserId = uid,
                        peerDeviceId = device.deviceId,
                    )
                    val lastSeen = device.lastSeenAtMillis
                    val isLegacyEntry = lastSeen == null
                    DeviceVerificationAttemptCard(
                        device = device,
                        state = state,
                        activityLabel = if (isLegacyEntry) {
                            "Activity not recorded yet (older device entry)"
                        } else {
                            "Last seen in verification " + DateUtils.getRelativeTimeSpanString(
                                requireNotNull(lastSeen), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                        },
                        canRemove = isLegacyEntry,
                        onRemove = { devicePendingRemoval = device },
                        trustedAtMillis = trustedAtByDevice[deviceTrustMapKey(uid, device.deviceId)],
                        trustStatusLoaded = trustedAtByDevice.containsKey(deviceTrustMapKey(uid, device.deviceId)),
                        onRevoke = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        trustedDeviceRepository.revoke(uid, device.deviceId)
                                    }
                                    trustedAtByDevice = trustedAtByDevice +
                                        (deviceTrustMapKey(uid, device.deviceId) to null)
                                    notice = "Trust revoked on this device."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not revoke device trust."
                                }
                            }
                        },
                        onStart = {
                            scope.launch {
                                error = null
                                try {
                                    withContext(Dispatchers.IO) {
                                        requireNotNull(coordinator).startVerification(uid, device.deviceId)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not start verification."
                                }
                            }
                        },
                        onConfirm = {
                            scope.launch {
                                error = null
                                try {
                                    withContext(Dispatchers.IO) {
                                        requireNotNull(coordinator).confirmSasMatch(requireNotNull(state).transactionId)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not confirm verification."
                                }
                            }
                        },
                        onCancel = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        coordinator?.cancel(requireNotNull(state).transactionId)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not cancel verification."
                                }
                            }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Verify another InChat account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Enter the exact username of the person you want to verify. Only accounts discoverable by username can be loaded here. Compare the safety numbers in person or through another trusted channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = peerUsernameQuery,
                    onValueChange = {
                        peerUsernameQuery = it
                        peerAccountUserId = null
                        peerAccountUsername = null
                        peerDevices = emptyList()
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoadingPeerDevices,
                    label = { Text("InChat username") },
                    placeholder = { Text("@username") },
                )
                Button(
                    onClick = ::loadPeerDevices,
                    enabled = !isLoadingPeerDevices && peerUsernameQuery.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoadingPeerDevices) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (peerAccountUserId == null) "Find published devices" else "Refresh account devices")
                    }
                }
                val targetUserId = peerAccountUserId
                if (targetUserId != null) {
                    Text(
                        "Devices for @${peerAccountUsername.orEmpty()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (peerDevices.isEmpty()) {
                        Text(
                            "No devices have been published by this account yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    peerDevices.forEach { device ->
                        val state = latestDeviceVerificationState(
                            states = states,
                            peerUserId = targetUserId,
                            peerDeviceId = device.deviceId,
                        )
                        val trustKey = deviceTrustMapKey(targetUserId, device.deviceId)
                        val lastSeen = device.lastSeenAtMillis
                        DeviceVerificationAttemptCard(
                            device = device,
                            state = state,
                            activityLabel = if (lastSeen == null) {
                                "Activity not recorded yet"
                            } else {
                                "Last seen in verification " + DateUtils.getRelativeTimeSpanString(
                                    lastSeen,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString()
                            },
                            canRemove = false,
                            onRemove = {},
                            trustedAtMillis = trustedAtByDevice[trustKey],
                            trustStatusLoaded = trustedAtByDevice.containsKey(trustKey),
                            onRevoke = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            trustedDeviceRepository.revoke(targetUserId, device.deviceId)
                                        }
                                        trustedAtByDevice = trustedAtByDevice + (trustKey to null)
                                        notice = "Trust for @${peerAccountUsername.orEmpty()} was revoked on this phone."
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Could not revoke device trust."
                                    }
                                }
                            },
                            onStart = {
                                scope.launch {
                                    error = null
                                    try {
                                        withContext(Dispatchers.IO) {
                                            requireNotNull(coordinator).startVerification(targetUserId, device.deviceId)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Could not start verification."
                                    }
                                }
                            },
                            onConfirm = {
                                scope.launch {
                                    error = null
                                    try {
                                        withContext(Dispatchers.IO) {
                                            requireNotNull(coordinator).confirmSasMatch(requireNotNull(state).transactionId)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Could not confirm verification."
                                    }
                                }
                            },
                            onCancel = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            coordinator?.cancel(requireNotNull(state).transactionId)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Could not cancel verification."
                                    }
                                }
                            },
                        )
                    }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }

    devicePendingRemoval?.let { device ->
        AlertDialog(
            onDismissRequest = { devicePendingRemoval = null },
            title = { Text("Remove device entry?") },
            text = {
                Text(
                    "This removes this device's public keys from your account directory. " +
                        "Only remove it if you recognise it as an old installation. " +
                        "A currently used phone must keep its entry."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    devicePendingRemoval = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                directoryRepository.removeMyDevice(device.deviceId)
                                trustedDeviceRepository.revoke(uid, device.deviceId)
                            }
                            devices = devices.filterNot { it.deviceId == device.deviceId }
                            trustedAtByDevice = trustedAtByDevice - deviceTrustMapKey(uid, device.deviceId)
                            notice = "Old device entry removed from this account."
                            error = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Could not remove the device entry."
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { devicePendingRemoval = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DeviceVerificationAttemptCard(
    device: PublicDeviceKeyBundle,
    state: SasVerificationUiState?,
    activityLabel: String,
    canRemove: Boolean,
    onRemove: () -> Unit,
    trustedAtMillis: Long?,
    trustStatusLoaded: Boolean,
    onRevoke: () -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "Device " + device.deviceId.take(12),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(activityLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (canRemove) {
                OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove legacy device entry")
                }
            }
            when (state?.phase) {
                null, SasVerificationPhase.CANCELLED, SasVerificationPhase.EXPIRED, SasVerificationPhase.FAILED -> {
                    Text(
                        if (trustedAtMillis != null) "This phone trusts this device"
                        else state?.error ?: "Not verified",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (trustedAtMillis != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (trustedAtMillis != null) {
                        OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth()) {
                            Text("Remove trust on this phone")
                        }
                    } else {
                        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Verify this device") }
                    }
                }
                SasVerificationPhase.WAITING_FOR_PEER -> {
                    Text("Waiting for the other device to respond…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                SasVerificationPhase.COMPARING, SasVerificationPhase.CONFIRMING -> {
                    Text(
                        "Compare these three numbers on both devices. Continue only if all three match exactly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(state.decimalCodes.joinToString("     "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("They match") }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("They do not match") }
                }
                SasVerificationPhase.COMPLETE -> {
                    Text(
                        if (trustedAtMillis != null) "Verified; this phone trusts this device"
                        else "Verification completed. Checking whether this phone saved trust…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (trustedAtMillis != null) {
                        OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth()) {
                            Text("Remove trust on this phone")
                        }
                    } else {
                        Text(
                            if (trustStatusLoaded) {
                                "This device is not currently trusted on this phone."
                            } else {
                                "Checking this phone's saved trust decision…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (shouldOfferDeviceReverification(state, trustedAtMillis, trustStatusLoaded)) {
                            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                                Text("Verify this device again")
                            }
                        }
                    }
                }
            }
        }
    }
}
