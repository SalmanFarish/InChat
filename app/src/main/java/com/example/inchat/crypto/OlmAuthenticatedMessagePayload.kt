package com.example.inchat.crypto

import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Application metadata carried INSIDE the authenticated Olm plaintext.
 * Outer Firebase/envelope routing fields are never treated as message identity.
 *
 * Version 2 supports direct text messages and authenticates the existing direct
 * conversation ID. Group routing and other message types require separate schemas.
 */
internal data class OlmAuthenticatedMessagePayload(
    val messageId: String,
    val conversationId: String,
    val conversationPeerUserId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val sentAtMillis: Long,
    val text: String,
)

internal object OlmAuthenticatedMessagePayloadCodec {
    private const val SCHEMA_VERSION = 2
    private const val MESSAGE_TYPE = "text"
    private const val MAX_PAYLOAD_BYTES = 96 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024
    private val USER_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val MESSAGE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val CONVERSATION_ID = Regex("[A-Za-z0-9_-]{1,257}")
    private val FIELDS = setOf(
        "schemaVersion", "messageType", "messageId", "conversationId",
        "conversationPeerUserId", "senderUserId", "senderDeviceId",
        "recipientUserId", "recipientDeviceId", "sentAtMillis", "text",
    )

    /**
     * Matches ChatRepository.getChatRoomId(): direct chat IDs are the two
     * participant UIDs in lexicographical order separated by one underscore.
     */
    fun directConversationId(firstUserId: String, secondUserId: String): String {
        require(USER_ID.matches(firstUserId)) { "First conversation participant has an invalid user ID" }
        require(USER_ID.matches(secondUserId)) { "Second conversation participant has an invalid user ID" }
        require(firstUserId != secondUserId) { "A direct conversation requires two different accounts" }
        return if (firstUserId < secondUserId) {
            "${firstUserId}_${secondUserId}"
        } else {
            "${secondUserId}_${firstUserId}"
        }
    }

    fun encode(payload: OlmAuthenticatedMessagePayload): String {
        validate(payload)
        val encoded = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("messageType", MESSAGE_TYPE)
            .put("messageId", payload.messageId)
            .put("conversationId", payload.conversationId)
            .put("conversationPeerUserId", payload.conversationPeerUserId)
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
        val version = json.opt("schemaVersion")
        require(version is Number && version.toDouble() == SCHEMA_VERSION.toDouble()) {
            "Unsupported authenticated message payload version"
        }
        require(json.opt("messageType") == MESSAGE_TYPE) {
            "Unsupported authenticated message type"
        }
        fun stringField(name: String): String {
            val value = json.opt(name)
            require(value is String) { "Authenticated payload field '$name' must be text" }
            return value
        }
        val rawTimestamp = json.opt("sentAtMillis")
        // Accept only integral JSON number representations. Converting a Double
        // through toLong() can silently round large attacker-controlled values.
        require(rawTimestamp is Byte || rawTimestamp is Short ||
            rawTimestamp is Int || rawTimestamp is Long) {
            "Authenticated message timestamp must be an integer"
        }
        val timestamp = (rawTimestamp as Number).toLong()
        val payload = OlmAuthenticatedMessagePayload(
            messageId = stringField("messageId"),
            conversationId = stringField("conversationId"),
            conversationPeerUserId = stringField("conversationPeerUserId"),
            senderUserId = stringField("senderUserId"),
            senderDeviceId = stringField("senderDeviceId"),
            recipientUserId = stringField("recipientUserId"),
            recipientDeviceId = stringField("recipientDeviceId"),
            sentAtMillis = timestamp,
            text = stringField("text"),
        )
        validate(payload)
        require(encode(payload) == plaintext) {
            "Authenticated message payload is not in canonical form"
        }
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
        require(USER_ID.matches(payload.conversationPeerUserId)) {
            "Authenticated conversation peer user ID has an invalid format"
        }
        require(payload.senderUserId != payload.conversationPeerUserId) {
            "A direct conversation requires two different accounts"
        }
        require(CONVERSATION_ID.matches(payload.conversationId)) {
            "Authenticated conversation ID has an invalid format"
        }
        require(
            payload.conversationId == directConversationId(
                payload.senderUserId,
                payload.conversationPeerUserId,
            )
        ) {
            "Authenticated conversation ID does not match its participants"
        }
        require(USER_ID.matches(payload.recipientUserId)) { "Authenticated recipient user ID has an invalid format" }
        require(DEVICE_ID.matches(payload.recipientDeviceId)) { "Authenticated recipient device ID has an invalid format" }
        require(
            payload.recipientUserId == payload.senderUserId ||
                payload.recipientUserId == payload.conversationPeerUserId
        ) {
            "Authenticated delivery recipient is not a participant in the conversation"
        }
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
