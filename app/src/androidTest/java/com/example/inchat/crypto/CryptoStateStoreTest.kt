package com.example.inchat.crypto

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import javax.crypto.AEADBadTagException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoStateStoreTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun opaqueStateIsEncryptedAndSurvivesStoreRecreation() {
        val stateId = "test-" + UUID.randomUUID().toString()
        val plaintext = "opaque native Olm account state".toByteArray()
        val store = CryptoStateStore(context, stateId)

        try {
            assertNull(store.read())
            store.write(plaintext)

            val storedFile = stateFile(stateId)
            assertTrue(storedFile.isFile)
            val encryptedEnvelope = storedFile.readBytes()
            assertTrue(
                "The envelope should contain IV and authentication-tag overhead",
                encryptedEnvelope.size > plaintext.size,
            )
            assertFalse(
                "The persisted envelope must not contain the plaintext bytes",
                encryptedEnvelope.toString(Charsets.ISO_8859_1)
                    .contains(plaintext.toString(Charsets.ISO_8859_1)),
            )

            // A new store instance simulates reopening the app after process death.
            assertArrayEquals(plaintext, CryptoStateStore(context, stateId).read())
        } finally {
            store.delete()
        }
    }

    @Test
    fun modifiedCiphertextFailsAuthentication() {
        val stateId = "test-" + UUID.randomUUID().toString()
        val store = CryptoStateStore(context, stateId)

        try {
            store.write("secret state".toByteArray())
            val file = stateFile(stateId)
            val tampered = file.readBytes()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
            file.writeBytes(tampered)

            assertThrows(AEADBadTagException::class.java) {
                store.read()
            }
        } finally {
            store.delete()
        }
    }

    @Test
    fun nativeOlmAccountSurvivesKeystoreStoreRecreation() {
        val stateId = "native-" + UUID.randomUUID().toString()
        val originalStore = CryptoStateStore(context, stateId)

        try {
            // Rust creates the account and passes its serialized pickle directly
            // to the store's Keystore-encrypted write entry point.
            val expectedIdentityKeys =
                NativeCryptoBridge.nativePersistAccountState(originalStore)

            // A new store instance simulates reopening the app after process death.
            val reopenedStore = CryptoStateStore(context, stateId)
            assertTrue(
                "The Rust account should restore with the same public identity keys",
                NativeCryptoBridge.nativeRestoreAccountState(
                    reopenedStore,
                    expectedIdentityKeys,
                ),
            )
        } finally {
            originalStore.delete()
        }
    }

    @Test
    fun nativeOlmSessionSurvivesKeystoreStoreRecreationAndContinuesRatchet() {
        val stateId = "native-session-" + UUID.randomUUID().toString()
        val originalStore = CryptoStateStore(context, stateId)

        try {
            // Rust establishes an isolated two-device session, persists Alice's
            // ratchet state, and returns Bob's encrypted reply (never plaintext).
            val encryptedReply =
                NativeCryptoBridge.nativePersistOlmSessionState(originalStore)

            // A fresh store instance simulates a process restart.
            val reopenedStore = CryptoStateStore(context, stateId)
            assertTrue(
                "The restored Olm session should decrypt the next peer message",
                NativeCryptoBridge.nativeRestoreOlmSessionState(
                    reopenedStore,
                    encryptedReply,
                ),
            )
        } finally {
            originalStore.delete()
        }
    }

    @Test
    fun nativeDeviceIdentitySurvivesStoreRecreationAndReturnsSignedPublicKeys() {
        val deviceId = "device-" + UUID.randomUUID().toString()
        val stateId = "native-device-" + UUID.randomUUID().toString()
        val originalStore = CryptoStateStore(context, stateId)

        try {
            val firstBundle = JSONObject(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(originalStore, deviceId)
                    ?: error("Native device identity creation failed"),
            )

            // Reopening the encrypted store must restore the same device identity.
            val reopenedStore = CryptoStateStore(context, stateId)
            val restoredBundle = JSONObject(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(reopenedStore, deviceId)
                    ?: error("Native device identity restoration failed"),
            )

            assertEquals(deviceId, firstBundle.getString("deviceId"))
            assertEquals(
                firstBundle.getJSONObject("identityKeys").getString("ed25519"),
                restoredBundle.getJSONObject("identityKeys").getString("ed25519"),
            )
            assertEquals(
                firstBundle.getJSONObject("identityKeys").getString("curve25519"),
                restoredBundle.getJSONObject("identityKeys").getString("curve25519"),
            )
            assertEquals(20, firstBundle.getJSONArray("oneTimeKeys").length())
            assertNotNull(firstBundle.getJSONObject("fallbackKey"))

            // A stored account must never be silently reassigned to another device.
            assertNull(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(
                    reopenedStore,
                    "different-" + UUID.randomUUID().toString(),
                ),
            )
        } finally {
            originalStore.delete()
        }
    }

    @Test
    fun twoDeviceIdsReceiveDifferentOlmIdentityKeys() {
        val firstDeviceId = "device-a-" + UUID.randomUUID().toString()
        val secondDeviceId = "device-b-" + UUID.randomUUID().toString()
        val firstStateId = "native-device-a-" + UUID.randomUUID().toString()
        val secondStateId = "native-device-b-" + UUID.randomUUID().toString()
        val firstStore = CryptoStateStore(context, firstStateId)
        val secondStore = CryptoStateStore(context, secondStateId)

        try {
            val firstBundle = JSONObject(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(firstStore, firstDeviceId)
                    ?: error("First device identity creation failed"),
            )
            val secondBundle = JSONObject(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(secondStore, secondDeviceId)
                    ?: error("Second device identity creation failed"),
            )

            assertNotEquals(
                firstBundle.getJSONObject("identityKeys").getString("ed25519"),
                secondBundle.getJSONObject("identityKeys").getString("ed25519"),
            )
            assertNotEquals(
                firstBundle.getJSONObject("identityKeys").getString("curve25519"),
                secondBundle.getJSONObject("identityKeys").getString("curve25519"),
            )
        } finally {
            firstStore.delete()
            secondStore.delete()
        }
    }

    private fun stateFile(stateId: String): File =
        File(File(context.noBackupFilesDir, "inchat-crypto"), "$stateId.bin")
}
