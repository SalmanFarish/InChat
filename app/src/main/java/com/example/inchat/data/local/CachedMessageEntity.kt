package com.example.inchat.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.example.inchat.data.model.Attachment
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo
import org.json.JSONObject

@Entity(
    tableName = "cached_messages",
    primaryKeys = ["ownerUserId", "chatId", "messageId"],
    indices = [Index(value = ["ownerUserId", "chatId", "timestamp"])]
)
data class CachedMessageEntity(
    val ownerUserId: String,
    val chatId: String,
    val messageId: String,
    val senderId: String,
    val senderNickname: String,
    val text: String,
    val timestamp: Long,
    val replyMessageId: String,
    val replySenderId: String,
    val replySenderNickname: String,
    val replyText: String,
    val reactionsJson: String,
    val edited: Boolean,
    val attachmentType: String,
    val attachmentStorageKey: String,
    val attachmentFileName: String,
    val attachmentMimeType: String,
    val attachmentSizeBytes: Long,
    val attachmentViewedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val isPending: Boolean = false,
    @ColumnInfo(defaultValue = "''")
    val systemEvent: String = ""
)

fun Message.toCachedEntity(
    ownerUserId: String,
    chatId: String,
    isPending: Boolean = false
): CachedMessageEntity {
    val reactionJson = JSONObject().apply {
        reactions.forEach { (uid, reaction) -> put(uid, reaction) }
    }.toString()

    return CachedMessageEntity(
        ownerUserId = ownerUserId,
        chatId = chatId,
        messageId = id,
        senderId = senderId,
        senderNickname = senderNickname,
        text = text,
        timestamp = timestamp,
        replyMessageId = replyTo?.messageId.orEmpty(),
        replySenderId = replyTo?.senderId.orEmpty(),
        replySenderNickname = replyTo?.senderNickname.orEmpty(),
        replyText = replyTo?.text.orEmpty(),
        reactionsJson = reactionJson,
        edited = edited,
        attachmentType = attachment?.type.orEmpty(),
        attachmentStorageKey = attachment?.storageKey.orEmpty(),
        attachmentFileName = attachment?.fileName.orEmpty(),
        attachmentMimeType = attachment?.mimeType.orEmpty(),
        attachmentSizeBytes = attachment?.sizeBytes ?: 0L,
        attachmentViewedAt = attachment?.viewedAt ?: 0L,
        isPending = isPending,
        systemEvent = systemEvent
    )
}

fun CachedMessageEntity.toMessage(): Message {
    val parsedReactions = runCatching {
        val json = JSONObject(reactionsJson)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key)
                if (value.isNotBlank()) put(key, value)
            }
        }
    }.getOrDefault(emptyMap())

    val reply = if (replyMessageId.isBlank()) {
        null
    } else {
        ReplyTo(
            messageId = replyMessageId,
            senderId = replySenderId,
            senderNickname = replySenderNickname,
            text = replyText
        )
    }

    val cachedAttachment = if (attachmentType.isBlank()) {
        null
    } else {
        Attachment(
            type = attachmentType,
            storageKey = attachmentStorageKey,
            fileName = attachmentFileName,
            mimeType = attachmentMimeType,
            sizeBytes = attachmentSizeBytes,
            viewedAt = attachmentViewedAt
        )
    }

    return Message(
        id = messageId,
        senderId = senderId,
        senderNickname = senderNickname,
        text = text,
        timestamp = timestamp,
        replyTo = reply,
        reactions = parsedReactions,
        edited = edited,
        attachment = cachedAttachment,
        systemEvent = systemEvent
    )
}