package com.example.inchat.crypto

import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Application metadata carried INSIDE the authenticated Olm plaintext.
 * Outer Firebase/envelope routing fields are never treated as message identity.
 *
 * Version 1 intentionally supports text direct messages only. Attachments, edits,
 * reactions, and group routing require separately specified authenticated schemas.
 */
internal data class OlmAuthenticatedMessagePayload(
    val messageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val sentAtMillis: Long,
    val text: String,
)

internal object OlmAuthenticatedMessagePayloadCodec {
    private const val SCHEMA_VERSION = 1
    private const val MESSAGE_TYPE = "text"
    private const val MAX_PAYLOAD_BYTES = 96 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024
    private val USER_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val MESSAGE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val FIELDS = setOf(
        "schemaVersion", "messageType", "messageId", "senderUserId",
        "senderDeviceId", "recipientUserId", "recipientDeviceId",
        "sentAtMillis", "text",
    )

    fun encode(payload: OlmAuthenticatedMessagePayload): String {
        validate(payload)
        val encoded = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("messageType", MESSAGE_TYPE)
            .put("messageId", payload.messageId)
            .put("senderUserId", payload.senderUserId)
            .put("senderDeviceId", payload.senderDeviceId)
            .put("recipientUserId", payload.recipientUserId)
            .put("recipientDeviceId", payload.recipientDeviceId)
            .put("sentAtMillis", payload.sentAtMillis)
            .put("text", payload.text)
            .toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Authenticated message payload exceeds the size limit"
        }
        return encoded
    }

    fun decodeAndValidate(
        plaintext: String,
        expectedMessageId: String,
        expectedSenderUserId: String,
        expectedSenderDeviceId: String,
        expectedRecipientUserId: String,
        expectedRecipientDeviceId: String,
    ): OlmAuthenticatedMessagePayload {
        require(plaintext.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Authenticated message payload exceeds the size limit"
        }
        val json = try {
            JSONObject(plaintext)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Decrypted message is not a valid authenticated payload", error)
        }
        val actualFields = json.keys().asSequence().toSet()
        require(actualFields == FIELDS) { "Authenticated message payload has missing or unknown fields" }
        require(json.getInt("schemaVersion") == SCHEMA_VERSION) {
            "Unsupported authenticated message payload version"
        }
        require(json.getString("messageType") == MESSAGE_TYPE) {
            "Unsupported authenticated message type"
        }
        val payload = OlmAuthenticatedMessagePayload(
            messageId = json.getString("messageId"),
            senderUserId = json.getString("senderUserId"),
            senderDeviceId = json.getString("senderDeviceId"),
            recipientUserId = json.getString("recipientUserId"),
            recipientDeviceId = json.getString("recipientDeviceId"),
            sentAtMillis = json.getLong("sentAtMillis"),
            text = json.getString("text"),
        )
        validate(payload)
        require(payload.messageId == expectedMessageId) { "Authenticated message ID does not match its receipt" }
        require(payload.senderUserId == expectedSenderUserId) { "Authenticated sender account does not match its receipt" }
        require(payload.senderDeviceId == expectedSenderDeviceId) { "Authenticated sender device does not match its receipt" }
        require(payload.recipientUserId == expectedRecipientUserId) { "Authenticated recipient account does not match the local account" }
        require(payload.recipientDeviceId == expectedRecipientDeviceId) { "Authenticated recipient device does not match its receipt" }
        return payload
    }

    private fun validate(payload: OlmAuthenticatedMessagePayload) {
        require(MESSAGE_ID.matches(payload.messageId)) { "Authenticated message ID has an invalid format" }
        require(USER_ID.matches(payload.senderUserId)) { "Authenticated sender user ID has an invalid format" }
        require(DEVICE_ID.matches(payload.senderDeviceId)) { "Authenticated sender device ID has an invalid format" }
        require(USER_ID.matches(payload.recipientUserId)) { "Authenticated recipient user ID has an invalid format" }
        require(DEVICE_ID.matches(payload.recipientDeviceId)) { "Authenticated recipient device ID has an invalid format" }
        require(payload.senderDeviceId != payload.recipientDeviceId || payload.senderUserId != payload.recipientUserId) {
            "A device cannot send an Olm message to itself"
        }
        require(payload.sentAtMillis >= 0) { "Authenticated message timestamp cannot be negative" }
        require(payload.text.isNotEmpty()) { "Authenticated text message cannot be empty" }
        require(payload.text.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_BYTES) {
            "Authenticated message text exceeds the size limit"
        }
    }
}
