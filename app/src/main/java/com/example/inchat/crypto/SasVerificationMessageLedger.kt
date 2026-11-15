package com.example.inchat.crypto

import java.security.MessageDigest

internal enum class SasMessageRegistration { NEW, DUPLICATE, CONFLICT }

/**
 * Persists message identities before a transport acknowledges their removal.
 * Replayed messages remain rejected after process death and database reopen.
 */
internal class SasVerificationMessageLedger(
    private val dao: SasVerificationMessageDao,
    private val localUserId: String,
    private val localDeviceId: String,
) {
    init {
        require(USER_PATTERN.matches(localUserId))
        require(DEVICE_PATTERN.matches(localDeviceId))
    }

    fun register(message: SasVerificationMessage, nowMillis: Long): SasMessageRegistration {
        require(nowMillis >= 0)
        require(message.recipientUserId == localUserId && message.recipientDeviceId == localDeviceId) {
            "SAS message is not addressed to this local device"
        }
        require(message.expiresAtMillis > nowMillis) { "SAS message has expired" }
        val digest = sha256(message.payload)
        val row = SasVerificationMessageEntity(
            deliveryId = message.deliveryId,
            transactionId = message.transactionId,
            senderUserId = message.senderUserId,
            senderDeviceId = message.senderDeviceId,
            recipientUserId = message.recipientUserId,
            recipientDeviceId = message.recipientDeviceId,
            type = message.type.wireValue,
            payloadSha256 = digest,
            expiresAtMillis = message.expiresAtMillis,
            receivedAtMillis = nowMillis,
        )
        val inserted = dao.insertIfAbsent(row)
        val stored = dao.getByDeliveryId(message.deliveryId)
            ?: error("SAS replay record could not be read after insertion")
        if (stored.payloadSha256 != digest || stored.expiresAtMillis != message.expiresAtMillis) {
            return SasMessageRegistration.CONFLICT
        }
        return if (inserted == -1L) SasMessageRegistration.DUPLICATE else SasMessageRegistration.NEW
    }

    fun pruneExpired(nowMillis: Long): Int {
        require(nowMillis >= 0)
        return dao.deleteExpired(nowMillis)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val USER_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
