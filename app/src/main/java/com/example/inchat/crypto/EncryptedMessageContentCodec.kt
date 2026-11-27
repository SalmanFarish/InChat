package com.example.inchat.crypto

import com.example.inchat.data.model.ReplyTo
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Reply metadata is serialized inside the existing end-to-end encrypted body. */
internal data class DecodedEncryptedMessageContent(
    val text: String,
    val replyTo: ReplyTo?,
)

internal object EncryptedMessageContentCodec {
    private const val PREFIX = "inchat.e2ee.content.v1:"
    private const val SCHEMA_VERSION = 1
    private const val MAX_CONTENT_BYTES = 64 * 1024
    private const val MAX_QUOTED_TEXT_CHARS = 4096
    private const val MAX_NICKNAME_CHARS = 128
    private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val FIELDS = setOf("schemaVersion", "type", "text", "replyTo")
    private val REPLY_FIELDS = setOf("messageId", "senderId", "senderNickname", "text")

    fun encode(
        text: String,
        replyTo: ReplyTo?,
        currentUserId: String,
        currentUserNickname: String,
    ): String {
        require(text.isNotBlank()) { "Encrypted message text cannot be empty" }
        if (replyTo == null) return text
        require(ID.matches(currentUserId)) { "Reply sender account ID is invalid" }
        require(ID.matches(replyTo.messageId)) { "Reply target message ID is invalid" }
        require(ID.matches(replyTo.senderId)) { "Reply target sender ID is invalid" }
        require(replyTo.text.isNotBlank() && replyTo.text.length <= MAX_QUOTED_TEXT_CHARS) {
            "Reply quote is empty or too long"
        }
        val nickname = (if (replyTo.senderId == currentUserId) {
            currentUserNickname
        } else {
            replyTo.senderNickname
        }).trim()
        require(nickname.isNotBlank() && nickname.length <= MAX_NICKNAME_CHARS) {
            "Reply target nickname is invalid"
        }
        val reply = JSONObject()
            .put("messageId", replyTo.messageId)
            .put("senderId", replyTo.senderId)
            .put("senderNickname", nickname)
            .put("text", replyTo.text)
        val encoded = PREFIX + JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("type", "text_with_reply")
            .put("text", text)
            .put("replyTo", reply)
            .toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_CONTENT_BYTES) {
            "Encrypted message content exceeds the size limit"
        }
        return encoded
    }

    fun decode(content: String): DecodedEncryptedMessageContent {
        if (!content.startsWith(PREFIX)) return DecodedEncryptedMessageContent(content, null)
        if (content.toByteArray(StandardCharsets.UTF_8).size > MAX_CONTENT_BYTES) {
            return DecodedEncryptedMessageContent(content, null)
        }
        return try {
            val json = JSONObject(content.removePrefix(PREFIX))
            require(json.keys().asSequence().toSet() == FIELDS) {
                "Encrypted message content has missing or unknown fields"
            }
            require(json.opt("schemaVersion") == SCHEMA_VERSION) {
                "Unsupported encrypted message content version"
            }
            require(json.opt("type") == "text_with_reply") {
                "Unsupported encrypted message content type"
            }
            val text = json.opt("text") as? String
                ?: throw IllegalArgumentException("Encrypted message text is invalid")
            val replyJson = json.optJSONObject("replyTo")
                ?: throw IllegalArgumentException("Encrypted reply metadata is missing")
            require(replyJson.keys().asSequence().toSet() == REPLY_FIELDS) {
                "Encrypted reply metadata has missing or unknown fields"
            }
            fun stringField(name: String): String = replyJson.opt(name) as? String
                ?: throw IllegalArgumentException("Encrypted reply field '$name' is invalid")
            val reply = ReplyTo(
                messageId = stringField("messageId"),
                senderId = stringField("senderId"),
                senderNickname = stringField("senderNickname"),
                text = stringField("text"),
            )
            require(text.isNotBlank()) { "Encrypted message text cannot be empty" }
            require(ID.matches(reply.messageId) && ID.matches(reply.senderId)) {
                "Encrypted reply identity is invalid"
            }
            require(reply.senderNickname.isNotBlank() && reply.senderNickname.length <= MAX_NICKNAME_CHARS) {
                "Encrypted reply nickname is invalid"
            }
            require(reply.text.isNotBlank() && reply.text.length <= MAX_QUOTED_TEXT_CHARS) {
                "Encrypted reply quote is invalid"
            }
            DecodedEncryptedMessageContent(text, reply)
        } catch (_: Exception) {
            // A malformed marker must not crash the whole conversation timeline.
            DecodedEncryptedMessageContent(content, null)
        }
    }
}
