package com.example.inchat.crypto

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

internal data class FirebaseOlmInboxItem(
    val deliveryId: String,
    val senderUid: String,
    val senderDeviceId: String,
    val messageId: String,
    val envelope: String,
)

internal enum class FirebaseOlmUploadResult {
    CREATED,
    ALREADY_PRESENT,
}

/**
 * Isolated ciphertext-only transport over Firebase Realtime Database.
 *
 * It never reads or writes the existing chats/{chatId}/messages tree. Upload
 * is create-only and recipient/device scoped by database.rules.json. Firebase
 * is an untrusted relay: the receiver must still authenticate the Olm envelope.
 */
internal class FirebaseOlmEnvelopeTransport(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    suspend fun upload(
        delivery: QueuedOlmDeviceDelivery,
        senderDeviceId: String,
    ): FirebaseOlmUploadResult {
        val senderUid = requireNotNull(auth.currentUser?.uid) {
            "Sign in before uploading an encrypted delivery"
        }
        require(UID_PATTERN.matches(senderUid)) { "Authenticated user ID has an invalid format" }
        require(UID_PATTERN.matches(delivery.recipientUserId)) { "Recipient user ID has an invalid format" }
        require(DEVICE_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(DEVICE_PATTERN.matches(delivery.recipientDeviceId)) { "Recipient device ID has an invalid format" }
        require(DELIVERY_PATTERN.matches(delivery.deliveryId)) { "Delivery ID has an invalid format" }
        require(MESSAGE_PATTERN.matches(delivery.messageId)) { "Message ID has an invalid format" }

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
        return try {
            reference.setValue(payload).await()
            FirebaseOlmUploadResult.CREATED
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A retry after an uncertain network result is safe only if the
            // exact same immutable payload is already present at this ID.
            val existing = try {
                reference.get().await()
            } catch (readError: Exception) {
                if (readError is CancellationException) throw readError
                throw error
            }
            if (existing.matches(senderUid, senderDeviceId, delivery.messageId, delivery.envelope)) {
                FirebaseOlmUploadResult.ALREADY_PRESENT
            } else {
                throw error
            }
        }
    }

    /** One-shot inbox read. A later worker can poll; no live listener is attached here. */
    suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> {
        val uid = requireNotNull(auth.currentUser?.uid) {
            "Sign in before reading encrypted deliveries"
        }
        require(UID_PATTERN.matches(uid)) { "Authenticated user ID has an invalid format" }
        require(DEVICE_PATTERN.matches(deviceId)) { "Device ID has an invalid format" }

        return database.reference.child(INBOX_NODE).child(uid).child(deviceId).get().await()
            .children.mapNotNull(::parseInboxItem)
            .sortedBy { it.deliveryId }
    }

    /** Delete only a delivery in the currently authenticated user's own device inbox. */
    suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) {
        val uid = requireNotNull(auth.currentUser?.uid) {
            "Sign in before acknowledging an encrypted delivery"
        }
        require(UID_PATTERN.matches(uid))
        require(DEVICE_PATTERN.matches(deviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        database.reference.child(INBOX_NODE).child(uid).child(deviceId).child(deliveryId)
            .removeValue().await()
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

    private fun parseInboxItem(snapshot: DataSnapshot): FirebaseOlmInboxItem? {
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
            require(decoded.recipientDeviceId.isNotBlank())
            FirebaseOlmInboxItem(deliveryId, senderUid, senderDeviceId, messageId, envelope)
        }.getOrNull()
    }

    private companion object {
        const val INBOX_NODE = "olmInbox"
        val UID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val DELIVERY_PATTERN = Regex("[A-Fa-f0-9]{64}")
        val MESSAGE_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
