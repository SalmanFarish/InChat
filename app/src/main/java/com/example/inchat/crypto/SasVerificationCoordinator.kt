package com.example.inchat.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

internal enum class SasVerificationPhase {
    WAITING_FOR_PEER,
    COMPARING,
    CONFIRMING,
    COMPLETE,
    CANCELLED,
    EXPIRED,
    FAILED,
}

internal data class SasVerificationUiState(
    val transactionId: String,
    val peerUserId: String,
    val peerDeviceId: String,
    val phase: SasVerificationPhase,
    val emojiIndices: List<Int> = emptyList(),
    val decimalCodes: List<Int> = emptyList(),
    val error: String? = null,
)

/**
 * Orchestrates the isolated two-device SAS protocol. Successful verification
 * writes only a device-local, Keystore-encrypted trust record; it is not
 * connected to normal chat delivery.
 *
 * The native handle owns the ephemeral DH secret. If Android kills the process,
 * the attempt cannot be resumed; the user must start a fresh verification.
 */
internal class SasVerificationCoordinator(
    private val localUserId: String,
    private val identityRepository: DeviceIdentityRepository,
    private val directoryRepository: DeviceKeyDirectoryRepository,
    private val transport: SasVerificationTransport,
    ledger: SasVerificationMessageLedger,
    private val trustedDeviceRepository: TrustedDeviceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Attempt(
        val transactionId: String,
        val handle: String,
        val peerUserId: String,
        val peerDeviceId: String,
        val expiresAtMillis: Long,
        val localIsInitiator: Boolean,
        val peerEd25519IdentityKey: String,
        val received: MutableMap<SasVerificationMessageType, String> = mutableMapOf(),
        var pendingPeerMac: String? = null,
        var localConfirmed: Boolean = false,
    )

    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, Attempt>()
    private val mutableStates = MutableStateFlow<Map<String, SasVerificationUiState>>(emptyMap())
    val states: StateFlow<Map<String, SasVerificationUiState>> = mutableStates.asStateFlow()

    private val localBundle: PublicDeviceKeyBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        identityRepository.ensurePublicKeyBundle()
    }
    private val localDeviceId: String
        get() = localBundle.deviceId
    private val receiver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SasVerificationInboxReceiver(
            transport = transport,
            ledger = ledger,
            localUserId = localUserId,
            localDeviceId = localDeviceId,
            clock = clock,
        )
    }

    init {
        require(localUserId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "Local user ID has an invalid format"
        }
    }

    /** Start a new attempt from this device. The remote bundle is signature-verified by the directory. */
    suspend fun startVerification(
        peerUserId: String,
        peerDeviceId: String,
    ): SasVerificationUiState = mutex.withLock {
        check(directoryRepository.authenticatedUserId == localUserId) {
            "The signed-in account does not match this local identity"
        }
        require(peerUserId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(peerDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(peerUserId != localUserId || peerDeviceId != localDeviceId) {
            "A device cannot verify itself"
        }
        val existing = attempts.values.firstOrNull {
            it.peerUserId == peerUserId && it.peerDeviceId == peerDeviceId
        }
        check(existing == null) { "A verification with this device is already active" }

        val remote = directoryRepository.getUserDevices(peerUserId)
            .firstOrNull { it.deviceId == peerDeviceId }
            ?: error("The selected device is no longer published")
        val transactionId = UUID.randomUUID().toString()
        val now = clock().coerceAtLeast(0L)
        val expiresAt = now + SasVerificationMessageCodec.MAX_LIFETIME_MILLIS
        val created = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeSasCreate(
                    localUserId, localDeviceId, localBundle.ed25519IdentityKey,
                    peerUserId, peerDeviceId, remote.ed25519IdentityKey,
                    transactionId, true,
                )
            ) { "Native SAS session could not be created" }
        )
        val attempt = Attempt(
            transactionId, created.getString("handle"), peerUserId, peerDeviceId,
            expiresAt, true, remote.ed25519IdentityKey,
        )
        attempts[transactionId] = attempt
        commitments[transactionId] = created.getString("commitment")
        publishState(attempt, SasVerificationPhase.WAITING_FOR_PEER)
        try {
            send(attempt, SasVerificationMessageType.COMMITMENT, created.getString("commitment"))
        } catch (failure: Throwable) {
            discard(attempt, SasVerificationPhase.FAILED, failure.message ?: "Could not send verification request")
            throw failure
        }
        stateFor(attempt)
    }

    /** Poll the dedicated inbox. Each delivery is acknowledged only after this coordinator handles it. */
    suspend fun receivePending(): SasVerificationInboxReceiveResult = withContext(Dispatchers.IO) {
        receiver.receivePending { message -> handleIncoming(message) }
    }

    /** Call only after the user has visually compared both devices and selected “They match”. */
    suspend fun confirmSasMatch(transactionId: String) = mutex.withLock {
        val attempt = requireAttempt(transactionId)
        ensureNotExpired(attempt)
        check(mutableStates.value[transactionId]?.phase == SasVerificationPhase.CONFIRMING) {
            "The SAS comparison is not ready"
        }
        if (!attempt.localConfirmed) {
            check(NativeCryptoBridge.nativeSasConfirmMatch(attempt.handle, true)) {
                "Native SAS confirmation was rejected"
            }
            attempt.localConfirmed = true
        }
        val localMac = requireNotNull(NativeCryptoBridge.nativeSasCreateLocalMac(attempt.handle)) {
            "Native SAS identity authentication could not be created"
        }
        send(attempt, SasVerificationMessageType.SAS_MAC, localMac)
        attempt.pendingPeerMac?.let { verifyPeerMac(attempt, it) }
        updateCompletion(attempt)
    }

    /** Cancel locally and notify the peer when the relay is still available. */
    suspend fun cancel(transactionId: String) = mutex.withLock {
        val attempt = attempts[transactionId] ?: return@withLock
        runCatching { send(attempt, SasVerificationMessageType.CANCEL, "") }
        discard(attempt, SasVerificationPhase.CANCELLED, null)
    }

    /** Expire attempts on screen resume or before polling; no background worker is required. */
    suspend fun expireStaleAttempts() = mutex.withLock {
        val now = clock().coerceAtLeast(0L)
        attempts.values.toList().filter { it.expiresAtMillis <= now }.forEach {
            discard(it, SasVerificationPhase.EXPIRED, "Verification expired. Start a new attempt.")
        }
    }

    private suspend fun handleIncoming(message: SasVerificationMessage) = mutex.withLock {
        check(message.recipientUserId == localUserId && message.recipientDeviceId == localDeviceId)
        check(directoryRepository.authenticatedUserId == localUserId)
        var attempt = attempts[message.transactionId]

        if (message.type == SasVerificationMessageType.CANCEL) {
            if (attempt != null) discard(attempt, SasVerificationPhase.CANCELLED, "The other device cancelled verification.")
            return@withLock
        }

        if (attempt == null) {
            check(message.type == SasVerificationMessageType.COMMITMENT) {
                "No active verification exists for this transaction"
            }
            val competingAttempt = attempts.values.firstOrNull {
                it.peerUserId == message.senderUserId &&
                    it.peerDeviceId == message.senderDeviceId
            }
            if (competingAttempt != null) {
                // Both devices may start at nearly the same time. Keep the first
                // transaction and cancel the competing incoming one so its sender
                // does not remain stuck waiting for a response that will never come.
                rejectCompetingAttempt(message)
                return@withLock
            }
            attempt = createResponderAttempt(message)
        }

        check(message.senderUserId == attempt.peerUserId && message.senderDeviceId == attempt.peerDeviceId) {
            "Verification message came from a different device"
        }
        ensureNotExpired(attempt)

        val previous = attempt.received[message.type]
        if (previous != null) {
            check(previous == message.payload) { "Conflicting duplicate SAS message" }
        }

        when (message.type) {
            SasVerificationMessageType.COMMITMENT -> {
                if (previous == null) {
                    check(NativeCryptoBridge.nativeSasAcceptPeerCommitment(attempt.handle, message.payload)) {
                        "Peer SAS commitment was rejected"
                    }
                    attempt.received[message.type] = message.payload
                }
                val publicKey = requireNotNull(NativeCryptoBridge.nativeSasRevealPublicKey(attempt.handle)) {
                    "Native SAS public key could not be revealed"
                }
                if (!attempt.localIsInitiator) {
                    // The responder must publish its commitment before its public key.
                    send(attempt, SasVerificationMessageType.COMMITMENT, attemptCommitment(attempt))
                }
                send(attempt, SasVerificationMessageType.PUBLIC_KEY, publicKey)
            }
            SasVerificationMessageType.PUBLIC_KEY -> {
                if (previous == null) {
                    check(NativeCryptoBridge.nativeSasAcceptPeerPublicKey(attempt.handle, message.payload)) {
                        "Peer SAS public key did not match its commitment"
                    }
                    attempt.received[message.type] = message.payload
                }
                if (mutableStates.value[attempt.transactionId]?.phase == SasVerificationPhase.CONFIRMING) {
                    return@withLock
                }
                val codes = JSONObject(
                    requireNotNull(NativeCryptoBridge.nativeSasDisplayCodes(attempt.handle)) {
                        "SAS comparison codes are unavailable"
                    }
                )
                val emojis = codes.getJSONArray("emojiIndices")
                val decimals = codes.getJSONArray("decimals")
                mutableStates.value = mutableStates.value + (attempt.transactionId to
                    stateFor(attempt).copy(
                        phase = SasVerificationPhase.CONFIRMING,
                        emojiIndices = (0 until emojis.length()).map { emojis.getInt(it) },
                        decimalCodes = (0 until decimals.length()).map { decimals.getInt(it) },
                    ))
            }
            SasVerificationMessageType.SAS_MAC -> {
                if (previous != null) return@withLock
                attempt.received[message.type] = message.payload
                if (attempt.localConfirmed) {
                    verifyPeerMac(attempt, message.payload)
                    updateCompletion(attempt)
                } else {
                    attempt.pendingPeerMac = message.payload
                }
            }
            SasVerificationMessageType.CANCEL -> Unit
        }
    }

    private suspend fun rejectCompetingAttempt(message: SasVerificationMessage) {
        val now = clock().coerceAtLeast(0L)
        if (now >= message.expiresAtMillis) return
        transport.upload(
            SasVerificationMessage(
                transactionId = message.transactionId,
                senderUserId = localUserId,
                senderDeviceId = localDeviceId,
                recipientUserId = message.senderUserId,
                recipientDeviceId = message.senderDeviceId,
                type = SasVerificationMessageType.CANCEL,
                payload = "",
                createdAtMillis = now,
                expiresAtMillis = message.expiresAtMillis,
            )
        )
    }

    private suspend fun createResponderAttempt(message: SasVerificationMessage): Attempt {
        val peer = directoryRepository.getUserDevices(message.senderUserId)
            .firstOrNull { it.deviceId == message.senderDeviceId }
            ?: error("The requesting device is not in the verified device directory")
        val local = localBundle
        val created = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeSasCreate(
                    message.senderUserId, message.senderDeviceId, peer.ed25519IdentityKey,
                    localUserId, local.deviceId, local.ed25519IdentityKey,
                    message.transactionId, false,
                )
            ) { "Native SAS responder session could not be created" }
        )
        val attempt = Attempt(
            message.transactionId,
            created.getString("handle"),
            message.senderUserId,
            message.senderDeviceId,
            message.expiresAtMillis,
            false,
            peer.ed25519IdentityKey,
        )
        attempts[message.transactionId] = attempt
        commitments[message.transactionId] = created.getString("commitment")
        publishState(attempt, SasVerificationPhase.WAITING_FOR_PEER)
        return attempt
    }

    private suspend fun send(
        attempt: Attempt,
        type: SasVerificationMessageType,
        payload: String,
    ) {
        val now = clock().coerceAtLeast(0L)
        check(now < attempt.expiresAtMillis) { "Verification expired" }
        transport.upload(
            SasVerificationMessage(
                transactionId = attempt.transactionId,
                senderUserId = localUserId,
                senderDeviceId = localDeviceId,
                recipientUserId = attempt.peerUserId,
                recipientDeviceId = attempt.peerDeviceId,
                type = type,
                payload = payload,
                createdAtMillis = now,
                expiresAtMillis = attempt.expiresAtMillis,
            )
        )
    }

    private fun attemptCommitment(attempt: Attempt): String {
        // The native API intentionally returns the commitment only at creation.
        return requireNotNull(commitments[attempt.transactionId]) {
            "Local SAS commitment is unavailable"
        }
    }

    private val commitments = mutableMapOf<String, String>()

    private fun verifyPeerMac(attempt: Attempt, mac: String) {
        check(NativeCryptoBridge.nativeSasVerifyPeerMac(attempt.handle, mac)) {
            "The other device could not authenticate its identity key"
        }
        attempt.pendingPeerMac = null
    }

    private suspend fun updateCompletion(attempt: Attempt) {
        if (NativeCryptoBridge.nativeSasIsComplete(attempt.handle)) {
            trustedDeviceRepository.markVerified(
                peerUserId = attempt.peerUserId,
                peerDeviceId = attempt.peerDeviceId,
                peerEd25519IdentityKey = attempt.peerEd25519IdentityKey,
                verifiedAtMillis = clock().coerceAtLeast(0L),
            )
            mutableStates.value = mutableStates.value + (attempt.transactionId to
                stateFor(attempt).copy(phase = SasVerificationPhase.COMPLETE))
        }
    }

    private fun ensureNotExpired(attempt: Attempt) {
        if (clock().coerceAtLeast(0L) >= attempt.expiresAtMillis) {
            discard(attempt, SasVerificationPhase.EXPIRED, "Verification expired. Start a new attempt.")
            error("Verification expired")
        }
    }

    private fun requireAttempt(transactionId: String): Attempt =
        requireNotNull(attempts[transactionId]) { "Verification attempt is no longer active" }

    private fun stateFor(attempt: Attempt) = SasVerificationUiState(
        transactionId = attempt.transactionId,
        peerUserId = attempt.peerUserId,
        peerDeviceId = attempt.peerDeviceId,
        phase = mutableStates.value[attempt.transactionId]?.phase
            ?: SasVerificationPhase.WAITING_FOR_PEER,
    )

    private fun publishState(attempt: Attempt, phase: SasVerificationPhase) {
        mutableStates.value = mutableStates.value +
            (attempt.transactionId to stateFor(attempt).copy(phase = phase))
    }

    private fun discard(attempt: Attempt, phase: SasVerificationPhase, error: String?) {
        NativeCryptoBridge.nativeSasCancel(attempt.handle)
        attempts.remove(attempt.transactionId)
        commitments.remove(attempt.transactionId)
        mutableStates.value = mutableStates.value +
            (attempt.transactionId to stateFor(attempt).copy(phase = phase, error = error))
    }
}
