package com.example.inchat.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class FirebaseOlmInboxReceiveResult(
    val stored: Int,
    val alreadyPending: Int,
    val alreadyProcessed: Int,
    val conflicts: Int,
    val rejected: Int,
)

/**
 * Copies ciphertext into the durable local receipt ledger before deleting the
 * remote copy. Decryption and live-chat persistence remain separate steps.
 */
internal class FirebaseOlmInboxReceiver(
    private val transport: OlmInboxTransport,
    private val ledger: OlmInboundDeliveryLedger,
    private val deviceId: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onAcknowledgementStored: suspend (FirebaseOlmInboxItem) -> Unit = {},
) {
    suspend fun receivePending(): FirebaseOlmInboxReceiveResult = RECEIVE_MUTEX.withLock {
        val items = transport.readMyDeviceInbox(deviceId)
        var stored = 0
        var pending = 0
        var processed = 0
        var conflicts = 0
        var rejected = 0
        var firstFailure: Exception? = null

        suspend fun acknowledgeAndRemove(item: FirebaseOlmInboxItem) {
            // The server-side acknowledgement is durable before the sender wake-up.
            // If dispatch fails, leave the inbox copy in place so WorkManager retries
            // this exact acknowledgement instead of waiting for the periodic poll.
            transport.acknowledgeStoredDelivery(deviceId, item)
            onAcknowledgementStored(item)
            transport.removeFromMyDeviceInbox(deviceId, item)
        }

        // A transient failure for one relay item must not prevent later independent
        // ciphertext receipts from being durably staged in this same bounded pass.
        // Preserve retry signaling by rethrowing after all remaining items are tried.
        for (item in items) {
            try {
                val registration = withContext(ioDispatcher) {
                    require(EncryptedOlmEnvelopeCodec.decode(item.envelope).senderDeviceId == item.senderDeviceId) {
                        "Inbox sender device does not match the encrypted envelope"
                    }
                    ledger.register(
                        senderUserId = item.senderUid,
                        messageId = item.messageId,
                        encodedEnvelope = item.envelope,
                        receivedAtMillis = clock().coerceAtLeast(0L),
                    )
                }
                when (registration) {
                    OlmInboundRegistration.CONFLICT -> conflicts++
                    OlmInboundRegistration.NEW -> {
                        acknowledgeAndRemove(item)
                        stored++
                    }
                    OlmInboundRegistration.PENDING_RETRY -> {
                        acknowledgeAndRemove(item)
                        pending++
                    }
                    OlmInboundRegistration.DUPLICATE_PROCESSED -> {
                        acknowledgeAndRemove(item)
                        processed++
                    }
                    OlmInboundRegistration.DUPLICATE_REJECTED -> {
                        acknowledgeAndRemove(item)
                        rejected++
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure
            }
        }

        // WorkManager still retries the pass, while successfully handled later
        // items are already removed and won't needlessly repeat on the next poll.
        firstFailure?.let { throw it }
        FirebaseOlmInboxReceiveResult(stored, pending, processed, conflicts, rejected)
    }

    private companion object {
        // Immediate push and periodic inbox workers can overlap. Serialize the
        // remote inbox read/ack/remove transaction to avoid duplicate ACK races.
        val RECEIVE_MUTEX = Mutex()
    }
}
