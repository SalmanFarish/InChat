package com.example.inchat.notification

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Converts FCM data into Android notification presentation.
 *
 * Encrypted message pushes are metadata-only signals. Their sender/group labels,
 * message text, and even an accidental FCM notification title/body are ignored.
 * The conversation identifiers are retained only for tap navigation and active
 * conversation suppression.
 */
internal data class NotificationPresentation(
    val title: String,
    val body: String,
    val senderName: String,
    val messageText: String,
    val notificationType: String,
    val chatId: String,
    val senderId: String,
    val messageId: String,
    val includeMessageHistory: Boolean,
)

internal object NotificationPayloadPolicy {
    const val TYPE_CHAT = "chat_message"
    const val TYPE_GROUP = "group_message"
    const val TYPE_ENCRYPTED_CHAT = "encrypted_message"
    const val TYPE_ENCRYPTED_GROUP = "encrypted_group_message"
    const val TYPE_ENCRYPTED_OLM_WAKEUP = "encrypted_olm"

    const val ROUTE_CHAT = "chat_message"
    const val ROUTE_GROUP = "group_message"

    /**
     * Derives a repeatable per-conversation Android notification ID without
     * relying on collision-prone String.hashCode(). The digest keeps IDs stable
     * across process restarts and devices; reserve the fallback ID for invalid
     * or missing conversation identifiers.
     */
    fun notificationIdForChat(
        chatId: String,
        fallbackId: Int,
        recipientUserId: String = "",
    ): Int {
        if (chatId.isBlank()) return fallbackId
        val digest = accountConversationDigest(chatId, recipientUserId)
        val candidate = ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int and Int.MAX_VALUE
        return candidate.takeIf { it != 0 && it != fallbackId } ?: (fallbackId + 1)
    }

    /**
     * Full-length account/conversation identity used as the Android notification
     * tag and PendingIntent data. The 31-bit notification ID alone is not unique.
     */
    fun notificationTagForChat(chatId: String, recipientUserId: String): String =
        digestHex(accountConversationDigest(chatId, recipientUserId))

    /**
     * Returns an opaque, account-scoped SharedPreferences key. Hashing avoids
     * putting account and conversation identifiers into preference key names.
     */
    fun notificationHistoryKey(recipientUserId: String, chatId: String): String =
        "messages_" + notificationTagForChat(chatId, recipientUserId)

    private fun accountConversationDigest(chatId: String, recipientUserId: String): ByteArray {
        val accountId = recipientUserId.trim()
        val conversationId = chatId.trim()
        val identity = accountId.length.toString() + ":" + accountId + conversationId
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
    }

    private fun digestHex(digest: ByteArray): String =
        digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private const val GENERIC_TITLE = "InChat"
    private const val GENERIC_ENCRYPTED_BODY = "New encrypted message"

    /**
     * Verifies an explicitly addressed FCM message against the current account.
     * Only signed-in recipients may receive legacy large-group topic messages.
     */
    fun isIntendedForAccount(data: Map<String, String>, currentUserId: String): Boolean {
        val signedInUserId = currentUserId.trim()
        if (signedInUserId.isBlank()) return false

        if ("recipientUserId" in data) {
            val targetUserId = data["recipientUserId"]?.trim().orEmpty()
            return targetUserId.isNotBlank() && targetUserId == signedInUserId
        }

        // The only supported unaddressed notification is the legacy large-group
        // topic broadcast. Direct messages must always identify their recipient.
        return data["type"] == TYPE_GROUP
    }

    /**
     * Identifies the reserved encrypted wake-up type before validating its
     * fields. Malformed reserved payloads must never fall through to plaintext.
     */
    fun isEncryptedOlmWakeupType(data: Map<String, String>): Boolean =
        data["type"] == TYPE_ENCRYPTED_OLM_WAKEUP

    /**
     * Encrypted Olm FCM carries a wake-up signal, not notification content or
     * routing data. Accept only the two fields emitted by the trusted Worker.
     */
    fun isEncryptedOlmWakeup(data: Map<String, String>): Boolean =
        data.keys == setOf("type", "deliveryId") &&
            data["type"] == TYPE_ENCRYPTED_OLM_WAKEUP &&
            data["deliveryId"]?.matches(Regex("[A-Fa-f0-9]{64}")) == true

    fun from(
        data: Map<String, String>,
        fallbackTitle: String?,
        fallbackBody: String?,
    ): NotificationPresentation {
        val type = data["type"]
        val encryptedGroup = type == TYPE_ENCRYPTED_GROUP
        val encryptedDirect = type == TYPE_ENCRYPTED_CHAT
        val chatId = data["chatId"]?.trim().orEmpty()
        val senderId = data["senderId"]?.trim().orEmpty()
        val messageId = data["messageId"]?.trim().orEmpty()

        if (encryptedDirect || encryptedGroup) {
            // Do not read senderName, groupName, message, or FCM notification
            // title/body in this branch. These values are outside the E2EE trust
            // boundary and must never enter Android's notification/history UI.
            return NotificationPresentation(
                title = GENERIC_TITLE,
                body = GENERIC_ENCRYPTED_BODY,
                senderName = GENERIC_TITLE,
                messageText = GENERIC_ENCRYPTED_BODY,
                notificationType = if (encryptedGroup) ROUTE_GROUP else ROUTE_CHAT,
                chatId = chatId,
                senderId = senderId,
                messageId = messageId,
                includeMessageHistory = false,
            )
        }

        // Preserve the established plaintext notification behavior.
        val senderName =
            data["senderName"]
                ?.trim()
                ?.removePrefix("@")
                ?.takeIf { it.isNotBlank() }
                ?: fallbackTitle
                    ?.removePrefix("@")
                    ?.takeIf { it.isNotBlank() }
                ?: GENERIC_TITLE

        val messageText =
            data["message"]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackBody
                    ?.takeIf { it.isNotBlank() }
                ?: "You received a new message"

        val isGroup = type == TYPE_GROUP
        val groupName =
            data["groupName"]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Group"

        return NotificationPresentation(
            title = if (isGroup) groupName else "@$senderName",
            body = if (isGroup) "$senderName: $messageText" else messageText,
            senderName = senderName,
            messageText = messageText,
            notificationType = if (isGroup) ROUTE_GROUP else ROUTE_CHAT,
            chatId = chatId,
            senderId = senderId,
            messageId = messageId,
            includeMessageHistory = true,
        )
    }
}
