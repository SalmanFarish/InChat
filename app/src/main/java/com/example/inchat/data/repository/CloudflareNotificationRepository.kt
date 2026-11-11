package com.example.inchat.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls the Cloudflare Worker for trusted notification delivery and
 * server-managed FCM group topic subscriptions.
 *
 * The Worker URL is public. Firebase service-account credentials and FCM
 * server authority remain exclusively in Cloudflare secrets.
 */
class CloudflareNotificationRepository {

    companion object {
        private const val TAG = "CloudflareNotifications"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    suspend fun notifyMessage(
        chatId: String,
        messageId: String
    ): Result<Unit> {
        if (chatId.isBlank() || messageId.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Invalid notification message reference.")
            )
        }

        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.success(Unit)
        }

        return post(
            path = "/events/message",
            body = JSONObject()
                .put("chatId", chatId)
                .put("messageId", messageId)
        ).map { Unit }
    }

    suspend fun setGroupTopicSubscription(
        chatId: String,
        targetUid: String,
        subscribe: Boolean
    ): Result<Unit> {
        if (chatId.isBlank() || targetUid.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Invalid group topic subscription.")
            )
        }

        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.success(Unit)
        }

        return post(
            path = "/groups/topic",
            body = JSONObject()
                .put("chatId", chatId)
                .put("targetUid", targetUid)
                .put("subscribe", subscribe)
        ).map { Unit }
    }

    suspend fun clearMyPersonalTopic(
        uid: String
    ): Result<Unit> {
        if (uid.isBlank() || auth.currentUser?.uid != uid) {
            return Result.failure(
                IllegalStateException("Authenticated user does not match notification owner.")
            )
        }

        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.success(Unit)
        }

        return post(
            path = "/notifications/personal-topic",
            body = JSONObject()
        ).map { Unit }
    }

    suspend fun syncConversationPreview(
        chatId: String
    ): Result<Unit> {
        if (chatId.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Chat ID cannot be blank.")
            )
        }

        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.success(Unit)
        }

        return post(
            path = "/events/preview",
            body = JSONObject().put("chatId", chatId)
        ).map { Unit }
    }

    /**
     * Reconcile this signed-in device's FCM token with every group the
     * current account still belongs to. FCM topic membership is not carried
     * over automatically when an installation token changes.
     */
    suspend fun syncMyGroupSubscriptions(
        uid: String,
        subscribe: Boolean = true
    ): Result<Unit> {
        if (uid.isBlank()) {
            return Result.failure(
                IllegalArgumentException("User ID cannot be blank.")
            )
        }

        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.success(Unit)
        }

        if (auth.currentUser?.uid != uid) {
            return Result.failure(
                IllegalStateException("Authenticated user does not match group owner.")
            )
        }

        return try {
            val snapshot = database.getReference("groupMemberships")
                .child(uid)
                .get()
                .await()

            var firstFailure: Throwable? = null

            for (membership in snapshot.children) {
                val groupId = membership.key.orEmpty()
                val isMember = membership.getValue(Boolean::class.java) == true
                if (!isMember || groupId.isBlank()) continue

                val result = setGroupTopicSubscription(
                    chatId = groupId,
                    targetUid = uid,
                    subscribe = subscribe
                )

                if (result.isFailure && firstFailure == null) {
                    firstFailure = result.exceptionOrNull()
                }
            }

            if (firstFailure != null) {
                Result.failure(firstFailure!!)
            } else {
                Result.success(Unit)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not synchronize group notification topics.", error)
            Result.failure(error)
        }
    }

    private suspend fun post(
        path: String,
        body: JSONObject
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            val user = auth.currentUser
                ?: throw IllegalStateException("You must be signed in.")

            val idToken = user.getIdToken(false).await().token
                ?: throw IllegalStateException("Could not obtain Firebase ID token.")

            val endpoint = URL(
                CloudflareWorkerConfig.API_BASE_URL.trimEnd('/') + path
            )

            connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )
            connection.setRequestProperty("Accept", "application/json")

            val requestBytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.outputStream.use { output ->
                output.write(requestBytes)
            }

            val responseCode = connection.responseCode
            val responseText = (
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
            )?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                throw IllegalStateException(
                    "Cloudflare notification request failed ($responseCode): " +
                        responseText.take(240)
                )
            }

            val response = if (responseText.isBlank()) {
                JSONObject()
            } else {
                JSONObject(responseText)
            }

            Result.success(response)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection?.disconnect()
        }
    }
}
