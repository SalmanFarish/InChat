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

    private fun stateFile(stateId: String): File =
        File(File(context.noBackupFilesDir, "inchat-crypto"), "$stateId.bin")
}
