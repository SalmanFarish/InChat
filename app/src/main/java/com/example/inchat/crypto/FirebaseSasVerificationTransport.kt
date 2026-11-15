package com.example.inchat.crypto

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

internal data class SasVerificationInboxItem(
    val deliveryId: String,
    val senderUid: String,
    val message: SasVerificationMessage,
)

internal interface SasVerificationTransport {
    suspend fun upload(message: SasVerificationMessage)
    suspend fun readInbox(deviceId: String): List<SasVerificationInboxItem>
    suspend fun acknowledge(deviceId: String, deliveryId: String)
}

/**
 * Separate, short-lived Firebase relay for SAS protocol messages.
 * It never touches chats, Olm inboxes, or normal message delivery.
 */
internal class FirebaseSasVerificationTransport(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SasVerificationTransport {
    override suspend fun upload(message: SasVerificationMessage) {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in before sending a verification message" }
        require(uid == message.senderUserId) { "SAS sender must be the authenticated account" }
        val now = clock().coerceAtLeast(0L)
        require(message.expiresAtMillis > now) { "SAS verification transaction has expired" }
        val encoded = SasVerificationMessageCodec.encode(message)
        val reference = inboxReference(message.recipientUserId, message.recipientDeviceId, message.deliveryId)
        val payload = mapOf(
            "senderUid" to uid,
            "senderDeviceId" to message.senderDeviceId,
            "transactionId" to message.transactionId,
            "type" to message.type.wireValue,
            "payload" to message.payload,
            "createdAtMillis" to message.createdAtMillis,
            "expiresAtMillis" to message.expiresAtMillis,
            "canonicalMessage" to encoded,
        )
        reference.setValue(payload).await()
    }

    override suspend fun readInbox(deviceId: String): List<SasVerificationInboxItem> {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in before reading verification messages" }
        require(DEVICE_PATTERN.matches(deviceId))
        val now = clock().coerceAtLeast(0L)
        return database.reference.child(INBOX_NODE).child(uid).child(deviceId).get().await()
            .children.mapNotNull { parse(it, uid, deviceId, now) }
            .sortedBy { it.message.createdAtMillis }
    }

    override suspend fun acknowledge(deviceId: String, deliveryId: String) {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in before acknowledging verification messages" }
        require(DEVICE_PATTERN.matches(deviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        database.reference.child(INBOX_NODE).child(uid).child(deviceId).child(deliveryId)
            .removeValue().await()
    }

    private fun parse(
        snapshot: DataSnapshot,
        recipientUid: String,
        recipientDeviceId: String,
        now: Long,
    ): SasVerificationInboxItem? = runCatching {
        val deliveryId = requireNotNull(snapshot.key)
        require(DELIVERY_PATTERN.matches(deliveryId))
        val senderUid = requireNotNull(snapshot.child("senderUid").getValue(String::class.java))
        val senderDeviceId = requireNotNull(snapshot.child("senderDeviceId").getValue(String::class.java))
        val transactionId = requireNotNull(snapshot.child("transactionId").getValue(String::class.java))
        val type = SasVerificationMessageType.fromWire(
            requireNotNull(snapshot.child("type").getValue(String::class.java))
        )
        val payload = requireNotNull(snapshot.child("payload").getValue(String::class.java))
        val createdAt = requireNotNull(snapshot.child("createdAtMillis").getValue(Long::class.java))
        val expiresAt = requireNotNull(snapshot.child("expiresAtMillis").getValue(Long::class.java))
        val message = SasVerificationMessage(
            transactionId, senderUid, senderDeviceId, recipientUid, recipientDeviceId,
            type, payload, createdAt, expiresAt,
        )
        require(message.deliveryId == deliveryId)
        val canonical = SasVerificationMessageCodec.encode(message)
        require(snapshot.child("canonicalMessage").getValue(String::class.java) == canonical)
        SasVerificationMessageCodec.decode(canonical, recipientUid, recipientDeviceId, now)
        SasVerificationInboxItem(deliveryId, senderUid, message)
    }.getOrNull()

    private fun inboxReference(uid: String, deviceId: String, deliveryId: String) =
        database.reference.child(INBOX_NODE).child(uid).child(deviceId).child(deliveryId)

    private companion object {
        const val INBOX_NODE = "sasVerificationInbox"
        val DEVICE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val DELIVERY_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}

/**
 * Durable receiver: validate, persist replay identity, then acknowledge relay.
 * A conflicting delivery is deliberately left on the relay for investigation.
 */
internal class SasVerificationInboxReceiver(
    private val transport: SasVerificationTransport,
    private val ledger: SasVerificationMessageLedger,
    private val localUserId: String,
    private val localDeviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun receivePending(): SasVerificationInboxReceiveResult {
        val items = transport.readInbox(localDeviceId)
        var received = 0
        var duplicates = 0
        var conflicts = 0
        for (item in items) {
            require(item.senderUid == item.message.senderUserId) {
                "SAS inbox sender does not match the authenticated message sender"
            }
            val encoded = SasVerificationMessageCodec.encode(item.message)
            val decoded = SasVerificationMessageCodec.decode(
                encoded, localUserId, localDeviceId, clock().coerceAtLeast(0L),
            )
            when (ledger.register(decoded, clock().coerceAtLeast(0L))) {
                SasMessageRegistration.NEW -> {
                    transport.acknowledge(localDeviceId, item.deliveryId)
                    received++
                }
                SasMessageRegistration.DUPLICATE -> {
                    transport.acknowledge(localDeviceId, item.deliveryId)
                    duplicates++
                }
                SasMessageRegistration.CONFLICT -> conflicts++
            }
        }
        return SasVerificationInboxReceiveResult(received, duplicates, conflicts)
    }
}

internal data class SasVerificationInboxReceiveResult(
    val received: Int,
    val duplicates: Int,
    val conflicts: Int,
)
