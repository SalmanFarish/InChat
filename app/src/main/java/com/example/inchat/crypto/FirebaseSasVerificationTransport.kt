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

internal data class SasVerificationRelayRecord(
    val deliveryId: String?,
    val fields: Map<String, Any?>,
)

internal interface SasVerificationRelayStore {
    suspend fun put(
        recipientUserId: String,
        recipientDeviceId: String,
        deliveryId: String,
        fields: Map<String, Any?>,
    )

    suspend fun readInbox(
        recipientUserId: String,
        recipientDeviceId: String,
    ): List<SasVerificationRelayRecord>

    suspend fun remove(
        recipientUserId: String,
        recipientDeviceId: String,
        deliveryId: String,
    )
}

/** Firebase adapter kept separate from transport validation so the latter can be failure-tested locally. */
internal class FirebaseSasVerificationRelayStore(
    private val database: FirebaseDatabase,
) : SasVerificationRelayStore {
    override suspend fun put(
        recipientUserId: String,
        recipientDeviceId: String,
        deliveryId: String,
        fields: Map<String, Any?>,
    ) {
        inboxReference(recipientUserId, recipientDeviceId, deliveryId)
            .setValue(fields).await()
    }

    override suspend fun readInbox(
        recipientUserId: String,
        recipientDeviceId: String,
    ): List<SasVerificationRelayRecord> {
        val snapshot = database.reference.child(INBOX_NODE)
            .child(recipientUserId)
            .child(recipientDeviceId)
            .get()
            .await()
        return snapshot.children.map { child ->
            val fields = child.children.mapNotNull { field ->
                field.key?.let { key -> key to field.value }
            }.toMap()
            SasVerificationRelayRecord(child.key, fields)
        }
    }

    override suspend fun remove(
        recipientUserId: String,
        recipientDeviceId: String,
        deliveryId: String,
    ) {
        inboxReference(recipientUserId, recipientDeviceId, deliveryId)
            .removeValue().await()
    }

    private fun inboxReference(
        recipientUserId: String,
        recipientDeviceId: String,
        deliveryId: String,
    ) = database.reference.child(INBOX_NODE)
        .child(recipientUserId)
        .child(recipientDeviceId)
        .child(deliveryId)

    private companion object {
        const val INBOX_NODE = "sasVerificationInbox"
    }
}

private val SAS_DELIVERY_PATTERN = Regex("[A-Fa-f0-9]{64}")
private val SAS_RELAY_FIELDS = setOf(
    "senderUid",
    "senderDeviceId",
    "transactionId",
    "type",
    "payload",
    "createdAtMillis",
    "expiresAtMillis",
    "canonicalMessage",
)

private fun Map<String, Any?>.longField(name: String): Long? {
    val number = this[name] as? Number ?: return null
    val doubleValue = number.toDouble()
    if (!doubleValue.isFinite() || doubleValue % 1.0 != 0.0) return null
    val longValue = number.toLong()
    return longValue.takeIf { it.toDouble() == doubleValue }
}

internal fun isExpiredSasRelayEntry(
    deliveryId: String?,
    expiresAtMillis: Long?,
    nowMillis: Long,
): Boolean =
    deliveryId != null &&
        SAS_DELIVERY_PATTERN.matches(deliveryId) &&
        expiresAtMillis != null &&
        expiresAtMillis <= nowMillis

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
    private val localUserId: String,
    private val localDeviceId: String,
    private val relayStore: SasVerificationRelayStore =
        FirebaseSasVerificationRelayStore(FirebaseDatabase.getInstance()),
    private val currentAuthenticatedUserId: () -> String? = {
        FirebaseAuth.getInstance().currentUser?.uid
    },
    private val clock: () -> Long = System::currentTimeMillis,
) : SasVerificationTransport {
    init {
        require(USER_PATTERN.matches(localUserId)) { "Local user ID has an invalid format" }
        require(DEVICE_PATTERN.matches(localDeviceId)) { "Local device ID has an invalid format" }
    }

    /**
     * A transport instance belongs to the account that created its coordinator.
     * Never derive the inbox owner dynamically from FirebaseAuth: an account switch
     * must fail closed rather than redirect a stale coordinator to another inbox.
     */
    private fun requireAuthenticatedLocalUser(): String {
        val uid = requireNotNull(currentAuthenticatedUserId()) {
            "Sign in before using the verification transport"
        }
        require(uid == localUserId) {
            "The signed-in account does not match this verification transport"
        }
        return uid
    }

    override suspend fun upload(message: SasVerificationMessage) {
        val uid = requireAuthenticatedLocalUser()
        require(message.senderUserId == localUserId) {
            "SAS sender must be this verification transport's account"
        }
        require(message.senderDeviceId == localDeviceId) {
            "SAS sender device must be this installation"
        }
        val now = clock().coerceAtLeast(0L)
        require(message.expiresAtMillis > now) { "SAS verification transaction has expired" }
        val encoded = SasVerificationMessageCodec.encode(message)
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
        // Recheck account ownership and expiry immediately before the remote
        // write. Firebase rules remain the authoritative boundary if either
        // changes while the payload is being prepared.
        requireAuthenticatedLocalUser()
        require(message.expiresAtMillis > clock().coerceAtLeast(0L)) {
            "SAS verification transaction expired before upload"
        }
        relayStore.put(
            message.recipientUserId,
            message.recipientDeviceId,
            message.deliveryId,
            payload,
        )
    }

    override suspend fun readInbox(deviceId: String): List<SasVerificationInboxItem> {
        requireAuthenticatedLocalUser()
        require(deviceId == localDeviceId) { "SAS inbox must be read for this installation" }
        val records = relayStore.readInbox(localUserId, deviceId)
        // Auth can change while the database read is in flight. Do not return
        // messages to a coordinator after its owning account has signed out.
        requireAuthenticatedLocalUser()
        // Capture time after the asynchronous read so an entry expiring while
        // the request is in flight cannot be accepted using a stale timestamp.
        val now = clock().coerceAtLeast(0L)

        val pending = mutableListOf<SasVerificationInboxItem>()
        for (record in records) {
            if (isExpiredSasRelayEntry(
                    record.deliveryId,
                    record.fields.longField("expiresAtMillis"),
                    now,
                )
            ) {
                // SAS relay rows are immutable after creation. The addressed
                // recipient is allowed to delete its own rows, so remove expired
                // entries instead of rereading the same stale rows on every poll.
                // Recheck auth immediately before each destructive operation.
                requireAuthenticatedLocalUser()
                relayStore.remove(localUserId, deviceId, requireNotNull(record.deliveryId))
                continue
            }
            parse(record, localUserId, deviceId, now)?.let(pending::add)
        }
        return pending.sortedBy { it.message.createdAtMillis }
    }

    override suspend fun acknowledge(deviceId: String, deliveryId: String) {
        requireAuthenticatedLocalUser()
        require(deviceId == localDeviceId) { "Only this installation can acknowledge its SAS inbox" }
        require(SAS_DELIVERY_PATTERN.matches(deliveryId))
        // Keep account-bound ownership explicit immediately before the delete.
        requireAuthenticatedLocalUser()
        relayStore.remove(localUserId, deviceId, deliveryId)
    }

    private fun parse(
        record: SasVerificationRelayRecord,
        recipientUid: String,
        recipientDeviceId: String,
        now: Long,
    ): SasVerificationInboxItem? = runCatching {
        val deliveryId = requireNotNull(record.deliveryId)
        require(SAS_DELIVERY_PATTERN.matches(deliveryId))
        val fields = record.fields
        require(fields.keys == SAS_RELAY_FIELDS) {
            "SAS relay record has missing or unexpected fields"
        }
        fun requiredString(name: String): String =
            requireNotNull(fields[name] as? String) { "SAS relay field $name is invalid" }
        fun requiredLong(name: String): Long =
            requireNotNull(fields.longField(name)) { "SAS relay field $name is invalid" }

        val senderUid = requiredString("senderUid")
        val senderDeviceId = requiredString("senderDeviceId")
        val transactionId = requiredString("transactionId")
        val type = SasVerificationMessageType.fromWire(requiredString("type"))
        val payload = requiredString("payload")
        val createdAt = requiredLong("createdAtMillis")
        val expiresAt = requiredLong("expiresAtMillis")
        val message = SasVerificationMessage(
            transactionId, senderUid, senderDeviceId, recipientUid, recipientDeviceId,
            type, payload, createdAt, expiresAt,
        )
        require(message.deliveryId == deliveryId)
        val canonical = SasVerificationMessageCodec.encode(message)
        require(requiredString("canonicalMessage") == canonical)
        SasVerificationMessageCodec.decode(canonical, recipientUid, recipientDeviceId, now)
        SasVerificationInboxItem(deliveryId, senderUid, message)
    }.getOrNull()

    private companion object {
        val USER_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * Durable receiver: validate and register first, let the application coordinator
 * handle the message, mark it processed, and only then acknowledge the relay.
 *
 * If the process stops after registration but before the callback completes,
 * the next receive retries the callback instead of treating the delivery as done.
 */
internal class SasVerificationInboxReceiver(
    private val transport: SasVerificationTransport,
    private val ledger: SasVerificationMessageLedger,
    private val localUserId: String,
    private val localDeviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun receivePending(
        onMessage: suspend (SasVerificationMessage) -> Unit = {},
    ): SasVerificationInboxReceiveResult {
        val items = transport.readInbox(localDeviceId)
        var received = 0
        var duplicates = 0
        var conflicts = 0
        val now = clock().coerceAtLeast(0L)
        for (item in items) {
            require(item.deliveryId == item.message.deliveryId) {
                "SAS inbox delivery key does not match its canonical message"
            }
            require(item.senderUid == item.message.senderUserId) {
                "SAS inbox sender does not match the authenticated message sender"
            }
            val encoded = SasVerificationMessageCodec.encode(item.message)
            val decoded = SasVerificationMessageCodec.decode(
                encoded, localUserId, localDeviceId, now,
            )
            when (ledger.register(decoded, now)) {
                SasMessageRegistration.NEW -> {
                    onMessage(decoded)
                    ledger.markProcessed(decoded, clock().coerceAtLeast(0L))
                    transport.acknowledge(localDeviceId, item.deliveryId)
                    received++
                }
                SasMessageRegistration.DUPLICATE -> {
                    if (!ledger.isProcessed(item.deliveryId)) {
                        onMessage(decoded)
                        ledger.markProcessed(decoded, clock().coerceAtLeast(0L))
                        received++
                    } else {
                        duplicates++
                    }
                    transport.acknowledge(localDeviceId, item.deliveryId)
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
