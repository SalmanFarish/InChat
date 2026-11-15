package com.example.inchat.crypto

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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
            assertTrue("The envelope should contain IV and authentication-tag overhead",
                encryptedEnvelope.size > plaintext.size)
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

    private fun stateFile(stateId: String): File =
        File(File(context.noBackupFilesDir, "inchat-crypto"), "$stateId.bin")
}
