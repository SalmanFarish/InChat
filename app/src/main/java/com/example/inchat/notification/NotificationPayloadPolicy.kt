package com.example.inchat.notification

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

    private const val GENERIC_TITLE = "InChat"
    private const val GENERIC_ENCRYPTED_BODY = "New encrypted message"

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
