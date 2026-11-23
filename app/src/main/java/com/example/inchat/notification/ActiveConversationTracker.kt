package com.example.inchat.notification

/**
 * Tracks which account/conversation is visible so a foreground push cannot
 * suppress another account's notification merely because its chat ID matches.
 */
object ActiveConversationTracker {
    private data class ActiveConversation(
        val chatId: String,
        val recipientUserId: String,
    )

    @Volatile
    private var appInForeground: Boolean = false

    @Volatile
    private var activeConversation: ActiveConversation? = null

    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    fun setActiveChat(chatId: String, recipientUserId: String) {
        if (chatId.isBlank() || recipientUserId.isBlank()) return
        activeConversation = ActiveConversation(chatId, recipientUserId)
    }

    fun clearActiveChat(chatId: String, recipientUserId: String) {
        val expected = ActiveConversation(chatId, recipientUserId)
        if (activeConversation == expected) {
            activeConversation = null
        }
    }

    fun shouldSuppressNotification(chatId: String, recipientUserId: String): Boolean {
        if (!appInForeground || chatId.isBlank() || recipientUserId.isBlank()) return false
        return activeConversation == ActiveConversation(chatId, recipientUserId)
    }
}
