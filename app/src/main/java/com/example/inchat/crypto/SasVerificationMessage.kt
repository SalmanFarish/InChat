package com.example.inchat.crypto

import org.json.JSONObject
import java.security.MessageDigest

internal enum class SasVerificationMessageType(val wireValue: String) {
    COMMITMENT("commitment"),
    PUBLIC_KEY("public_key"),
    SAS_MAC("sas_mac"),
    CANCEL("cancel");

    companion object {
        fun fromWire(value: String): SasVerificationMessageType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported SAS verification message type")
    }
}

internal data class SasVerificationMessage(
    val transactionId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val type: SasVerificationMessageType,
    val payload: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    val deliveryId: String
        get() = SasVerificationMessageCodec.deliveryId(this)
}

/**
 * Strict, canonical, short-lived wire format for the SAS exchange.
 *
 * The relay sees only SAS public protocol material. It is not an identity trust
 * anchor: the native SAS MAC exchange authenticates the device identity keys.
 */
internal object SasVerificationMessageCodec {
    const val MAX_LIFETIME_MILLIS = 10 * 60 * 1000L
    const val MAX_FUTURE_SKEW_MILLIS = 2 * 60 * 1000L
    const val MAX_ENCODED_BYTES = 8 * 1024
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    private val COMMITMENT_PATTERN = Regex("[A-Fa-f0-9]{64}")
    private val BASE64_PATTERN = Regex("[A-Za-z0-9_+/=-]{1,256}")

    fun encode(message: SasVerificationMessage): String {
        validate(message, message.createdAtMillis, checkWindow = false)
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("transactionId", message.transactionId)
            .put("senderUserId", message.senderUserId)
            .put("senderDeviceId", message.senderDeviceId)
            .put("recipientUserId", message.recipientUserId)
            .put("recipientDeviceId", message.recipientDeviceId)
            .put("type", message.type.wireValue)
            .put("payload", message.payload)
            .put("createdAtMillis", message.createdAtMillis)
            .put("expiresAtMillis", message.expiresAtMillis)
        return json.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
                "SAS verification message is too large"
            }
        }
    }

    fun decode(
        encoded: String,
        expectedRecipientUserId: String,
        expectedRecipientDeviceId: String,
        nowMillis: Long,
    ): SasVerificationMessage {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "SAS verification message is too large"
        }
        val json = JSONObject(encoded)
        val allowed = setOf(
            "schemaVersion", "transactionId", "senderUserId", "senderDeviceId",
            "recipientUserId", "recipientDeviceId", "type", "payload",
            "createdAtMillis", "expiresAtMillis",
        )
        require(json.keys().asSequence().toSet() == allowed) {
            "SAS verification message has missing or unknown fields"
        }
        require(json.get("schemaVersion") is Int && json.getInt("schemaVersion") == 1) {
            "Unsupported SAS verification message version"
        }
        require(json.get("createdAtMillis") is Long || json.get("createdAtMillis") is Int) {
            "SAS creation time must be an integer"
        }
        require(json.get("expiresAtMillis") is Long || json.get("expiresAtMillis") is Int) {
            "SAS expiry time must be an integer"
        }
        val message = SasVerificationMessage(
            transactionId = json.getString("transactionId"),
            senderUserId = json.getString("senderUserId"),
            senderDeviceId = json.getString("senderDeviceId"),
            recipientUserId = json.getString("recipientUserId"),
            recipientDeviceId = json.getString("recipientDeviceId"),
            type = SasVerificationMessageType.fromWire(json.getString("type")),
            payload = json.getString("payload"),
            createdAtMillis = json.getLong("createdAtMillis"),
            expiresAtMillis = json.getLong("expiresAtMillis"),
        )
        validate(message, nowMillis, checkWindow = true)
        require(message.recipientUserId == expectedRecipientUserId &&
            message.recipientDeviceId == expectedRecipientDeviceId) {
            "SAS verification message is addressed to another device"
        }
        require(encode(message) == encoded) {
            "SAS verification message is not canonical JSON"
        }
        return message
    }

    internal fun deliveryId(message: SasVerificationMessage): String {
        val identity = listOf(
            message.transactionId, message.senderUserId, message.senderDeviceId,
            message.recipientUserId, message.recipientDeviceId, message.type.wireValue,
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun validate(message: SasVerificationMessage, nowMillis: Long, checkWindow: Boolean) {
        require(ID_PATTERN.matches(message.transactionId)) { "SAS transaction ID is invalid" }
        require(ID_PATTERN.matches(message.senderUserId) && ID_PATTERN.matches(message.recipientUserId)) {
            "SAS user ID is invalid"
        }
        require(DEVICE_PATTERN.matches(message.senderDeviceId) && DEVICE_PATTERN.matches(message.recipientDeviceId)) {
            "SAS device ID is invalid"
        }
        require(message.senderUserId != message.recipientUserId ||
            message.senderDeviceId != message.recipientDeviceId) {
            "A device cannot verify itself"
        }
        require(message.createdAtMillis >= 0 && message.expiresAtMillis > message.createdAtMillis) {
            "SAS verification time window is invalid"
        }
        require(message.expiresAtMillis - message.createdAtMillis <= MAX_LIFETIME_MILLIS) {
            "SAS verification message lifetime is too long"
        }
        if (checkWindow) {
            require(nowMillis >= 0 && message.createdAtMillis <= nowMillis + MAX_FUTURE_SKEW_MILLIS) {
                "SAS verification message is dated in the future"
            }
            require(message.expiresAtMillis > nowMillis) { "SAS verification message has expired" }
        }
        when (message.type) {
            SasVerificationMessageType.COMMITMENT ->
                require(COMMITMENT_PATTERN.matches(message.payload)) { "SAS commitment is malformed" }
            SasVerificationMessageType.PUBLIC_KEY,
            SasVerificationMessageType.SAS_MAC ->
                require(BASE64_PATTERN.matches(message.payload)) { "SAS public key or MAC is malformed" }
            SasVerificationMessageType.CANCEL ->
                require(message.payload.isEmpty()) { "SAS cancellation must not contain a payload" }
        }
    }
}
