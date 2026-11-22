package com.example.inchat.crypto

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await

internal data class FirebaseOlmInboxItem(
    val deliveryId: String,
    val senderUid: String,
    val senderDeviceId: String,
    val messageId: String,
    val envelope: String,
    val groupId: String? = null,
    val membershipEpoch: Long? = null,
    val conversationId: String? = null,
    val conversationType: String? = null,
)

internal data class FirebaseOlmRelayRecord(
    val deliveryId: String?,
    val fields: Map<String, Any?>,
)

/**
 * Narrow relay boundary shared by the Firebase adapter and deterministic
 * failure-injection tests. Values are routing metadata or ciphertext only.
 */
internal interface OlmRelayStore {
    suspend fun putInbox(
        recipientUid: String,
        recipientDeviceId: String,
        deliveryId: String,
        fields: Map<String, Any?>,
    )

    suspend fun readInbox(recipientUid: String, recipientDeviceId: String): List<FirebaseOlmRelayRecord>

    suspend fun removeInbox(recipientUid: String, recipientDeviceId: String, deliveryId: String)

    suspend fun putAcknowledgement(
        senderUid: String,
        senderDeviceId: String,
        deliveryId: String,
        fields: Map<String, Any?>,
    )

    suspend fun readAcknowledgements(senderUid: String, senderDeviceId: String): List<FirebaseOlmRelayRecord>

    suspend fun removeAcknowledgement(senderUid: String, senderDeviceId: String, deliveryId: String)
}

/** Production relay adapter. All paths remain below olmInbox/olmDeliveryAcks. */
internal class FirebaseOlmRelayStore(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
) : OlmRelayStore {
    override suspend fun putInbox(
        recipientUid: String,
        recipientDeviceId: String,
        deliveryId: String,
        fields: Map<String, Any?>,
    ) {
        database.reference.child(INBOX_NODE)
            .child(recipientUid)
            .child(recipientDeviceId)
            .child(deliveryId)
            .setValue(fields)
            .await()
    }

    override suspend fun readInbox(
        recipientUid: String,
        recipientDeviceId: String,
    ): List<FirebaseOlmRelayRecord> =
        database.reference.child(INBOX_NODE)
            .child(recipientUid)
            .child(recipientDeviceId)
            .get()
            .await()
            .children
            .map(::toRelayRecord)

    override suspend fun removeInbox(
        recipientUid: String,
        recipientDeviceId: String,
        deliveryId: String,
    ) {
        database.reference.child(INBOX_NODE)
            .child(recipientUid)
            .child(recipientDeviceId)
            .child(deliveryId)
            .removeValue()
            .await()
    }

    override suspend fun putAcknowledgement(
        senderUid: String,
        senderDeviceId: String,
        deliveryId: String,
        fields: Map<String, Any?>,
    ) {
        database.reference.child(ACK_NODE)
            .child(senderUid)
            .child(senderDeviceId)
            .child(deliveryId)
            .setValue(fields)
            .await()
    }

    override suspend fun readAcknowledgements(
        senderUid: String,
        senderDeviceId: String,
    ): List<FirebaseOlmRelayRecord> =
        database.reference.child(ACK_NODE)
            .child(senderUid)
            .child(senderDeviceId)
            .get()
            .await()
            .children
            .map(::toRelayRecord)

    override suspend fun removeAcknowledgement(
        senderUid: String,
        senderDeviceId: String,
        deliveryId: String,
    ) {
        database.reference.child(ACK_NODE)
            .child(senderUid)
            .child(senderDeviceId)
            .child(deliveryId)
            .removeValue()
            .await()
    }

    private fun toRelayRecord(snapshot: DataSnapshot): FirebaseOlmRelayRecord {
        val raw = snapshot.value as? Map<*, *> ?: emptyMap<Any, Any>()
        val fields = raw.entries.mapNotNull { entry ->
            (entry.key as? String)?.let { key -> key to entry.value }
        }.toMap()
        return FirebaseOlmRelayRecord(snapshot.key, fields)
    }

    private companion object {
        const val INBOX_NODE = "olmInbox"
        const val ACK_NODE = "olmDeliveryAcks"
    }
}

internal interface OlmInboxTransport {
    suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem>
    suspend fun acknowledgeStoredDelivery(deviceId: String, item: FirebaseOlmInboxItem)
    suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String)
}

internal interface OlmDeliveryAckTransport {
    suspend fun readMyDeliveryAcknowledgements(senderDeviceId: String): List<OlmDeliveryAcknowledgement>
    suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String)
}

/** Sender-side transport used only by the isolated encrypted device outbox. */
internal interface OlmOutgoingDeliveryTransport : OlmDeliveryAckTransport {
    suspend fun upload(
        delivery: QueuedOlmDeviceDelivery,
        senderDeviceId: String,
    ): FirebaseOlmUploadResult
}

internal enum class FirebaseOlmUploadResult {
    STORED,
}

/**
 * Isolated ciphertext-only transport over Firebase Realtime Database.
 *
 * Firebase remains an untrusted relay. Database rules enforce recipient/device
 * ownership and immutable exact retries; the receiver still authenticates the
 * Olm ciphertext before any plaintext can be committed.
 */
internal class FirebaseOlmEnvelopeTransport(
    private val trustVerifier: OlmDeviceTrustVerifier,
    private val relayStore: OlmRelayStore = FirebaseOlmRelayStore(),
    private val currentAuthenticatedUserId: () -> String? = {
        FirebaseAuth.getInstance().currentUser?.uid
    },
    private val readPublishedDevices: suspend (String) -> List<PublicDeviceKeyBundle> = { uid ->
        DeviceKeyDirectoryRepository().getUserDevices(uid)
    },
    private val expectedAuthenticatedUserId: String? = null,
) : OlmInboxTransport, OlmOutgoingDeliveryTransport {

    /**
     * Bind each worker-created transport to its scheduled account. The callback
     * seam makes sign-out/account-switch races testable without Firebase Auth.
     */
    private fun requireAuthenticatedUserId(expectedUserId: String? = expectedAuthenticatedUserId): String {
        val uid = requireNotNull(currentAuthenticatedUserId()) {
            "Sign in before using encrypted delivery transport"
        }
        require(UID_PATTERN.matches(uid)) { "Authenticated user ID has an invalid format" }
        if (expectedUserId != null) {
            require(uid == expectedUserId) {
                "The signed-in account does not match this encrypted delivery transport"
            }
        }
        return uid
    }

    override suspend fun upload(
        delivery: QueuedOlmDeviceDelivery,
        senderDeviceId: String,
    ): FirebaseOlmUploadResult {
        require(delivery.status == OlmDeliveryStatus.IN_FLIGHT) {
            "Only a claimed outbox delivery can be uploaded"
        }
        val senderUid = requireAuthenticatedUserId()
        require(delivery.ownerUserId == senderUid) {
            "Outbox row does not belong to the authenticated sender account"
        }
        require(delivery.senderDeviceId == senderDeviceId) {
            "Outbox row does not belong to the active sender device"
        }
        require(UID_PATTERN.matches(delivery.recipientUserId)) { "Recipient user ID has an invalid format" }
        require(DEVICE_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(DEVICE_PATTERN.matches(delivery.recipientDeviceId)) {
            "Recipient device ID has an invalid format"
        }
        require(DELIVERY_PATTERN.matches(delivery.deliveryId)) { "Delivery ID has an invalid format" }
        require(MESSAGE_PATTERN.matches(delivery.messageId)) { "Message ID has an invalid format" }

        // Fetch the current, signature-verified public bundle and re-check the
        // active account after the asynchronous directory read.
        val currentRecipientDevice = readPublishedDevices(delivery.recipientUserId)
            .firstOrNull { it.deviceId == delivery.recipientDeviceId }
            ?: throw IllegalStateException("Recipient device is no longer published")
        requireAuthenticatedUserId(senderUid)
        check(
            trustVerifier.isVerified(
                peerUserId = delivery.recipientUserId,
                peerDeviceId = currentRecipientDevice.deviceId,
                peerEd25519IdentityKey = currentRecipientDevice.ed25519IdentityKey,
            )
        ) {
            "Recipient device is no longer trusted on this installation"
        }

        val envelope = EncryptedOlmEnvelopeCodec.decode(delivery.envelope)
        require(envelope.senderDeviceId == senderDeviceId) {
            "Envelope sender device does not match the authenticated sender device"
        }
        require(envelope.recipientDeviceId == delivery.recipientDeviceId) {
            "Envelope recipient device does not match the delivery target"
        }

        require((delivery.groupId == null) == (delivery.membershipEpoch == null) &&
            (delivery.membershipEpoch == null || delivery.membershipEpoch > 0L)
        ) { "Group relay identity is incomplete" }
        val conversationType: String
        val conversationId: String
        if (delivery.groupId != null && delivery.membershipEpoch != null) {
            require(GROUP_PATTERN.matches(delivery.groupId))
            conversationType = "group"
            conversationId = delivery.groupId
        } else {
            conversationType = "direct"
            conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId(
                senderUid,
                delivery.recipientUserId,
            )
        }
        val payload = mapOf(
            "senderUid" to senderUid,
            "senderDeviceId" to senderDeviceId,
            "messageId" to delivery.messageId,
            "envelope" to delivery.envelope,
            "conversationType" to conversationType,
            "conversationId" to conversationId,
        ) + if (delivery.groupId != null && delivery.membershipEpoch != null) mapOf(
            "groupId" to delivery.groupId,
            "membershipEpoch" to delivery.membershipEpoch,
        ) else emptyMap()
        requireAuthenticatedUserId(senderUid)
        relayStore.putInbox(
            delivery.recipientUserId,
            delivery.recipientDeviceId,
            delivery.deliveryId,
            payload,
        )
        // A lost completion is deliberately surfaced as a retry. The stable
        // delivery ID and immutable payload make the next write idempotent.
        requireAuthenticatedUserId(senderUid)
        return FirebaseOlmUploadResult.STORED
    }

    /** One-shot inbox read. A later worker poll performs reconnection recovery. */
    override suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> {
        val uid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(deviceId)) { "Device ID has an invalid format" }
        val records = relayStore.readInbox(uid, deviceId)
        requireAuthenticatedUserId(uid)
        return records.mapNotNull { parseInboxItem(it, deviceId) }
            .sortedBy { it.deliveryId }
    }

    /**
     * Publish the recipient acknowledgement only after the ciphertext receipt
     * has been stored in Room. The inbox item is deleted by a separate call.
     */
    override suspend fun acknowledgeStoredDelivery(
        deviceId: String,
        item: FirebaseOlmInboxItem,
    ) {
        val recipientUid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(deviceId))
        require(UID_PATTERN.matches(item.senderUid))
        require(DEVICE_PATTERN.matches(item.senderDeviceId))
        require(DELIVERY_PATTERN.matches(item.deliveryId))
        require(MESSAGE_PATTERN.matches(item.messageId))

        val acknowledgement = mapOf(
            "recipientUid" to recipientUid,
            "recipientDeviceId" to deviceId,
            "messageId" to item.messageId,
            "acknowledgedAt" to ServerValue.TIMESTAMP,
        )
        requireAuthenticatedUserId(recipientUid)
        relayStore.putAcknowledgement(
            item.senderUid,
            item.senderDeviceId,
            item.deliveryId,
            acknowledgement,
        )
        requireAuthenticatedUserId(recipientUid)
    }

    /** Delete only a delivery in the currently authenticated user's own inbox. */
    override suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) {
        val uid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(deviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        requireAuthenticatedUserId(uid)
        relayStore.removeInbox(uid, deviceId, deliveryId)
        requireAuthenticatedUserId(uid)
    }

    /** Read only acknowledgements under the authenticated sender account/device. */
    override suspend fun readMyDeliveryAcknowledgements(
        senderDeviceId: String,
    ): List<OlmDeliveryAcknowledgement> {
        val uid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(senderDeviceId)) {
            "Sender device ID has an invalid format"
        }
        val records = relayStore.readAcknowledgements(uid, senderDeviceId)
        requireAuthenticatedUserId(uid)
        return records.mapNotNull { parseAcknowledgement(it, uid, senderDeviceId) }
            .sortedBy { it.deliveryId }
    }

    /** Delete only an acknowledgement in the authenticated sender's own path. */
    override suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String) {
        val uid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(senderDeviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        requireAuthenticatedUserId(uid)
        relayStore.removeAcknowledgement(uid, senderDeviceId, deliveryId)
        requireAuthenticatedUserId(uid)
    }

    private fun parseAcknowledgement(
        record: FirebaseOlmRelayRecord,
        senderUserId: String,
        senderDeviceId: String,
    ): OlmDeliveryAcknowledgement? = runCatching {
        val deliveryId = requireNotNull(record.deliveryId)
        val fields = record.fields
        require(fields.keys == ACK_FIELDS) {
            "Encrypted delivery acknowledgement has missing or unexpected fields"
        }
        val recipientUid = fields["recipientUid"] as? String
            ?: error("Acknowledgement recipient account is invalid")
        val recipientDeviceId = fields["recipientDeviceId"] as? String
            ?: error("Acknowledgement recipient device is invalid")
        val messageId = fields["messageId"] as? String
            ?: error("Acknowledgement message ID is invalid")
        val acknowledgedAt = fields["acknowledgedAt"] as? Number
            ?: error("Acknowledgement timestamp is invalid")
        val timestamp = acknowledgedAt.toDouble()
        require(DELIVERY_PATTERN.matches(deliveryId))
        require(UID_PATTERN.matches(recipientUid))
        require(DEVICE_PATTERN.matches(recipientDeviceId))
        require(MESSAGE_PATTERN.matches(messageId))
        require(timestamp.isFinite() && timestamp > 0.0 && timestamp % 1.0 == 0.0)
        OlmDeliveryAcknowledgement(
            deliveryId = deliveryId,
            recipientUserId = recipientUid,
            recipientDeviceId = recipientDeviceId,
            messageId = messageId,
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
        )
    }.getOrNull()

    private fun parseInboxItem(
        record: FirebaseOlmRelayRecord,
        expectedRecipientDeviceId: String,
    ): FirebaseOlmInboxItem? = runCatching {
        val deliveryId = requireNotNull(record.deliveryId)
        val fields = record.fields
        require(fields.keys == DIRECT_INBOX_FIELDS || fields.keys == GROUP_INBOX_FIELDS) {
            "Encrypted inbox item has missing or unexpected fields"
        }
        val senderUid = fields["senderUid"] as? String
            ?: error("Encrypted inbox sender account is invalid")
        val senderDeviceId = fields["senderDeviceId"] as? String
            ?: error("Encrypted inbox sender device is invalid")
        val messageId = fields["messageId"] as? String
            ?: error("Encrypted inbox message ID is invalid")
        val envelope = fields["envelope"] as? String
            ?: error("Encrypted inbox envelope is invalid")
        require(DELIVERY_PATTERN.matches(deliveryId))
        require(UID_PATTERN.matches(senderUid))
        require(DEVICE_PATTERN.matches(senderDeviceId))
        require(MESSAGE_PATTERN.matches(messageId))
        val decoded = EncryptedOlmEnvelopeCodec.decode(envelope)
        require(decoded.senderDeviceId == senderDeviceId)
        require(decoded.recipientDeviceId == expectedRecipientDeviceId)
        val conversationType = fields["conversationType"] as? String
            ?: error("Encrypted inbox conversation type is invalid")
        val conversationId = fields["conversationId"] as? String
            ?: error("Encrypted inbox conversation ID is invalid")
        val groupId = fields["groupId"] as? String
        val membershipEpochNumber = fields["membershipEpoch"] as? Number
        val membershipEpoch = membershipEpochNumber?.toLong()
        require(UID_PATTERN.matches(requireAuthenticatedUserId()))
        if (conversationType == "direct") {
            require(fields.keys == DIRECT_INBOX_FIELDS)
            require(groupId == null && membershipEpoch == null)
            require(conversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(senderUid, requireAuthenticatedUserId()))
        } else {
            require(conversationType == "group")
            require(fields.keys == GROUP_INBOX_FIELDS)
            require(groupId != null && GROUP_PATTERN.matches(groupId))
            require(conversationId == groupId && membershipEpochNumber != null &&
                membershipEpoch != null && membershipEpoch > 0L &&
                membershipEpochNumber.toDouble().isFinite() &&
                membershipEpochNumber.toDouble() == membershipEpoch.toDouble()
            )
        }
        FirebaseOlmInboxItem(
            deliveryId, senderUid, senderDeviceId, messageId, envelope,
            groupId, membershipEpoch, conversationId, conversationType,
        )
    }.getOrNull()

    private companion object {
        val BASE_INBOX_FIELDS = setOf("senderUid", "senderDeviceId", "messageId", "envelope", "conversationType", "conversationId")
        val DIRECT_INBOX_FIELDS = BASE_INBOX_FIELDS
        val GROUP_INBOX_FIELDS = BASE_INBOX_FIELDS + setOf("groupId", "membershipEpoch")
        val GROUP_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val ACK_FIELDS = setOf("recipientUid", "recipientDeviceId", "messageId", "acknowledgedAt")
        val UID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val DELIVERY_PATTERN = Regex("[A-Fa-f0-9]{64}")
        val MESSAGE_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
