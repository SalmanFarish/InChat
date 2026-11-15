package com.example.inchat.data.model

data class Conversation(
    val chatId: String = "",
    val otherUserId: String = "",
    val otherUsername: String = "",
    val lastMessage: String = "",
    val lastTimestamp: Long = 0L,
    val lastSenderId: String = "",
    val unreadCount: Long = 0L,
    val chatType: String = "direct",
    val groupName: String = "",
    val memberCount: Long = 0L,
    val lastSenderNickname: String = "",
    val isPinned: Boolean = false,
    val isHidden: Boolean = false
)
