package com.example.inchat.data.model

data class Message(
    val id: String = "",
    val senderId: String = "",
    val senderNickname: String = "",
    val text: String = "",
    val timestamp: Long = 0L,
    val replyTo: ReplyTo? = null,
    val reactions: Map<String, String> = emptyMap(),
    val edited: Boolean = false
)

data class ReplyTo(
    val messageId: String = "",
    val senderId: String = "",
    val senderNickname: String = "",
    val text: String = ""
)
