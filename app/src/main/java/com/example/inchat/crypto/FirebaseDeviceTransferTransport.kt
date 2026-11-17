package com.example.inchat.crypto

import android.util.Base64
import com.example.inchat.data.repository.FirebaseServerClock
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * Short-lived, same-account relay for device-identity ciphertext.
 *
 * Only the AEAD envelope is uploaded. Transfer keys, pairing private keys,
 * identity state and acknowledgement signatures never enter Firebase.
 * The receiver verifies size, chunk count, expiry and SHA-256 before import.
 */
internal data class DeviceTransferRelayPayload(
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val transactionId: String,
    val encryptedPayload: ByteArray,
)

internal interface DeviceIdentityTransferTransport {
    suspend fun upload(sourceDeviceId: String, targetDeviceId: String, transactionId: String, encryptedPayload: ByteArray)
    suspend fun download(targetDeviceId: String, transactionId: String): DeviceTransferRelayPayload?
    suspend fun publishAcknowledgement(targetDeviceId: String, transactionId: String, acknowledgement: ByteArray)
    suspend fun readAcknowledgement(targetDeviceId: String, transactionId: String): ByteArray?
    suspend fun deleteCompleted(targetDeviceId: String, transactionId: String)
}

internal class FirebaseDeviceTransferTransport(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val clock: () -> Long = FirebaseServerClock::now,
) : DeviceIdentityTransferTransport {
    override suspend fun upload(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        encryptedPayload: ByteArray,
    ) {
        val uid = requireUid()
        validateIds(sourceDeviceId, targetDeviceId, transactionId)
        require(sourceDeviceId != targetDeviceId)
        require(encryptedPayload.size in 1..MAX_PAYLOAD_BYTES) {
            "Encrypted device transfer has an invalid size"
        }
        val now = clock().coerceAtLeast(0L)
        val expiresAt = now + TRANSFER_LIFETIME_MILLIS
        val root = database.reference
        require(root.child(DEVICE_KEYS_NODE).child(uid).child(sourceDeviceId).get().await().exists()) {
            "Source device is not published in this account"
        }

        val transaction = root.child(INBOX_NODE).child(uid).child(targetDeviceId).child(transactionId)
        require(!transaction.get().await().exists()) { "A relay record already exists for this transfer" }

        val chunkCount = (encryptedPayload.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        val metadata = mapOf(
            "protocolVersion" to PROTOCOL_VERSION,
            "sourceDeviceId" to sourceDeviceId,
            "targetDeviceId" to targetDeviceId,
            "transactionId" to transactionId,
            "createdAtMillis" to now,
            "expiresAtMillis" to expiresAt,
            "payloadSize" to encryptedPayload.size,
            "chunkCount" to chunkCount,
            "payloadSha256" to sha256Hex(encryptedPayload),
        )

        try {
            transaction.child("metadata").setValue(metadata).await()
            repeat(chunkCount) { index ->
                val start = index * CHUNK_BYTES
                val end = minOf(start + CHUNK_BYTES, encryptedPayload.size)
                val chunk = encryptedPayload.copyOfRange(start, end)
                try {
                    val encoded = Base64.encodeToString(chunk, Base64.NO_WRAP)
                    transaction.child("chunks").child(index.toString()).setValue(encoded).await()
                } finally {
                    chunk.fill(0)
                }
            }
            // This marker is written last. Readers ignore partial uploads.
            transaction.child("ready").setValue(true).await()
        } catch (failure: Throwable) {
            // Best-effort removal of incomplete ciphertext; never masks the original error.
            runCatching { transaction.removeValue().await() }
            throw failure
        }
    }

    /**
     * Returns ciphertext only after all chunks and metadata have been checked.
     * A null result means the source has not finished uploading yet.
     */
    override suspend fun download(
        targetDeviceId: String,
        transactionId: String,
    ): DeviceTransferRelayPayload? {
        val uid = requireUid()
        require(DEVICE_ID_PATTERN.matches(targetDeviceId))
        require(TRANSACTION_PATTERN.matches(transactionId))
        val snapshot = database.reference.child(INBOX_NODE).child(uid)
            .child(targetDeviceId).child(transactionId).get().await()
        if (!snapshot.child("ready").getValue(Boolean::class.java).orFalse()) return null

        val metadata = parseMetadata(snapshot.child("metadata"), targetDeviceId, transactionId)
        val now = clock().coerceAtLeast(0L)
        require(metadata.expiresAtMillis > now) { "Device transfer relay has expired" }
        require(metadata.createdAtMillis <= now + MAX_FUTURE_SKEW_MILLIS) {
            "Device transfer relay timestamp is invalid"
        }

        val chunks = snapshot.child("chunks").children.associate { child ->
            requireNotNull(child.key).toIntOrNull() to requireNotNull(
                child.getValue(String::class.java)
            )
        }
        require(chunks.size == metadata.chunkCount && chunks.keys.all { it != null }) {
            "Device transfer relay is missing chunks"
        }
        val output = java.io.ByteArrayOutputStream(metadata.payloadSize)
        for (index in 0 until metadata.chunkCount) {
            val encoded = requireNotNull(chunks[index]) { "Device transfer relay is missing a chunk" }
            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            require(decoded.size in 1..CHUNK_BYTES) { "Device transfer chunk has an invalid size" }
            output.write(decoded)
            decoded.fill(0)
        }
        val payload = output.toByteArray()
        require(payload.size == metadata.payloadSize && payload.size <= MAX_PAYLOAD_BYTES) {
            payload.fill(0)
            "Device transfer payload size does not match its metadata"
        }
        val actualHash = sha256Hex(payload)
        val expected = metadata.payloadSha256.toByteArray(Charsets.US_ASCII)
        val actual = actualHash.toByteArray(Charsets.US_ASCII)
        require(MessageDigest.isEqual(expected, actual)) {
            payload.fill(0)
            "Device transfer payload integrity check failed"
        }
        return DeviceTransferRelayPayload(
            sourceDeviceId = metadata.sourceDeviceId,
            targetDeviceId = metadata.targetDeviceId,
            transactionId = metadata.transactionId,
            encryptedPayload = payload,
        )
    }


    override suspend fun publishAcknowledgement(
        targetDeviceId: String,
        transactionId: String,
        acknowledgement: ByteArray,
    ) {
        val uid = requireUid()
        require(DEVICE_ID_PATTERN.matches(targetDeviceId))
        require(TRANSACTION_PATTERN.matches(transactionId))
        require(acknowledgement.size in 32..256)
        val reference = database.reference.child(INBOX_NODE).child(uid)
            .child(targetDeviceId).child(transactionId)
        val snapshot = reference.get().await()
        require(snapshot.child("ready").getValue(Boolean::class.java) == true) {
            "Cannot acknowledge a transfer that is not ready"
        }
        require(snapshot.child("metadata").child("expiresAtMillis").getValue(Long::class.java) ?: 0L > clock()) {
            "Device transfer relay has expired"
        }
        val encoded = Base64.encodeToString(acknowledgement, Base64.NO_WRAP)
        reference.child("acknowledgement").setValue(encoded).await()
    }

    override suspend fun readAcknowledgement(
        targetDeviceId: String,
        transactionId: String,
    ): ByteArray? {
        val uid = requireUid()
        require(DEVICE_ID_PATTERN.matches(targetDeviceId))
        require(TRANSACTION_PATTERN.matches(transactionId))
        val reference = database.reference.child(INBOX_NODE).child(uid)
            .child(targetDeviceId).child(transactionId)
        val snapshot = reference.get().await()
        if (!snapshot.exists()) return null
        val expires = (snapshot.child("metadata").child("expiresAtMillis").value as? Number)?.toLong()
            ?: return null
        if (expires <= clock().coerceAtLeast(0L)) return null
        val encoded = snapshot.child("acknowledgement").getValue(String::class.java) ?: return null
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size in 32..256) {
            bytes.fill(0)
            "Device transfer acknowledgement has an invalid size"
        }
        return bytes
    }

    override suspend fun deleteCompleted(targetDeviceId: String, transactionId: String) {
        val uid = requireUid()
        require(DEVICE_ID_PATTERN.matches(targetDeviceId))
        require(TRANSACTION_PATTERN.matches(transactionId))
        val reference = database.reference.child(INBOX_NODE).child(uid)
            .child(targetDeviceId).child(transactionId)
        val snapshot = reference.get().await()
        if (!snapshot.exists()) return
        require(snapshot.child("acknowledgement").exists()) {
            "Cannot remove a transfer before its acknowledgement is available"
        }
        reference.removeValue().await()
    }

    /** Remove expired records for this account/device without requiring a server function. */
    suspend fun pruneExpired(targetDeviceId: String): Int {
        val uid = requireUid()
        require(DEVICE_ID_PATTERN.matches(targetDeviceId))
        val inbox = database.reference.child(INBOX_NODE).child(uid).child(targetDeviceId)
        val snapshot = inbox.get().await()
        val now = clock().coerceAtLeast(0L)
        var removed = 0
        for (record in snapshot.children) {
            val transactionId = record.key ?: continue
            val expired = runCatching {
                parseMetadata(record.child("metadata"), targetDeviceId, transactionId)
                    .expiresAtMillis <= now
            }.getOrDefault(false)
            if (expired) {
                record.ref.removeValue().await()
                removed++
            }
        }
        return removed
    }

    /** Remove the relay only after the destination has successfully imported it. */
    suspend fun acknowledgeImported(targetDeviceId: String, transactionId: String) {
        val uid = requireUid()
        require(DEVICE_ID_PATTERN.matches(targetDeviceId))
        require(TRANSACTION_PATTERN.matches(transactionId))
        val reference = database.reference.child(INBOX_NODE).child(uid)
            .child(targetDeviceId).child(transactionId)
        val snapshot = reference.get().await()
        if (!snapshot.exists()) return
        val metadata = parseMetadata(snapshot.child("metadata"), targetDeviceId, transactionId)
        require(snapshot.child("ready").getValue(Boolean::class.java) == true) {
            "Cannot acknowledge an incomplete device transfer"
        }
        require(metadata.expiresAtMillis > clock().coerceAtLeast(0L)) {
            "Device transfer relay has expired"
        }
        reference.removeValue().await()
    }

    private fun parseMetadata(
        snapshot: DataSnapshot,
        targetDeviceId: String,
        transactionId: String,
    ): Metadata {
        require(snapshot.exists())
        val version = requireNotNull((snapshot.child("protocolVersion").value as? Number)?.toInt())
        val source = requireNotNull(snapshot.child("sourceDeviceId").getValue(String::class.java))
        val target = requireNotNull(snapshot.child("targetDeviceId").getValue(String::class.java))
        val transaction = requireNotNull(snapshot.child("transactionId").getValue(String::class.java))
        val created = requireNotNull((snapshot.child("createdAtMillis").value as? Number)?.toLong())
        val expires = requireNotNull((snapshot.child("expiresAtMillis").value as? Number)?.toLong())
        val size = requireNotNull((snapshot.child("payloadSize").value as? Number)?.toInt())
        val count = requireNotNull((snapshot.child("chunkCount").value as? Number)?.toInt())
        val hash = requireNotNull(snapshot.child("payloadSha256").getValue(String::class.java))
        validateIds(source, target, transaction)
        require(version == PROTOCOL_VERSION && target == targetDeviceId && transaction == transactionId)
        require(source != target && size in 1..MAX_PAYLOAD_BYTES)
        require(count == (size + CHUNK_BYTES - 1) / CHUNK_BYTES && count in 1..MAX_CHUNKS)
        require(expires > created && expires - created <= TRANSFER_LIFETIME_MILLIS)
        require(hash.matches(HASH_PATTERN))
        return Metadata(source, target, transaction, created, expires, size, count, hash)
    }

    private fun requireUid(): String = requireNotNull(auth.currentUser?.uid) {
        "Sign in to the same InChat account on both devices"
    }

    private fun validateIds(source: String, target: String, transaction: String) {
        require(DEVICE_ID_PATTERN.matches(source) && DEVICE_ID_PATTERN.matches(target)) {
            "Device ID has an invalid format"
        }
        require(TRANSACTION_PATTERN.matches(transaction)) { "Transfer transaction ID is invalid" }
    }

    private data class Metadata(
        val sourceDeviceId: String,
        val targetDeviceId: String,
        val transactionId: String,
        val createdAtMillis: Long,
        val expiresAtMillis: Long,
        val payloadSize: Int,
        val chunkCount: Int,
        val payloadSha256: String,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun Boolean?.orFalse() = this == true

    private companion object {
        const val INBOX_NODE = "deviceTransferInbox"
        const val DEVICE_KEYS_NODE = "deviceKeys"
        const val PROTOCOL_VERSION = 1
        const val CHUNK_BYTES = 128 * 1024
        const val MAX_PAYLOAD_BYTES = 9 * 1024 * 1024
        const val MAX_CHUNKS = (MAX_PAYLOAD_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES
        const val TRANSFER_LIFETIME_MILLIS = 2 * 60 * 1000L
        const val MAX_FUTURE_SKEW_MILLIS = 2 * 60 * 1000L
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val TRANSACTION_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val HASH_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}
