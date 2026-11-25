package com.example.inchat.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
        private const val MAX_ACCOUNT_CLEANUP_BATCHES = 10_000
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    /**
     * Sends a privacy-preserving wake-up for one already-uploaded encrypted
     * Olm delivery. The Worker derives the recipient account from the direct
     * conversation and resolves the recipient device's private FCM token.
     */
    suspend fun notifyEncryptedDelivery(
        senderUserId: String,
        chatId: String,
        recipientUserId: String,
        deliveryId: String,
        messageId: String,
        senderDeviceId: String,
        recipientDeviceId: String
    ): Result<Unit> {
        if (senderUserId.isBlank() || auth.currentUser?.uid != senderUserId) {
            return Result.failure(
                IllegalStateException("Authenticated user does not match encrypted delivery owner.")
            )
        }

        val valid = chatId.matches(Regex("[A-Za-z0-9_-]{3,257}")) &&
            recipientUserId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            deliveryId.matches(Regex("[A-Fa-f0-9]{64}")) &&
            messageId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            senderDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            recipientDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))
        if (!valid) {
            return Result.failure(
                IllegalArgumentException("Invalid encrypted delivery notification reference.")
            )
        }

        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.success(Unit)
        }

        return post(
            path = "/olm/notify",
            body = JSONObject()
                .put("chatId", chatId)
                .put("recipientUid", recipientUserId)
                .put("deliveryId", deliveryId)
                .put("messageId", messageId)
                .put("senderDeviceId", senderDeviceId)
                .put("recipientDeviceId", recipientDeviceId)
        ).map { response ->
            if (!response.optBoolean("success", false)) {
                throw IllegalStateException("Cloudflare Worker did not accept encrypted push dispatch.")
            }
            Unit
        }
    }


    /**
     * Wakes the originating sender device after this recipient has durably
     * acknowledged a ciphertext relay item. The push contains identifiers only.
     */
    suspend fun notifyEncryptedAcknowledgement(
        senderUserId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        deliveryId: String,
        messageId: String,
    ): Result<Unit> {
        val recipientUserId = auth.currentUser?.uid.orEmpty()
        if (recipientUserId.isBlank() || senderUserId.isBlank() || senderUserId == recipientUserId) {
            return Result.failure(
                IllegalStateException("Authenticated recipient does not match the encrypted acknowledgement."),
            )
        }
        val valid = senderUserId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            senderDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            recipientDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            deliveryId.matches(Regex("[A-Fa-f0-9]{64}")) &&
            messageId.matches(Regex("[A-Za-z0-9_-]{1,128}"))
        if (!valid) {
            return Result.failure(
                IllegalArgumentException("Invalid encrypted acknowledgement notification reference."),
            )
        }
        if (!CloudflareWorkerConfig.isConfigured) return Result.success(Unit)

        return post(
            path = "/olm/notify-ack",
            body = JSONObject()
                .put("senderUid", senderUserId)
                .put("senderDeviceId", senderDeviceId)
                .put("recipientDeviceId", recipientDeviceId)
                .put("deliveryId", deliveryId)
                .put("messageId", messageId),
        ).map { response ->
            if (!response.optBoolean("success", false)) {
                throw IllegalStateException("Cloudflare Worker did not accept the encrypted acknowledgement wake-up.")
            }
            Unit
        }
    }

    /**
     * Updates both participants' inbox metadata after the ciphertext-only direct
     * relay upload. The Worker accepts message identifiers/timestamp only and
     * writes a generic preview; it never receives message text.
     */
    suspend fun syncEncryptedDirectMessagePreview(
        senderUserId: String,
        chatId: String,
        messageId: String,
        sentAtMillis: Long,
        deliveryId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
    ): Result<Unit> {
        if (senderUserId.isBlank() || auth.currentUser?.uid != senderUserId) {
            return Result.failure(
                IllegalStateException("Authenticated user does not match encrypted preview owner."),
            )
        }
        val valid = chatId.matches(Regex("[A-Za-z0-9_-]{3,257}")) &&
            messageId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            deliveryId.matches(Regex("[A-Fa-f0-9]{64}")) &&
            senderDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            recipientDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            sentAtMillis > 0L
        if (!valid) {
            return Result.failure(
                IllegalArgumentException("Invalid encrypted direct preview reference."),
            )
        }
        if (!CloudflareWorkerConfig.isConfigured) return Result.success(Unit)

        var lastFailure: Throwable? = null
        for (waitMs in longArrayOf(0L, 1_000L)) {
            if (waitMs > 0L) delay(waitMs)
            val attempt = post(
                path = "/events/encrypted-direct-preview",
                body = JSONObject()
                    .put("chatId", chatId)
                    .put("messageId", messageId)
                    .put("sentAtMillis", sentAtMillis)
                    .put("deliveryId", deliveryId)
                    .put("senderDeviceId", senderDeviceId)
                    .put("recipientDeviceId", recipientDeviceId),
            )
            if (attempt.isSuccess) {
                val response = attempt.getOrThrow()
                if (!response.optBoolean("success", false)) {
                    return Result.failure(
                        IllegalStateException("Worker did not confirm encrypted direct preview synchronization."),
                    )
                }
                return Result.success(Unit)
            }
            val failure = attempt.exceptionOrNull()
                ?: IllegalStateException("Encrypted direct preview synchronization failed.")
            lastFailure = failure
            if (!isRetryableNotificationFailure(failure)) return Result.failure(failure)
        }
        return Result.failure(
            lastFailure ?: IllegalStateException("Encrypted direct preview synchronization failed."),
        )
    }


    /** Sync generic member inbox previews only after the exact group relay is verified. */
    suspend fun syncEncryptedGroupMessagePreview(
        senderUserId: String,
        groupId: String,
        messageId: String,
        sentAtMillis: Long,
        membershipEpoch: Long,
        deliveryId: String,
        recipientUserId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
    ): Result<Unit> {
        if (senderUserId.isBlank() || auth.currentUser?.uid != senderUserId) {
            return Result.failure(
                IllegalStateException("Authenticated user does not match encrypted group preview owner."),
            )
        }
        val valid = groupId.matches(Regex("[A-Za-z0-9_-]{3,257}")) &&
            messageId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            deliveryId.matches(Regex("[A-Fa-f0-9]{64}")) &&
            recipientUserId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            recipientUserId != senderUserId &&
            senderDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            recipientDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            membershipEpoch > 0L &&
            sentAtMillis > 0L
        if (!valid) {
            return Result.failure(
                IllegalArgumentException("Invalid encrypted group preview reference."),
            )
        }
        if (!CloudflareWorkerConfig.isConfigured) return Result.success(Unit)

        var lastFailure: Throwable? = null
        for (waitMs in longArrayOf(0L, 1_000L)) {
            if (waitMs > 0L) delay(waitMs)
            val attempt = post(
                path = "/events/encrypted-group-preview",
                body = JSONObject()
                    .put("groupId", groupId)
                    .put("messageId", messageId)
                    .put("sentAtMillis", sentAtMillis)
                    .put("membershipEpoch", membershipEpoch)
                    .put("deliveryId", deliveryId)
                    .put("recipientUserId", recipientUserId)
                    .put("senderDeviceId", senderDeviceId)
                    .put("recipientDeviceId", recipientDeviceId),
            )
            if (attempt.isSuccess) {
                val response = attempt.getOrThrow()
                if (!response.optBoolean("success", false)) {
                    return Result.failure(
                        IllegalStateException("Worker did not confirm encrypted group preview synchronization."),
                    )
                }
                return Result.success(Unit)
            }
            val failure = attempt.exceptionOrNull()
                ?: IllegalStateException("Encrypted group preview synchronization failed.")
            lastFailure = failure
            if (!isRetryableNotificationFailure(failure)) return Result.failure(failure)
        }
        return Result.failure(
            lastFailure ?: IllegalStateException("Encrypted group preview synchronization failed."),
        )
    }

    /**
     * Sends a ciphertext-only group wake-up for one uploaded Olm device delivery.
     * The Worker revalidates the current member map and crypto epoch before FCM.
     */
    suspend fun notifyEncryptedGroupDelivery(
        senderUserId: String,
        groupId: String,
        membershipEpoch: Long,
        recipientUserId: String,
        deliveryId: String,
        messageId: String,
        senderDeviceId: String,
        recipientDeviceId: String
    ): Result<Unit> {
        if (senderUserId.isBlank() || auth.currentUser?.uid != senderUserId) {
            return Result.failure(IllegalStateException("Authenticated user does not match encrypted delivery owner."))
        }
        val valid = groupId.matches(Regex("[A-Za-z0-9_-]{3,257}")) &&
            membershipEpoch > 0L &&
            recipientUserId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            deliveryId.matches(Regex("[A-Fa-f0-9]{64}")) &&
            messageId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            senderDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            recipientDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))
        if (!valid) {
            return Result.failure(IllegalArgumentException("Invalid encrypted group delivery notification reference."))
        }
        if (!CloudflareWorkerConfig.isConfigured) return Result.success(Unit)

        return post(
            path = "/olm/notify-group",
            body = JSONObject()
                .put("groupId", groupId)
                .put("membershipEpoch", membershipEpoch)
                .put("recipientUid", recipientUserId)
                .put("deliveryId", deliveryId)
                .put("messageId", messageId)
                .put("senderDeviceId", senderDeviceId)
                .put("recipientDeviceId", recipientDeviceId)
        ).map { response ->
            if (!response.optBoolean("success", false)) {
                throw IllegalStateException("Cloudflare Worker did not accept encrypted group push dispatch.")
            }
            Unit
        }
    }

    /**
     * Performs group creation and membership/role changes through the trusted
     * Worker. Firebase client rules intentionally deny direct writes to
     * chats/{groupId}/members.
     */
    suspend fun manageGroupMembership(
        action: String,
        chatId: String,
        groupName: String? = null,
        memberIds: List<String> = emptyList(),
        targetUid: String? = null,
        role: String? = null
    ): Result<String?> {
        if (
            action !in setOf("create", "add", "set_role", "remove", "leave") ||
            chatId.isBlank()
        ) {
            return Result.failure(
                IllegalArgumentException("Invalid group membership request.")
            )
        }

        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.failure(
                IllegalStateException("The group management service is not configured.")
            )
        }

        val body = JSONObject()
            .put("action", action)
            .put("chatId", chatId)

        groupName?.let { body.put("groupName", it) }
        if (action == "create" || action == "add") {
            val ids = org.json.JSONArray()
            memberIds.forEach { ids.put(it) }
            body.put("memberIds", ids)
        }
        targetUid?.let { body.put("targetUid", it) }
        role?.let { body.put("role", it) }

        return post(
            path = "/groups/manage-members",
            body = body
        ).map { response ->
            response.optString("messageId").takeIf { it.isNotBlank() }
        }
    }

    /**
     * Initializes or repairs the Worker-owned E2EE membership snapshot for a
     * legacy group. The Worker verifies that this signed-in account is still a
     * current member and conditionally binds the complete member map to one
     * monotonically increasing epoch.
     */
    suspend fun ensureGroupCryptoMembershipSnapshot(
        chatId: String
    ): Result<GroupCryptoMembershipSnapshot> {
        if (chatId.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Invalid group crypto membership reference.")
            )
        }
        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.failure(
                IllegalStateException("The group crypto membership service is not configured.")
            )
        }

        return post(
            path = "/groups/crypto-membership",
            body = JSONObject().put("chatId", chatId)
        ).map { response ->
            val epoch = response.optLong("epoch", 0L)
            val membersJson = response.optJSONObject("members")
                ?: throw IllegalStateException("Worker returned no group membership map.")
            val members = mutableMapOf<String, String>()
            val keys = membersJson.keys()
            while (keys.hasNext()) {
                val uid = keys.next()
                val role = membersJson.optString(uid)
                members[uid] = role
            }
            val snapshot = GroupCryptoMembershipSnapshot(
                groupId = response.optString("chatId", chatId),
                epoch = epoch,
                members = members
            )
            if (!snapshot.isValid()) {
                throw IllegalStateException("Worker returned an invalid group crypto membership snapshot.")
            }
            snapshot
        }
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

    /**
     * Removes the current account from every group before its Firebase Auth
     * identity is deleted. The Worker transfers group ownership where needed
     * and removes server-managed FCM topic subscriptions.
     */
    suspend fun cleanupGroupsForAccountDeletion(
        uid: String
    ): Result<Unit> {
        if (uid.isBlank() || auth.currentUser?.uid != uid) {
            return Result.failure(
                IllegalStateException("Authenticated user does not match account owner.")
            )
        }

        if (!CloudflareWorkerConfig.isConfigured) {
            return Result.failure(
                IllegalStateException("The account cleanup service is not configured.")
            )
        }

        // The Worker intentionally processes one group per invocation so
        // every request stays within Cloudflare Workers Free subrequest limits.
        // Keep the Firebase Auth identity alive until the server confirms that
        // every group and account-owned E2EE record has been cleaned.
        var completed = false
        var batches = 0

        while (!completed && batches < MAX_ACCOUNT_CLEANUP_BATCHES) {
            val response = post(
                path = "/account/group-cleanup",
                body = JSONObject()
            ).getOrElse { error ->
                return Result.failure(error)
            }

            if (!response.has("complete")) {
                return Result.failure(
                    IllegalStateException(
                        "The account cleanup service returned an incomplete response."
                    )
                )
            }

            completed = response.optBoolean("complete", false)
            batches += 1
        }

        return if (completed) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "Account cleanup exceeded $MAX_ACCOUNT_CLEANUP_BATCHES batches. " +
                        "It is safe to retry; completed groups have already been cleaned."
                )
            )
        }
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

            val results = coroutineScope {
                snapshot.children
                    .mapNotNull { membership ->
                        val groupId = membership.key.orEmpty()
                        val isMember =
                            membership.getValue(Boolean::class.java) == true

                        if (!isMember || groupId.isBlank()) {
                            null
                        } else {
                            async {
                                setGroupTopicSubscription(
                                    chatId = groupId,
                                    targetUid = uid,
                                    subscribe = subscribe
                                )
                            }
                        }
                    }
                    .awaitAll()
            }

            val firstFailure = results
                .firstOrNull { it.isFailure }
                ?.exceptionOrNull()

            if (firstFailure != null) {
                Result.failure(firstFailure)
            } else {
                Result.success(Unit)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not synchronize group notification topics.", error)
            Result.failure(error)
        }
    }

    internal fun isRetryableNotificationFailure(error: Throwable): Boolean {
        val statusCode = Regex("""failed \((\d{3})\)""")
            .find(error.message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        // Authentication, validation and permission errors need user/code
        // fixes, not repeated requests. Retry rate limits, server failures,
        // request timeouts and transport failures.
        return statusCode == null ||
            statusCode == 408 ||
            statusCode == 429 ||
            statusCode >= 500
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection?.disconnect()
        }
    }
}
