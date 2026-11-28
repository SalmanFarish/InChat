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

        // Stage this page in the exact order supplied by the durable per-device
        // mailbox before doing network acknowledgements. Cloudflare assigns each
        // accepted envelope a monotonically increasing sequence; preserving it
        // here ensures a Megolm key envelope is committed before dependent group
        // ciphertext. ACK/removal remains parallel after all local receipts exist.
        val outcomes = arrayOfNulls<ItemOutcome>(items.size)
        val stagedIndexes = ArrayList<Int>(items.size)
        var nextReceivedAtMillis = withContext(ioDispatcher) {
            ledger.nextReceivedAtMillis(clock().coerceAtLeast(0L))
        }
        for (index in items.indices) {
            val item = items[index]
            try {
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
                        receivedAtMillis = nextReceivedAtMillis,
                    )
                }
                outcomes[index] = ItemOutcome(registration = registration, failure = null)
                if (registration != OlmInboundRegistration.CONFLICT) {
                    stagedIndexes += index
                }
                nextReceivedAtMillis = Math.addExact(nextReceivedAtMillis, 1L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Do not stage any later mailbox sequence after a local durable
                // registration failure. Those remote rows remain for the next pass.
                outcomes[index] = ItemOutcome(registration = null, failure = failure)
                break
            }
        }

        suspend fun acknowledgeStagedItem(index: Int) {
            val item = items[index]
            try {
                acknowledgeAndRemove(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                outcomes[index] = ItemOutcome(
                    registration = outcomes[index]?.registration,
                    failure = failure,
                )
            }
        }

        // Only local Room registration is ordered. Network ACK/removal remains
        // concurrent and bounded so a slow relay operation cannot hold up
        // unrelated, already-durable envelopes in this page.
        val nextIndex = AtomicInteger()
        val workerCount = minOf(MAX_CONCURRENT_ITEM_HANDOFFS, stagedIndexes.size)
        coroutineScope {
            List(workerCount) {
                async {
                    while (true) {
                        val offset = nextIndex.getAndIncrement()
                        if (offset >= stagedIndexes.size) break
                        acknowledgeStagedItem(stagedIndexes[offset])
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
            // Null rows are later mailbox sequences deliberately left remote
            // after an earlier local registration failure.
            val outcome = outcomeOrNull ?: continue
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
