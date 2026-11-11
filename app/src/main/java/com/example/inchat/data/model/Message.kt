package com.example.inchat.data.model

data class Message(
    val id: String = "",
    val senderId: String = "",
    val senderNickname: String = "",
    val text: String = "",
    val timestamp: Long = 0L,
    val replyTo: ReplyTo? = null,
    val reactions: Map<String, String> = emptyMap(),
    val edited: Boolean = false,
    val attachment: Attachment? = null,
    val systemEvent: String = "",
    val workerProcessed: WorkerProcessedReceipt? = null
)

data class WorkerProcessedReceipt(
    val senderId: String = "",
    val processedAt: Long = 0L
)

data class ReplyTo(
    val messageId: String = "",
    val senderId: String = "",
    val senderNickname: String = "",
    val text: String = ""
)
