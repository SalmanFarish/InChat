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
import com.example.inchat.data.local.LocalChatDatabase
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
                        transport = FirebaseSasVerificationTransport(localDeviceId = bundle.deviceId),
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

    LaunchedEffect(uid, devices, states) {
        trustedAtByDevice = withContext(Dispatchers.IO) {
            devices.associate { device ->
                device.deviceId to trustedDeviceRepository.verifiedAtMillis(
                    uid, device.deviceId, device.ed25519IdentityKey
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
            } catch (failure: Exception) {
                error = failure.message ?: "Could not load registered devices."
            } finally {
                isRefreshing = false
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
                    val transport = FirebaseSasVerificationTransport(localDeviceId = bundle.deviceId)
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
                localDeviceId = result.first
                coordinator = result.second.first
                devices = result.second.second
                notice = "This device is published. You can now verify another device on this account."
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
            Text("Verify your devices", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Publish this installation's public identity, then compare the same safety numbers on both devices. Private keys stay on each device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isInitializing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                    Text("Restoring this device…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (!initializationReady) {
                Text(
                    "InChat couldn't check whether this device is already registered. Retry before publishing to avoid creating a duplicate entry.",
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
                        Text("Publish this device")
                    }
                }
                Text(
                    "This is an explicit setup step. It publishes only public device keys to the account's device directory.",
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
                    val state = states.values.firstOrNull { it.peerDeviceId == device.deviceId && it.peerUserId == uid }
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
                        trustedAtMillis = trustedAtByDevice[device.deviceId],
                        onRevoke = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        trustedDeviceRepository.revoke(uid, device.deviceId)
                                    }
                                    trustedAtByDevice = trustedAtByDevice + (device.deviceId to null)
                                    notice = "Trust revoked on this device."
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
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not cancel verification."
                                }
                            }
                        }
                    )
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
                            trustedAtByDevice = trustedAtByDevice - device.deviceId
                            notice = "Old device entry removed from this account."
                            error = null
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
                        if (trustedAtMillis != null) "Trusted on this device"
                        else state?.error ?: "Not verified",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (trustedAtMillis != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (trustedAtMillis != null) {
                        OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth()) {
                            Text("Revoke trust")
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
                        if (trustedAtMillis != null) "Verified and trusted on this device"
                        else "Verification completed. Checking the local trust record…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (trustedAtMillis != null) {
                        OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth()) {
                            Text("Revoke trust")
                        }
                    }
                }
            }
        }
    }
}
