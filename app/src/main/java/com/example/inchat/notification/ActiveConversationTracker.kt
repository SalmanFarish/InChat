package com.example.inchat.notification

/**
 * Tracks which conversation is visible so foreground message pushes
 * do not interrupt the conversation the user is already reading.
 */
object ActiveConversationTracker {
    @Volatile
    private var appInForeground: Boolean = false

    @Volatile
    private var activeChatId: String? = null

    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    fun setActiveChat(chatId: String) {
        activeChatId = chatId
    }

    fun clearActiveChat(chatId: String) {
        if (activeChatId == chatId) {
            activeChatId = null
        }
    }

    fun shouldSuppressNotification(chatId: String): Boolean {
        return appInForeground &&
            chatId.isNotBlank() &&
            activeChatId == chatId
    }
}
