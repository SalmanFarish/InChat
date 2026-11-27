package com.example.inchat.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

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

        suspend fun acknowledgeAndRemove(item: FirebaseOlmInboxItem) {
            // The server-side acknowledgement is durable before the sender wake-up.
            // If dispatch fails, leave the inbox copy in place so WorkManager retries
            // this exact acknowledgement instead of waiting for the periodic poll.
            transport.acknowledgeStoredDelivery(deviceId, item)
            onAcknowledgementStored(item)
            transport.removeFromMyDeviceInbox(deviceId, item)
        }

        // Inbox items are independent until the per-session decrypt/ratchet
        // stage. A fixed worker pool keeps the number of suspended jobs bounded
        // even when the relay returns a large mailbox snapshot.
        val outcomes = arrayOfNulls<ItemOutcome>(items.size)
        val nextIndex = AtomicInteger()
        val workerCount = minOf(MAX_CONCURRENT_ITEM_HANDOFFS, items.size)
        coroutineScope {
            List(workerCount) {
                async {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= items.size) break
                        val item = items[index]
                        outcomes[index] = try {
                            val registration = withContext(ioDispatcher) {
                                require(
                                    EncryptedOlmEnvelopeCodec.decode(item.envelope).senderDeviceId ==
                                        item.senderDeviceId,
                                ) {
                                    "Inbox sender device does not match the encrypted envelope"
                                }
                                ledger.register(
                                    senderUserId = item.senderUid,
                                    messageId = item.messageId,
                                    encodedEnvelope = item.envelope,
                                    receivedAtMillis = clock().coerceAtLeast(0L),
                                )
                            }
                            if (registration != OlmInboundRegistration.CONFLICT) {
                                acknowledgeAndRemove(item)
                            }
                            ItemOutcome(registration = registration, failure = null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // One failed item must not prevent the other workers
                            // from durably staging and acknowledging independent items.
                            ItemOutcome(registration = null, failure = failure)
                        }
                    }
                }
            }.awaitAll()
        }

        var stored = 0
        var pending = 0
        var processed = 0
        var conflicts = 0
        var rejected = 0
        var firstFailure: Exception? = null
        for (outcomeOrNull in outcomes) {
            val outcome = checkNotNull(outcomeOrNull) {
                "Inbox worker pool completed without staging every snapshot item"
            }
            when (outcome.registration) {
                OlmInboundRegistration.NEW -> stored++
                OlmInboundRegistration.PENDING_RETRY -> pending++
                OlmInboundRegistration.DUPLICATE_PROCESSED -> processed++
                OlmInboundRegistration.DUPLICATE_REJECTED -> rejected++
                OlmInboundRegistration.CONFLICT -> conflicts++
                null -> if (firstFailure == null) firstFailure = outcome.failure
            }
        }

        // Successfully handled items are already removed and won't needlessly
        // repeat on the next poll. Preserve retry signaling for any failures.
        firstFailure?.let { throw it }
        FirebaseOlmInboxReceiveResult(stored, pending, processed, conflicts, rejected)
    }

    private data class ItemOutcome(
        val registration: OlmInboundRegistration?,
        val failure: Exception?,
    )

    private companion object {
        // Immediate push and periodic inbox workers can overlap. Serialize the
        // remote inbox read/ack/remove transaction to avoid duplicate ACK races.
        val RECEIVE_MUTEX = Mutex()
        const val MAX_CONCURRENT_ITEM_HANDOFFS = 8
    }
}
