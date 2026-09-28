package com.example.inchat.data.model

enum class AttachmentType {
    VOICE,
    VIEW_ONCE_PHOTO
}

data class Attachment(
    val type: String = "",
    val storageKey: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val viewedAt: Long = 0L
) {
    fun attachmentType(): AttachmentType? =
        when (type.lowercase()) {
            "voice" -> AttachmentType.VOICE
            "view_once_photo" -> AttachmentType.VIEW_ONCE_PHOTO
            else -> null
        }
}
