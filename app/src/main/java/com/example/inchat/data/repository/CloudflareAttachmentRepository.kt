package com.example.inchat.data.repository

import com.example.inchat.data.model.AttachmentType
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CloudflareAttachmentRepository {

    companion object {
        private const val BASE_URL =
            "https://REPLACE_WITH_INCHAT_ATTACHMENTS_WORKER_URL/v1/attachments"

        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 60_000
        private const val MAX_FILE_BYTES = 5L * 1024L * 1024L
        private const val MAX_VOICE_BYTES = 1L * 1024L * 1024L
    }

    private val auth = FirebaseAuth.getInstance()

    suspend fun upload(
        chatId: String,
        messageId: String,
        sourceFile: File,
        mimeType: String,
        attachmentType: AttachmentType,
        fileName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val user = auth.currentUser
                ?: return@withContext Result.failure(
                    IllegalStateException("You must be signed in.")
                )

            if (!sourceFile.exists() || !sourceFile.isFile) {
                return@withContext Result.failure(
                    IllegalArgumentException("Attachment file does not exist.")
                )
            }

            val maxBytes =
                if (attachmentType == AttachmentType.VOICE) {
                    MAX_VOICE_BYTES
                } else {
                    MAX_FILE_BYTES
                }

            if (sourceFile.length() <= 0L || sourceFile.length() > maxBytes) {
                return@withContext Result.failure(
                    IllegalArgumentException("Attachment is too large or empty.")
                )
            }

            val token =
                user.getIdToken(false).await().token
                    ?: throw IllegalStateException(
                        "Could not obtain Firebase ID token."
                    )

            val url = URL(
                "$BASE_URL/${
                    URLEncoder.encode(chatId, Charsets.UTF_8.name())
                }/${
                    URLEncoder.encode(messageId, Charsets.UTF_8.name())
                }"
            )

            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(sourceFile.length())
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty(
                    "Content-Type",
                    mimeType.ifBlank { "application/octet-stream" }
                )
                connection.setRequestProperty(
                    "Content-Length",
                    sourceFile.length().toString()
                )
                connection.setRequestProperty(
                    "X-InChat-Attachment-Type",
                    attachmentType.name.lowercase()
                )
                connection.setRequestProperty(
                    "X-InChat-File-Name",
                    fileName.take(180)
                )

                sourceFile.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val error = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()

                    return@withContext Result.failure(
                        IllegalStateException(
                            if (error.isBlank()) {
                                "Attachment upload failed ($responseCode)."
                            } else {
                                error
                            }
                        )
                    )
                }

                Result.success(
                    "attachments/$chatId/$messageId"
                )
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun download(
        chatId: String,
        messageId: String,
        destinationFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val user = auth.currentUser
                ?: return@withContext Result.failure(
                    IllegalStateException("You must be signed in.")
                )

            val token =
                user.getIdToken(false).await().token
                    ?: throw IllegalStateException(
                        "Could not obtain Firebase ID token."
                    )

            val url = URL(
                "$BASE_URL/${
                    URLEncoder.encode(chatId, Charsets.UTF_8.name())
                }/${
                    URLEncoder.encode(messageId, Charsets.UTF_8.name())
                }"
            )

            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.setRequestProperty("Authorization", "Bearer $token")

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Attachment download failed ($responseCode)."
                        )
                    )
                }

                destinationFile.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                Result.success(destinationFile)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun delete(
        chatId: String,
        messageId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val user = auth.currentUser
                ?: return@withContext Result.failure(
                    IllegalStateException("You must be signed in.")
                )

            val token =
                user.getIdToken(false).await().token
                    ?: throw IllegalStateException(
                        "Could not obtain Firebase ID token."
                    )

            val url = URL(
                "$BASE_URL/${
                    URLEncoder.encode(chatId, Charsets.UTF_8.name())
                }/${
                    URLEncoder.encode(messageId, Charsets.UTF_8.name())
                }"
            )

            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "DELETE"
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.setRequestProperty("Authorization", "Bearer $token")

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Attachment deletion failed ($responseCode)."
                        )
                    )
                }

                Result.success(Unit)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
