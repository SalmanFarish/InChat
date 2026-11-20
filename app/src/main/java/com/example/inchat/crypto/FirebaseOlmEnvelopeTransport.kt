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
)

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
 * It never reads or writes the existing chats/{chatId}/messages tree. Upload
 * is immutable and recipient/device scoped by database.rules.json. Exact retries
 * are accepted idempotently; changed payloads are rejected. Firebase
 * is an untrusted relay: the receiver must still authenticate the Olm envelope.
 */
internal class FirebaseOlmEnvelopeTransport(
    private val trustVerifier: OlmDeviceTrustVerifier,
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val expectedAuthenticatedUserId: String? = null,
) : OlmInboxTransport, OlmOutgoingDeliveryTransport {
    private val deviceDirectory = DeviceKeyDirectoryRepository(database, auth)

    /**
     * Optionally bind this transport instance to the account that scheduled its
     * worker. Recheck after every suspending Firebase read and immediately before
     * remote writes so a stale worker cannot continue under a newly signed-in user.
     */
    private fun requireAuthenticatedUserId(expectedUserId: String? = expectedAuthenticatedUserId): String {
        val uid = requireNotNull(auth.currentUser?.uid) {
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
        require(delivery.status == OlmDeliveryStatus.IN_FLIGHT) { "Only a claimed outbox delivery can be uploaded" }
        val senderUid = requireAuthenticatedUserId()
        require(delivery.ownerUserId == senderUid) {
            "Outbox row does not belong to the authenticated sender account"
        }
        require(delivery.senderDeviceId == senderDeviceId) {
            "Outbox row does not belong to the active sender device"
        }
        require(UID_PATTERN.matches(delivery.recipientUserId)) { "Recipient user ID has an invalid format" }
        require(DEVICE_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(DEVICE_PATTERN.matches(delivery.recipientDeviceId)) { "Recipient device ID has an invalid format" }
        require(DELIVERY_PATTERN.matches(delivery.deliveryId)) { "Delivery ID has an invalid format" }
        require(MESSAGE_PATTERN.matches(delivery.messageId)) { "Message ID has an invalid format" }

        // Re-read and signature-verify the current public bundle immediately
        // before relay upload. This prevents a queued copy from being delivered
        // after its pinned identity has changed or local trust was revoked.
        val currentRecipientDevice = deviceDirectory.getUserDevices(delivery.recipientUserId)
            .firstOrNull { it.deviceId == delivery.recipientDeviceId }
            ?: throw IllegalStateException("Recipient device is no longer published")
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

        val reference = inboxReference(delivery.recipientUserId, delivery.recipientDeviceId, delivery.deliveryId)
        val payload = mapOf(
            "senderUid" to senderUid,
            "senderDeviceId" to senderDeviceId,
            "messageId" to delivery.messageId,
            "envelope" to delivery.envelope,
        )
        requireAuthenticatedUserId(senderUid)
        reference.setValue(payload).await()
        return FirebaseOlmUploadResult.STORED
    }

    /** One-shot inbox read. A later worker can poll; no live listener is attached here. */
    override suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> {
        val uid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(deviceId)) { "Device ID has an invalid format" }

        val snapshot = database.reference.child(INBOX_NODE).child(uid).child(deviceId).get().await()
        requireAuthenticatedUserId(uid)
        return snapshot.children.mapNotNull { parseInboxItem(it, deviceId) }
            .sortedBy { it.deliveryId }
    }

    /**
     * Tell the originating device that this recipient device has durably
     * registered the ciphertext in its local Room receipt ledger.
     *
     * The acknowledgement is written before removing the relay copy. If either
     * operation is interrupted, the receiver can safely repeat both operations.
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
        database.reference.child(ACK_NODE)
            .child(item.senderUid)
            .child(item.senderDeviceId)
            .child(item.deliveryId)
            .setValue(acknowledgement)
            .await()
        requireAuthenticatedUserId(recipientUid)
    }

    /** Delete only a delivery in the currently authenticated user's own device inbox. */
    override suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) {
        val uid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(deviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        requireAuthenticatedUserId(uid)
        database.reference.child(INBOX_NODE).child(uid).child(deviceId).child(deliveryId)
            .removeValue().await()
        requireAuthenticatedUserId(uid)
    }

    /**
     * Read only acknowledgements under the authenticated sender user/device path.
     * Invalid or incomplete records are ignored rather than used for queue mutation.
     */
    override suspend fun readMyDeliveryAcknowledgements(
        senderDeviceId: String,
    ): List<OlmDeliveryAcknowledgement> {
        val uid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }

        val snapshot = database.reference.child(ACK_NODE).child(uid).child(senderDeviceId).get().await()
        requireAuthenticatedUserId(uid)
        return snapshot.children.mapNotNull { parseAcknowledgement(it, uid, senderDeviceId) }
            .sortedBy { it.deliveryId }
    }

    /** Delete only an acknowledgement in the authenticated user's own sender-device path. */
    override suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String) {
        val uid = requireAuthenticatedUserId()
        require(DEVICE_PATTERN.matches(senderDeviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        requireAuthenticatedUserId(uid)
        database.reference.child(ACK_NODE).child(uid).child(senderDeviceId).child(deliveryId)
            .removeValue().await()
        requireAuthenticatedUserId(uid)
    }

    private fun parseAcknowledgement(snapshot: DataSnapshot, senderUserId: String, senderDeviceId: String): OlmDeliveryAcknowledgement? {
        val deliveryId = snapshot.key ?: return null
        val recipientUid = snapshot.child("recipientUid").getValue(String::class.java) ?: return null
        val recipientDeviceId = snapshot.child("recipientDeviceId").getValue(String::class.java) ?: return null
        val messageId = snapshot.child("messageId").getValue(String::class.java) ?: return null
        val acknowledgedAt = snapshot.child("acknowledgedAt").value as? Number ?: return null
        val timestamp = acknowledgedAt.toDouble()
        if (!DELIVERY_PATTERN.matches(deliveryId) ||
            !UID_PATTERN.matches(recipientUid) ||
            !DEVICE_PATTERN.matches(recipientDeviceId) ||
            !MESSAGE_PATTERN.matches(messageId) ||
            !timestamp.isFinite() ||
            timestamp <= 0.0 ||
            timestamp % 1.0 != 0.0
        ) return null

        return OlmDeliveryAcknowledgement(
            deliveryId = deliveryId,
            recipientUserId = recipientUid,
            recipientDeviceId = recipientDeviceId,
            messageId = messageId,
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
        )
    }

    private fun inboxReference(uid: String, deviceId: String, deliveryId: String) =
        database.reference.child(INBOX_NODE).child(uid).child(deviceId).child(deliveryId)

    private fun DataSnapshot.matches(
        senderUid: String,
        senderDeviceId: String,
        messageId: String,
        envelope: String,
    ): Boolean =
        exists() &&
            child("senderUid").getValue(String::class.java) == senderUid &&
            child("senderDeviceId").getValue(String::class.java) == senderDeviceId &&
            child("messageId").getValue(String::class.java) == messageId &&
            child("envelope").getValue(String::class.java) == envelope

    private fun parseInboxItem(snapshot: DataSnapshot, expectedRecipientDeviceId: String): FirebaseOlmInboxItem? {
        val deliveryId = snapshot.key ?: return null
        val senderUid = snapshot.child("senderUid").getValue(String::class.java) ?: return null
        val senderDeviceId = snapshot.child("senderDeviceId").getValue(String::class.java) ?: return null
        val messageId = snapshot.child("messageId").getValue(String::class.java) ?: return null
        val envelope = snapshot.child("envelope").getValue(String::class.java) ?: return null
        if (!DELIVERY_PATTERN.matches(deliveryId) ||
            !UID_PATTERN.matches(senderUid) ||
            !DEVICE_PATTERN.matches(senderDeviceId) ||
            !MESSAGE_PATTERN.matches(messageId)
        ) return null

        return runCatching {
            val decoded = EncryptedOlmEnvelopeCodec.decode(envelope)
            require(decoded.senderDeviceId == senderDeviceId)
            require(decoded.recipientDeviceId == expectedRecipientDeviceId)
            FirebaseOlmInboxItem(deliveryId, senderUid, senderDeviceId, messageId, envelope)
        }.getOrNull()
    }

    private companion object {
        const val INBOX_NODE = "olmInbox"
        const val ACK_NODE = "olmDeliveryAcks"
        val UID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val DELIVERY_PATTERN = Regex("[A-Fa-f0-9]{64}")
        val MESSAGE_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
