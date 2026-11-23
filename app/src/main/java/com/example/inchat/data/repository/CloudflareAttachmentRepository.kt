package com.example.inchat.data.repository

import com.example.inchat.data.model.AttachmentType
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
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
            CloudflareWorkerConfig.API_BASE_URL + "/attachments"

        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 60_000
        private const val MAX_FILE_BYTES = 5L * 1024L * 1024L
        private const val MAX_VOICE_BYTES = 1L * 1024L * 1024L
    }

    private val auth = FirebaseAuth.getInstance()

    /**
     * Raw attachment uploads are intentionally disabled. The current Olm and
     * Megolm payload formats do not carry an authenticated media key, so
     * uploading source bytes here would expose media outside E2EE.
     *
     * Legacy attachments remain downloadable/deletable; new media sending
     * must wait for encrypted attachment envelopes and ciphertext storage.
     */
    suspend fun upload(
        chatId: String,
        messageId: String,
        sourceFile: File,
        mimeType: String,
        attachmentType: AttachmentType,
        fileName: String
    ): Result<String> = Result.failure(
        UnsupportedOperationException(
            "Attachment uploads are disabled until end-to-end encrypted media is supported."
        )
    )

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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
