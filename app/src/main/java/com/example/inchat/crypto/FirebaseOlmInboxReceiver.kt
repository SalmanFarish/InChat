package com.example.inchat.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class FirebaseOlmInboxReceiveResult(
    val stored: Int,
    val alreadyPending: Int,
    val alreadyProcessed: Int,
    val conflicts: Int,
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
) {
    suspend fun receivePending(): FirebaseOlmInboxReceiveResult {
        val items = transport.readMyDeviceInbox(deviceId)
        var stored = 0
        var pending = 0
        var processed = 0
        var conflicts = 0

        for (item in items) {
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
                    transport.acknowledgeStoredDelivery(deviceId, item)
                    transport.removeFromMyDeviceInbox(deviceId, item.deliveryId)
                    stored++
                }
                OlmInboundRegistration.PENDING_RETRY -> {
                    transport.acknowledgeStoredDelivery(deviceId, item)
                    transport.removeFromMyDeviceInbox(deviceId, item.deliveryId)
                    pending++
                }
                OlmInboundRegistration.DUPLICATE_PROCESSED -> {
                    transport.acknowledgeStoredDelivery(deviceId, item)
                    transport.removeFromMyDeviceInbox(deviceId, item.deliveryId)
                    processed++
                }
            }
        }
        return FirebaseOlmInboxReceiveResult(stored, pending, processed, conflicts)
    }
}
