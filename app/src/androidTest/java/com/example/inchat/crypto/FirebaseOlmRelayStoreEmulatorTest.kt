package com.example.inchat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Exercises the production FirebaseOlmRelayStore through the Firebase Android
 * SDK against a dedicated demo-project Realtime Database Emulator.
 *
 * This test deliberately injects the transport identity and never initializes
 * FirebaseAuth. Its emulator-only rules are permissive only below the two Olm
 * relay roots; production authorization is covered by rules.test.mjs.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseOlmRelayStoreEmulatorTest {

    @Test
    fun productionTransportRoundTripsCiphertextAndAcknowledgementWithoutAuthEmulator() = runBlocking {
        val host = InstrumentationRegistry.getArguments()
            .getString(FIREBASE_EMULATOR_HOST_ARGUMENT)
            ?.takeIf(String::isNotBlank)
        assumeTrue(
            "Start the dedicated Database Emulator and pass " +
                "-Pandroid.testInstrumentationRunnerArguments.$FIREBASE_EMULATOR_HOST_ARGUMENT=<host>",
            host != null,
        )
        val emulatorHost = requireNotNull(host)
        val runId = UUID.randomUUID().toString().replace("-", "")
        val senderUid = "test-sender-$runId"
        val recipientUid = "test-recipient-$runId"
        val senderDeviceId = "sender-device-$runId"
        val recipientDeviceId = "recipient-device-$runId"
        val messageId = "relay-test-$runId"
        val deliveryId = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val envelope = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                sessionId = "relay-session-$runId",
                ciphertext = byteArrayOf(1, 2, 3, 4),
            ),
        )
        val delivery = QueuedOlmDeviceDelivery(
            messageId = messageId,
            deliveryId = deliveryId,
            recipientUserId = recipientUid,
            recipientDeviceId = recipientDeviceId,
            ownerUserId = senderUid,
            senderDeviceId = senderDeviceId,
            envelope = envelope,
            status = OlmDeliveryStatus.IN_FLIGHT,
            attemptCount = 1,
            leaseUntilMillis = System.currentTimeMillis() + 30_000L,
        )

        val senderClient = createClient(emulatorHost, "olm-sender-$runId")
        val recipientClient = try {
            createClient(emulatorHost, "olm-recipient-$runId")
        } catch (error: Throwable) {
            senderClient.app.delete()
            throw error
        }

        val recipientBundle = PublicDeviceKeyBundle(
            deviceId = recipientDeviceId,
            ed25519IdentityKey = "test-ed25519-identity",
            curve25519IdentityKey = "test-curve25519-identity",
            curve25519IdentityKeySignature = "test-identity-signature",
            fallbackKey = null,
            oneTimeKeys = emptyList(),
        )
        val senderTransport = FirebaseOlmEnvelopeTransport(
            trustVerifier = OlmDeviceTrustVerifier { peerUid, peerDeviceId, peerKey ->
                peerUid == recipientUid &&
                    peerDeviceId == recipientDeviceId &&
                    peerKey == recipientBundle.ed25519IdentityKey
            },
            relayStore = FirebaseOlmRelayStore(senderClient.database),
            currentAuthenticatedUserId = { senderUid },
            readPublishedDevices = { uid ->
                if (uid == recipientUid) listOf(recipientBundle) else emptyList()
            },
            expectedAuthenticatedUserId = senderUid,
        )
        val recipientTransport = FirebaseOlmEnvelopeTransport(
            trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> false },
            relayStore = FirebaseOlmRelayStore(recipientClient.database),
            currentAuthenticatedUserId = { recipientUid },
            readPublishedDevices = { emptyList() },
            expectedAuthenticatedUserId = recipientUid,
        )

        try {
            // Queue a real SDK write while disconnected, then verify it completes
            // after reconnecting. No Auth emulator or Firebase account is involved.
            senderClient.database.goOffline()
            try {
                val pendingUpload = async {
                    senderTransport.upload(delivery, senderDeviceId)
                }
                delay(400)
                senderClient.database.goOnline()
                assertEquals(
                    FirebaseOlmUploadResult.STORED,
                    withTimeout(15_000L) { pendingUpload.await() },
                )
            } finally {
                senderClient.database.goOnline()
            }

            val inbox = recipientTransport.readMyDeviceInbox(recipientDeviceId)
            assertEquals(listOf(deliveryId), inbox.map { it.deliveryId })
            assertEquals(senderUid, inbox.single().senderUid)
            assertEquals(senderDeviceId, inbox.single().senderDeviceId)
            assertEquals(messageId, inbox.single().messageId)
            assertEquals(envelope, inbox.single().envelope)

            // The production adapter writes ServerValue.TIMESTAMP; successfully
            // parsing the returned acknowledgement proves the emulator resolved it.
            recipientTransport.acknowledgeStoredDelivery(recipientDeviceId, inbox.single())
            recipientTransport.removeFromMyDeviceInbox(recipientDeviceId, deliveryId)

            val acknowledgements =
                senderTransport.readMyDeliveryAcknowledgements(senderDeviceId)
            assertEquals(1, acknowledgements.size)
            assertEquals(
                OlmDeliveryAcknowledgement(
                    deliveryId = deliveryId,
                    recipientUserId = recipientUid,
                    recipientDeviceId = recipientDeviceId,
                    messageId = messageId,
                    senderUserId = senderUid,
                    senderDeviceId = senderDeviceId,
                ),
                acknowledgements.single(),
            )

            senderTransport.removeMyDeliveryAcknowledgement(senderDeviceId, deliveryId)
            assertTrue(recipientTransport.readMyDeviceInbox(recipientDeviceId).isEmpty())
            assertTrue(senderTransport.readMyDeliveryAcknowledgements(senderDeviceId).isEmpty())
        } finally {
            senderClient.database.goOnline()
            recipientClient.database.goOnline()
            // Clean only this run's random paths; never clear an emulator namespace.
            runCatching {
                FirebaseOlmRelayStore(recipientClient.database)
                    .removeInbox(recipientUid, recipientDeviceId, deliveryId)
            }
            runCatching {
                FirebaseOlmRelayStore(senderClient.database)
                    .removeAcknowledgement(senderUid, senderDeviceId, deliveryId)
            }
            recipientClient.app.delete()
            senderClient.app.delete()
        }
    }

    private fun createClient(host: String, appName: String): EmulatorClient {
        val options = FirebaseOptions.Builder()
            .setApplicationId("1:123456789012:android:1234567890abcdef")
            .setApiKey("emulator-only-api-key")
            .setProjectId(EMULATOR_PROJECT_ID)
            .setDatabaseUrl("https://$EMULATOR_PROJECT_ID.firebaseio.com")
            .build()
        val app = requireNotNull(
            FirebaseApp.initializeApp(
                InstrumentationRegistry.getInstrumentation().targetContext,
                options,
                appName,
            ),
        )
        return try {
            val database = FirebaseDatabase.getInstance(app).apply {
                useEmulator(host, DATABASE_EMULATOR_PORT)
            }
            EmulatorClient(app, database)
        } catch (error: Throwable) {
            app.delete()
            throw error
        }
    }

    private data class EmulatorClient(
        val app: FirebaseApp,
        val database: FirebaseDatabase,
    )

    private companion object {
        const val FIREBASE_EMULATOR_HOST_ARGUMENT = "firebaseEmulatorHost"
        const val EMULATOR_PROJECT_ID = "demo-inchat-olm-relay"
        const val DATABASE_EMULATOR_PORT = 9000
    }
}
