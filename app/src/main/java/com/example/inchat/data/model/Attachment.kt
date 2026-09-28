package com.example.inchat.data.model

enum class AttachmentType {
    VOICE,
    FILE
}

data class Attachment(
    val type: String = "",
    val storageKey: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L
) {
    fun attachmentType(): AttachmentType? =
        when (type.lowercase()) {
            "voice" -> AttachmentType.VOICE
            "file" -> AttachmentType.FILE
            else -> null
        }
}
