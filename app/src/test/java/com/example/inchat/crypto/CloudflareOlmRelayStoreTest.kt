package com.example.inchat.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CloudflareOlmRelayStoreTest {

    @Test
    fun relayRoundTripUsesMailboxEndpointsAndTheExistingOlmEnvelopeChecks() = runBlocking {
        val requests = RecordingHttpClient { request ->
            when {
                request.method == "POST" && request.url.endsWith("/olm/mailbox/enqueue") ->
                    JSONObject().put("accepted", true)
                request.method == "GET" && request.url.contains("/olm/mailbox/inbox?") ->
                    JSONObject()
                        .put("items", JSONArray().put(directInboxJson()))
                        .put("nextCursor", JSONObject.NULL)
                request.method == "POST" && request.url.contains("/olm/mailbox/acknowledge?") ->
                    JSONObject().put("acknowledged", true)
                request.method == "DELETE" && request.url.contains("/olm/mailbox/inbox/") -> null
                request.method == "GET" && request.url.contains("/olm/mailbox/acks?") ->
                    JSONObject()
                        .put("items", JSONArray().put(acknowledgementJson()))
                        .put("nextCursor", JSONObject.NULL)
                request.method == "DELETE" && request.url.contains("/olm/mailbox/acks/") -> null
                else -> error("Unexpected mailbox request: " + request.method + " " + request.url)
            }
        }
        val tokenProvider = RecordingTokenProvider()
        val store = CloudflareOlmRelayStore(
            tokenProvider = tokenProvider,
            httpClient = requests,
            apiBaseUrl = "https://worker.example/v1",
        )
        val senderTransport = cloudflareTransport("alice", store)
        val delivery = queuedDelivery()

        assertEquals(
            FirebaseOlmUploadResult.STORED,
            senderTransport.upload(delivery, "sender-device"),
        )

        val enqueue = requests.requests.single()
        assertEquals("POST", enqueue.method)
        assertEquals("https://worker.example/v1/olm/mailbox/enqueue", enqueue.url)
        assertEquals("alice-token", enqueue.bearerToken)
        assertEquals(delivery.deliveryId, enqueue.body?.getString("deliveryId"))
        assertEquals("bob", enqueue.body?.getString("recipientUid"))
        assertEquals("recipient-device", enqueue.body?.getString("recipientDeviceId"))
        assertEquals(delivery.envelope, enqueue.body?.getString("envelope"))
        assertEquals("alice_bob", enqueue.body?.getString("conversationId"))
        assertEquals("direct", enqueue.body?.getString("conversationType"))

        val recipientTransport = cloudflareTransport("bob", store)
        val received = recipientTransport.readMyDeviceInbox("recipient-device").single()
        assertEquals(delivery.deliveryId, received.deliveryId)
        assertEquals("alice", received.senderUid)
        assertEquals(delivery.messageId, received.messageId)
        assertEquals(delivery.envelope, received.envelope)
        assertEquals("bob", received.conversationPeerUserId)

        recipientTransport.acknowledgeStoredDelivery("recipient-device", received)
        recipientTransport.removeFromMyDeviceInbox("recipient-device", received.deliveryId)
        val acknowledgement = senderTransport.readMyDeliveryAcknowledgements("sender-device").single()
        assertEquals(
            OlmDeliveryAcknowledgement(
                deliveryId = delivery.deliveryId,
                recipientUserId = "bob",
                recipientDeviceId = "recipient-device",
                messageId = delivery.messageId,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
            ),
            acknowledgement,
        )
        senderTransport.removeMyDeliveryAcknowledgement("sender-device", acknowledgement.deliveryId)

        assertEquals(
            listOf(
                "POST",
                "GET",
                "POST",
                "DELETE",
                "GET",
                "DELETE",
            ),
            requests.requests.map { it.method },
        )
        assertEquals(
            listOf("alice", "bob", "bob", "alice", "alice"),
            tokenProvider.accountIds,
        )
        assertEquals(
            "https://worker.example/v1/olm/mailbox/acknowledge?deviceId=recipient-device",
            requests.requests[2].url,
        )
        assertEquals(
            JSONObject().put("deliveryId", delivery.deliveryId).toString(),
            requests.requests[2].body.toString(),
        )
    }

    @Test
    fun groupEnqueuePreservesTheExactMembershipEpochAndOmitsDirectPeer() = runBlocking {
        val requests = RecordingHttpClient { JSONObject().put("accepted", true) }
        val store = CloudflareOlmRelayStore(
            tokenProvider = RecordingTokenProvider(),
            httpClient = requests,
            apiBaseUrl = "https://worker.example/v1",
        )
        val groupDelivery = queuedDelivery().copy(
            groupId = "group-1",
            membershipEpoch = 7L,
            conversationId = "group-1",
            conversationPeerUserId = null,
        )

        cloudflareTransport("alice", store).upload(groupDelivery, "sender-device")

        val body = requests.requests.single().body ?: error("Mailbox enqueue body was missing.")
        assertEquals("group", body.getString("conversationType"))
        assertEquals("group-1", body.getString("conversationId"))
        assertEquals("group-1", body.getString("groupId"))
        assertEquals(7L, body.getLong("membershipEpoch"))
        assertFalse(body.has("conversationPeerUserId"))
    }

    @Test
    fun inboxAndAcknowledgementReadsFollowBoundedServerCursors() = runBlocking {
        val requests = RecordingHttpClient { request ->
            when {
                request.url.contains("/olm/mailbox/inbox?") && !request.url.contains("cursor=") ->
                    JSONObject()
                        .put("items", JSONArray().put(directInboxJson()))
                        .put("nextCursor", "50")
                request.url.contains("/olm/mailbox/inbox?") ->
                    JSONObject()
                        .put("items", JSONArray().put(directInboxJson().put("deliveryId", "b".repeat(64))))
                        .put("nextCursor", JSONObject.NULL)
                request.url.contains("/olm/mailbox/acks?") && !request.url.contains("cursor=") ->
                    JSONObject()
                        .put("items", JSONArray().put(acknowledgementJson()))
                        .put("nextCursor", "50")
                request.url.contains("/olm/mailbox/acks?") ->
                    JSONObject()
                        .put("items", JSONArray().put(acknowledgementJson().put("deliveryId", "b".repeat(64))))
                        .put("nextCursor", JSONObject.NULL)
                else -> error("Unexpected mailbox page request: " + request.url)
            }
        }
        val store = CloudflareOlmRelayStore(
            tokenProvider = RecordingTokenProvider(),
            httpClient = requests,
            apiBaseUrl = "https://worker.example/v1",
        )

        val inbox = store.readInbox("bob", "recipient-device")
        val acknowledgements = store.readAcknowledgements("alice", "sender-device")

        assertEquals(listOf("a".repeat(64), "b".repeat(64)), inbox.map { it.deliveryId })
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), acknowledgements.map { it.deliveryId })
        assertTrue(requests.requests[0].url.endsWith("&limit=50"))
        assertTrue(requests.requests[1].url.endsWith("&limit=50&cursor=50"))
        assertTrue(requests.requests[2].url.endsWith("&limit=50"))
        assertTrue(requests.requests[3].url.endsWith("&limit=50&cursor=50"))
    }

    @Test
    fun recipientAndSenderDeletesUseOnlyTheirOwnAuthenticatedDeviceScope() = runBlocking {
        val requests = RecordingHttpClient { null }
        val tokens = RecordingTokenProvider()
        val store = CloudflareOlmRelayStore(
            tokenProvider = tokens,
            httpClient = requests,
            apiBaseUrl = "https://worker.example/v1",
        )
        val id = "c".repeat(64)

        store.removeInbox("bob", "recipient-device", id)
        store.removeAcknowledgement("alice", "sender-device", id)

        assertEquals("DELETE", requests.requests[0].method)
        assertEquals(
            "https://worker.example/v1/olm/mailbox/inbox/$id?deviceId=recipient-device",
            requests.requests[0].url,
        )
        assertEquals("bob-token", requests.requests[0].bearerToken)
        assertEquals(
            "https://worker.example/v1/olm/mailbox/acks/$id?deviceId=sender-device",
            requests.requests[1].url,
        )
        assertEquals("alice-token", requests.requests[1].bearerToken)
        assertEquals(listOf("bob", "alice"), tokens.accountIds)
    }

    @Test
    fun retryableWorkerFailuresRemainVisibleToTheExistingWorkManagerRetryPath() = runBlocking {
        val requests = RecordingHttpClient {
            throw OlmMailboxHttpException(503, retryable = true, message = "mailbox unavailable")
        }
        val store = CloudflareOlmRelayStore(
            tokenProvider = RecordingTokenProvider(),
            httpClient = requests,
            apiBaseUrl = "https://worker.example/v1",
        )

        val failure = runCatching {
            store.readInbox("bob", "recipient-device")
        }.exceptionOrNull()

        assertTrue(failure is OlmMailboxHttpException)
        assertEquals(503, (failure as OlmMailboxHttpException).statusCode)
        assertTrue(failure.retryable)
    }

    private fun cloudflareTransport(
        userId: String,
        store: OlmRelayStore,
    ) = CloudflareOlmEnvelopeTransport(
        trustVerifier = OlmDeviceTrustVerifier { peerUid, peerDeviceId, peerKey ->
            peerUid == "bob" &&
                peerDeviceId == "recipient-device" &&
                peerKey == "recipient-ed25519"
        },
        relayStore = store,
        currentAuthenticatedUserId = { userId },
        readPublishedDevices = { uid ->
            if (uid == "bob") listOf(recipientBundle()) else emptyList()
        },
        expectedAuthenticatedUserId = userId,
    )

    private fun queuedDelivery(): QueuedOlmDeviceDelivery {
        val envelope = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = "sender-device",
                recipientDeviceId = "recipient-device",
                sessionId = "session-1",
                ciphertext = byteArrayOf(1, 2, 3),
            ),
        )
        return QueuedOlmDeviceDelivery(
            messageId = "message-1",
            deliveryId = "a".repeat(64),
            recipientUserId = "bob",
            recipientDeviceId = "recipient-device",
            ownerUserId = "alice",
            senderDeviceId = "sender-device",
            envelope = envelope,
            conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "bob"),
            conversationPeerUserId = "bob",
            status = OlmDeliveryStatus.IN_FLIGHT,
            attemptCount = 1,
            leaseUntilMillis = 10_000L,
        )
    }

    private fun recipientBundle() = PublicDeviceKeyBundle(
        deviceId = "recipient-device",
        ed25519IdentityKey = "recipient-ed25519",
        curve25519IdentityKey = "recipient-curve25519",
        curve25519IdentityKeySignature = "identity-signature",
        fallbackKey = null,
        oneTimeKeys = emptyList(),
    )

    private fun directInboxJson() = JSONObject()
        .put("deliveryId", "a".repeat(64))
        .put("senderUid", "alice")
        .put("senderDeviceId", "sender-device")
        .put("messageId", "message-1")
        .put("envelope", queuedDelivery().envelope)
        .put("conversationType", "direct")
        .put("conversationId", "alice_bob")
        .put("conversationPeerUserId", "bob")

    private fun acknowledgementJson() = JSONObject()
        .put("deliveryId", "a".repeat(64))
        .put("recipientUid", "bob")
        .put("recipientDeviceId", "recipient-device")
        .put("messageId", "message-1")
        .put("acknowledgedAt", 1234L)

    private class RecordingTokenProvider : OlmMailboxIdTokenProvider {
        val accountIds = mutableListOf<String>()

        override suspend fun getIdToken(expectedUserId: String): String {
            accountIds += expectedUserId
            return expectedUserId + "-token"
        }
    }

    private class RecordingHttpClient(
        private val respond: (RecordedRequest) -> JSONObject?,
    ) : OlmMailboxHttpClient {
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun execute(
            method: String,
            url: String,
            bearerToken: String,
            body: JSONObject?,
        ): JSONObject? {
            val request = RecordedRequest(
                method = method,
                url = url,
                bearerToken = bearerToken,
                body = body?.let { JSONObject(it.toString()) },
            )
            requests += request
            return respond(request)
        }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val bearerToken: String,
        val body: JSONObject?,
    )
}
