package com.example.inchat.crypto

import com.example.inchat.data.repository.CloudflareWorkerConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Supplies a Firebase ID token for exactly the account whose mailbox is being
 * accessed. The transport never accepts a caller-provided UID as authentication.
 */
internal fun interface OlmMailboxIdTokenProvider {
    suspend fun getIdToken(expectedUserId: String): String
}

internal class FirebaseOlmMailboxIdTokenProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : OlmMailboxIdTokenProvider {
    override suspend fun getIdToken(expectedUserId: String): String {
        require(UID_PATTERN.matches(expectedUserId)) { "Mailbox account ID has an invalid format." }
        val user = requireNotNull(auth.currentUser) { "Sign in before using encrypted mailbox delivery." }
        require(user.uid == expectedUserId) { "The mailbox account does not match the signed-in account." }
        val token = user.getIdToken(false).await().token
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Could not obtain a Firebase ID token for encrypted mailbox delivery.")
        check(auth.currentUser?.uid == expectedUserId) {
            "The signed-in account changed while obtaining the encrypted mailbox token."
        }
        return token
    }

    private companion object {
        val UID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/** Small HTTP seam so mailbox routing and JSON contracts can be tested without a live Worker. */
internal interface OlmMailboxHttpClient {
    suspend fun execute(
        method: String,
        url: String,
        bearerToken: String,
        body: JSONObject? = null,
    ): JSONObject?
}

internal class OlmMailboxHttpException(
    val statusCode: Int,
    val retryable: Boolean,
    message: String,
) : IOException(message)

/**
 * Android's existing HTTP stack is HttpURLConnection; no additional networking
 * dependency or paid service is introduced for encrypted delivery.
 */
internal class UrlConnectionOlmMailboxHttpClient(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : OlmMailboxHttpClient {
    override suspend fun execute(
        method: String,
        url: String,
        bearerToken: String,
        body: JSONObject?,
    ): JSONObject? = withContext(Dispatchers.IO) {
        require(method in HTTP_METHODS) { "Unsupported encrypted mailbox HTTP method." }
        require(bearerToken.isNotBlank()) { "Encrypted mailbox authorization token is missing." }
        val endpoint = URL(url)
        require(endpoint.protocol == "https") { "Encrypted mailbox requests must use HTTPS." }

        var connection: HttpURLConnection? = null
        try {
            connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_REQUEST_BYTES) {
                    "Encrypted mailbox request exceeds the supported size."
                }
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output -> output.write(bytes) }
            }

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return@withContext null
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use { it.readUtf8Limited(MAX_RESPONSE_BYTES) }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText).optString("error").takeIf(String::isNotBlank)
                }.getOrNull() ?: "Encrypted mailbox request failed."
                throw OlmMailboxHttpException(
                    statusCode = status,
                    retryable = status == 408 || status == 429 || status >= 500,
                    message = "Encrypted mailbox request failed ($status): " + message.take(240),
                )
            }
            if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        } finally {
            connection?.disconnect()
        }
    }

    private fun InputStream.readUtf8Limited(maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Encrypted mailbox response exceeds the supported size.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        const val MAX_REQUEST_BYTES = 3 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 24 * 1024 * 1024
        val HTTP_METHODS = setOf("GET", "POST", "DELETE")
    }
}

/**
 * Implements the existing OlmRelayStore contract over the Cloudflare mailbox
 * API. Firebase is used for account authentication and device/conversation
 * metadata only; encrypted message envelopes and receipts use Cloudflare's
 * temporary ciphertext queue.
 */
internal class CloudflareOlmRelayStore(
    private val tokenProvider: OlmMailboxIdTokenProvider = FirebaseOlmMailboxIdTokenProvider(),
    private val httpClient: OlmMailboxHttpClient = UrlConnectionOlmMailboxHttpClient(),
    private val apiBaseUrl: String = CloudflareWorkerConfig.API_BASE_URL,
) : OlmRelayStore {

    override suspend fun putInbox(
        recipientUid: String,
        recipientDeviceId: String,
        deliveryId: String,
        fields: Map<String, Any?>,
    ) {
        require(DELIVERY_PATTERN.matches(deliveryId)) { "Delivery ID has an invalid format." }
        val senderUid = fields["senderUid"] as? String
            ?: error("Encrypted delivery sender account is missing.")
        require(fields.keys == DIRECT_INBOX_FIELDS || fields.keys == GROUP_INBOX_FIELDS) {
            "Encrypted delivery contains missing or unexpected routing fields."
        }
        require(UID_PATTERN.matches(senderUid)) { "Sender account ID has an invalid format." }
        require(UID_PATTERN.matches(recipientUid)) { "Recipient account ID has an invalid format." }
        require(DEVICE_PATTERN.matches(recipientDeviceId)) { "Recipient device ID has an invalid format." }
        require(DEVICE_PATTERN.matches(fields["senderDeviceId"] as? String ?: "")) {
            "Sender device ID has an invalid format."
        }
        require(MESSAGE_PATTERN.matches(fields["messageId"] as? String ?: "")) {
            "Message ID has an invalid format."
        }
        require(fields["conversationType"] == if (fields.keys == GROUP_INBOX_FIELDS) "group" else "direct") {
            "Encrypted delivery conversation type does not match its routing fields."
        }

        val body = JSONObject()
        fields.forEach { (key, value) -> body.put(key, value) }
        body.put("deliveryId", deliveryId)
        body.put("recipientUid", recipientUid)
        body.put("recipientDeviceId", recipientDeviceId)
        execute(
            authenticatedUserId = senderUid,
            method = "POST",
            path = "/olm/mailbox/enqueue",
            body = body,
        )
    }

    override suspend fun readInbox(
        recipientUid: String,
        recipientDeviceId: String,
    ): List<FirebaseOlmRelayRecord> {
        require(UID_PATTERN.matches(recipientUid))
        require(DEVICE_PATTERN.matches(recipientDeviceId))
        return readPages(
            authenticatedUserId = recipientUid,
            path = "/olm/mailbox/inbox",
            deviceId = recipientDeviceId,
            maxPages = MAX_INBOX_PAGES,
        )
    }

    override suspend fun removeInbox(
        recipientUid: String,
        recipientDeviceId: String,
        deliveryId: String,
    ) {
        require(UID_PATTERN.matches(recipientUid))
        require(DEVICE_PATTERN.matches(recipientDeviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        execute(
            authenticatedUserId = recipientUid,
            method = "DELETE",
            path = "/olm/mailbox/inbox/" + deliveryId.lowercase() + "?deviceId=" + recipientDeviceId,
        )
    }

    override suspend fun putAcknowledgement(
        senderUid: String,
        senderDeviceId: String,
        deliveryId: String,
        fields: Map<String, Any?>,
    ) {
        require(UID_PATTERN.matches(senderUid))
        require(DEVICE_PATTERN.matches(senderDeviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        val recipientUid = fields["recipientUid"] as? String
            ?: error("Acknowledgement recipient account is missing.")
        require(fields.keys == ACK_FIELDS) { "Acknowledgement contains missing or unexpected fields." }
        require(UID_PATTERN.matches(recipientUid))
        require(DEVICE_PATTERN.matches(fields["recipientDeviceId"] as? String ?: ""))
        require(MESSAGE_PATTERN.matches(fields["messageId"] as? String ?: ""))
        execute(
            authenticatedUserId = recipientUid,
            method = "POST",
            path = "/olm/mailbox/acknowledge?deviceId=" + fields["recipientDeviceId"],
            body = JSONObject().put("deliveryId", deliveryId),
        )
    }

    override suspend fun readAcknowledgements(
        senderUid: String,
        senderDeviceId: String,
    ): List<FirebaseOlmRelayRecord> {
        require(UID_PATTERN.matches(senderUid))
        require(DEVICE_PATTERN.matches(senderDeviceId))
        return readPages(
            authenticatedUserId = senderUid,
            path = "/olm/mailbox/acks",
            deviceId = senderDeviceId,
            maxPages = MAX_ACKNOWLEDGEMENT_PAGES,
        )
    }

    override suspend fun removeAcknowledgement(
        senderUid: String,
        senderDeviceId: String,
        deliveryId: String,
    ) {
        require(UID_PATTERN.matches(senderUid))
        require(DEVICE_PATTERN.matches(senderDeviceId))
        require(DELIVERY_PATTERN.matches(deliveryId))
        execute(
            authenticatedUserId = senderUid,
            method = "DELETE",
            path = "/olm/mailbox/acks/" + deliveryId.lowercase() + "?deviceId=" + senderDeviceId,
        )
    }

    private suspend fun readPages(
        authenticatedUserId: String,
        path: String,
        deviceId: String,
        maxPages: Int,
    ): List<FirebaseOlmRelayRecord> {
        val records = ArrayList<FirebaseOlmRelayRecord>()
        var cursor: String? = null
        repeat(maxPages) {
            val query = buildString {
                append(path)
                append("?deviceId=")
                append(deviceId)
                append("&limit=")
                append(PAGE_SIZE)
                cursor?.let {
                    append("&cursor=")
                    append(it)
                }
            }
            val response = execute(authenticatedUserId, "GET", query)
                ?: error("Encrypted mailbox returned an empty page.")
            val items = response.optJSONArray("items")
                ?: error("Encrypted mailbox page is missing its items.")
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index)
                    ?: error("Encrypted mailbox page contains a malformed item.")
                val deliveryId = item.optString("deliveryId").takeIf(String::isNotBlank)
                    ?: error("Encrypted mailbox item is missing its delivery ID.")
                val fields = item.toFieldMap(excludedKeys = setOf("deliveryId"))
                records += FirebaseOlmRelayRecord(deliveryId, fields)
            }

            val nextValue = response.opt("nextCursor")
            val nextCursor = when {
                nextValue == null || nextValue === JSONObject.NULL -> null
                nextValue is String && nextValue.matches(CURSOR_PATTERN) -> nextValue
                else -> error("Encrypted mailbox returned an invalid page cursor.")
            }
            if (nextCursor == null) return records
            require(nextCursor != cursor) { "Encrypted mailbox repeated a page cursor." }
            cursor = nextCursor
        }
        throw IOException("Encrypted mailbox exceeded its maximum supported page count.")
    }

    private suspend fun execute(
        authenticatedUserId: String,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject? {
        require(apiBaseUrl.startsWith("https://")) {
            "Encrypted mailbox API must use a configured HTTPS endpoint."
        }
        val token = tokenProvider.getIdToken(authenticatedUserId)
        require(token.isNotBlank()) { "Encrypted mailbox token provider returned an empty token." }
        return httpClient.execute(
            method = method,
            url = apiBaseUrl.trimEnd('/') + path,
            bearerToken = token,
            body = body,
        )
    }

    private fun JSONObject.toFieldMap(excludedKeys: Set<String>): Map<String, Any?> =
        keys().asSequence()
            .filterNot(excludedKeys::contains)
            .associateWith { key ->
                val value = opt(key)
                if (value === JSONObject.NULL) null else value
            }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_INBOX_PAGES = 5
        const val MAX_ACKNOWLEDGEMENT_PAGES = 11
        val UID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val MESSAGE_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DELIVERY_PATTERN = Regex("[A-Fa-f0-9]{64}")
        val CURSOR_PATTERN = Regex("\\d{1,16}")
        val BASE_INBOX_FIELDS = setOf(
            "senderUid", "senderDeviceId", "messageId", "envelope", "conversationType", "conversationId",
        )
        val DIRECT_INBOX_FIELDS = BASE_INBOX_FIELDS + "conversationPeerUserId"
        val GROUP_INBOX_FIELDS = BASE_INBOX_FIELDS + setOf("groupId", "membershipEpoch")
        val ACK_FIELDS = setOf("recipientUid", "recipientDeviceId", "messageId", "acknowledgedAt")
    }
}

/**
 * Uses the exact same trust checks, envelope validation and account-switch
 * fencing as the existing isolated transport, swapping only the remote relay
 * implementation. This keeps Room receipts and outbox semantics unchanged.
 */
internal class CloudflareOlmEnvelopeTransport(
    trustVerifier: OlmDeviceTrustVerifier,
    relayStore: OlmRelayStore = CloudflareOlmRelayStore(),
    currentAuthenticatedUserId: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid },
    readPublishedDevices: suspend (String) -> List<PublicDeviceKeyBundle> = { uid ->
        DeviceKeyDirectoryRepository().getUserDevices(uid)
    },
    expectedAuthenticatedUserId: String? = null,
) : OlmInboxTransport, OlmOutgoingDeliveryTransport {
    private val delegate = FirebaseOlmEnvelopeTransport(
        trustVerifier = trustVerifier,
        relayStore = relayStore,
        currentAuthenticatedUserId = currentAuthenticatedUserId,
        readPublishedDevices = readPublishedDevices,
        expectedAuthenticatedUserId = expectedAuthenticatedUserId,
    )

    override suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> =
        delegate.readMyDeviceInbox(deviceId)

    override suspend fun acknowledgeStoredDelivery(deviceId: String, item: FirebaseOlmInboxItem) =
        delegate.acknowledgeStoredDelivery(deviceId, item)

    override suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) =
        delegate.removeFromMyDeviceInbox(deviceId, deliveryId)

    override suspend fun readMyDeliveryAcknowledgements(senderDeviceId: String): List<OlmDeliveryAcknowledgement> =
        delegate.readMyDeliveryAcknowledgements(senderDeviceId)

    override suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String) =
        delegate.removeMyDeliveryAcknowledgement(senderDeviceId, deliveryId)

    override suspend fun upload(
        delivery: QueuedOlmDeviceDelivery,
        senderDeviceId: String,
    ): FirebaseOlmUploadResult = delegate.upload(delivery, senderDeviceId)
}
