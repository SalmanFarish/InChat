package com.example.inchat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Exercises the real Firebase Realtime Database adapter against local Auth and
 * Database emulators. Supply firebaseEmulatorHost to opt in; ordinary device
 * test runs skip this integration test when the emulators are not running.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseSasVerificationTransportEmulatorTest {

    @Test
    fun twoAuthenticatedAccountsCanUploadReadRetryAndAcknowledgeThroughFirebaseAdapter() = runBlocking {
        val host = InstrumentationRegistry.getArguments()
            .getString(FIREBASE_EMULATOR_HOST_ARGUMENT)
            ?.takeIf(String::isNotBlank)
        assumeTrue(
            "Start the Auth and Realtime Database emulators and pass " +
                "-Pandroid.testInstrumentationRunnerArguments.$FIREBASE_EMULATOR_HOST_ARGUMENT=<host>",
            host != null,
        )
        val emulatorHost = requireNotNull(host)
        val runId = UUID.randomUUID().toString().replace("-", "").take(12)
        val aliceDeviceId = "device-alice-$runId"
        val bobDeviceId = "device-bob-$runId"
        val alice = createAccount(emulatorHost, "alice-$runId")
        var bob: EmulatorAccount? = null
        var deliveryId: String? = null

        try {
            val bobAccount = createAccount(emulatorHost, "bob-$runId")
            bob = bobAccount
            registerDevice(alice, aliceDeviceId)
            registerDevice(bobAccount, bobDeviceId)

            val aliceTransport = transport(alice, aliceDeviceId)
            val bobTransport = transport(bobAccount, bobDeviceId)
            val now = System.currentTimeMillis()
            val message = SasVerificationMessage(
                transactionId = "emulator-$runId",
                senderUserId = alice.uid,
                senderDeviceId = aliceDeviceId,
                recipientUserId = bobAccount.uid,
                recipientDeviceId = bobDeviceId,
                type = SasVerificationMessageType.COMMITMENT,
                payload = "a".repeat(64),
                createdAtMillis = now,
                expiresAtMillis = now + 5 * 60 * 1000L,
            )
            deliveryId = message.deliveryId

            // A write issued while RTDB is offline must remain recoverable.
            // Reconnecting the SDK should flush the queued write to the emulator.
            FirebaseDatabase.goOffline()
            try {
                val interruptedUpload = async { aliceTransport.upload(message) }
                delay(500)
                FirebaseDatabase.goOnline()
                withTimeout(15_000) { interruptedUpload.await() }
            } finally {
                FirebaseDatabase.goOnline()
            }

            // The Firebase adapter performs the same immutable write again on
            // retry. Rules must accept an exact retry but reject changed content.
            aliceTransport.upload(message)
            aliceTransport.upload(message)
            val changedRetry = runCatching {
                aliceTransport.upload(message.copy(payload = "b".repeat(64)))
            }
            assertTrue("Changed content must not overwrite an existing SAS relay row", changedRetry.isFailure)

            val senderRead = runCatching {
                FirebaseSasVerificationRelayStore(alice.database)
                    .readInbox(bobAccount.uid, bobDeviceId)
            }
            assertTrue("A sender must not read the recipient's SAS inbox", senderRead.isFailure)

            val senderDelete = runCatching {
                FirebaseSasVerificationRelayStore(alice.database)
                    .remove(bobAccount.uid, bobDeviceId, message.deliveryId)
            }
            assertTrue("A sender must not acknowledge/delete the recipient's SAS row", senderDelete.isFailure)

            val received = bobTransport.readInbox(bobDeviceId)
            assertEquals(listOf(message), received.map { it.message })
            assertEquals(listOf(message.deliveryId), received.map { it.deliveryId })

            // Acknowledgement is also a remote write. Verify it survives an
            // offline interval and is committed after the connection returns.
            FirebaseDatabase.goOffline()
            try {
                val interruptedAcknowledgement = async {
                    bobTransport.acknowledge(bobDeviceId, message.deliveryId)
                }
                delay(500)
                FirebaseDatabase.goOnline()
                withTimeout(15_000) { interruptedAcknowledgement.await() }
            } finally {
                FirebaseDatabase.goOnline()
            }
            assertTrue(
                "Recipient acknowledgement must remove the relay row after reconnection",
                bobTransport.readInbox(bobDeviceId).isEmpty(),
            )
        } finally {
            bob?.close(bobDeviceId, deliveryId)
            alice.close(aliceDeviceId, null)
        }
    }

    private suspend fun createAccount(host: String, name: String): EmulatorAccount {
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
                "sas-relay-$name",
            ),
        )
        try {
            val auth = FirebaseAuth.getInstance(app).apply {
                useEmulator(host, AUTH_EMULATOR_PORT)
            }
            val database = FirebaseDatabase.getInstance(app).apply {
                useEmulator(host, DATABASE_EMULATOR_PORT)
            }
            val uid = requireNotNull(auth.signInAnonymously().await().user?.uid) {
                "Firebase Auth emulator did not return an account"
            }
            return EmulatorAccount(app, auth, database, uid)
        } catch (error: Throwable) {
            app.delete()
            throw error
        }
    }

    private suspend fun registerDevice(account: EmulatorAccount, deviceId: String) {
        account.database.reference
            .child("deviceKeys")
            .child(account.uid)
            .child(deviceId)
            .setValue(mapOf("registeredForEmulatorTest" to true))
            .await()
    }

    private fun transport(account: EmulatorAccount, deviceId: String) =
        FirebaseSasVerificationTransport(
            localUserId = account.uid,
            localDeviceId = deviceId,
            relayStore = FirebaseSasVerificationRelayStore(account.database),
            currentAuthenticatedUserId = { account.auth.currentUser?.uid },
        )

    private data class EmulatorAccount(
        val app: FirebaseApp,
        val auth: FirebaseAuth,
        val database: FirebaseDatabase,
        val uid: String,
    ) {
        suspend fun close(deviceId: String, deliveryId: String?) {
            runCatching {
                if (deliveryId != null) {
                    database.reference
                        .child("sasVerificationInbox")
                        .child(uid)
                        .child(deviceId)
                        .child(deliveryId)
                        .removeValue()
                        .await()
                }
                database.reference.child("deviceKeys").child(uid).child(deviceId)
                    .removeValue().await()
            }
            auth.signOut()
            app.delete()
        }
    }

    private companion object {
        const val FIREBASE_EMULATOR_HOST_ARGUMENT = "firebaseEmulatorHost"
        const val EMULATOR_PROJECT_ID = "demo-inchat-rules"
        const val AUTH_EMULATOR_PORT = 9099
        const val DATABASE_EMULATOR_PORT = 9000
    }
}
