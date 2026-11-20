package com.example.inchat.crypto

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Entity
import androidx.room.Index
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.CachedMessageEntity
import com.example.inchat.data.local.LocalChatDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Exact plaintext version-10 table shape, used only to construct a migration fixture. */
@Entity(
    tableName = "encrypted_olm_messages",
    primaryKeys = ["ownerUserId", "conversationId", "messageId"],
    indices = [Index(value = ["ownerUserId", "conversationId", "sentAtMillis"])],
)
data class VersionTenPlaintextOlmMessage(
    val ownerUserId: String,
    val conversationId: String,
    val messageId: String,
    val conversationPeerUserId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val sentAtMillis: Long,
    val text: String,
)

@Dao
interface VersionTenPlaintextOlmMessageDao {
    @Insert
    suspend fun insert(message: VersionTenPlaintextOlmMessage)
}

@Database(
    entities = [
        CachedMessageEntity::class,
        OlmDeviceDeliveryEntity::class,
        OlmDeliveryReceiptEntity::class,
        OlmInboundReceiptEntity::class,
        SasVerificationMessageEntity::class,
        VersionTenPlaintextOlmMessage::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class VersionTenOlmMessagesDatabase : RoomDatabase() {
    abstract fun plaintextMessages(): VersionTenPlaintextOlmMessageDao
}

class EncryptedOlmMessageMigrationTest {
    @Test
    fun versionTenPlaintextRowsAreEncryptedAndPreservedDuringUpgrade() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "olm-message-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)

        val legacy = Room.databaseBuilder(
            context,
            VersionTenOlmMessagesDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()

        val original = VersionTenPlaintextOlmMessage(
            ownerUserId = "bob",
            conversationId = "alice_bob",
            messageId = "legacy-message-1",
            conversationPeerUserId = "alice",
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recipientUserId = "bob",
            recipientDeviceId = "recipient-device",
            sentAtMillis = 1_800_000_000_000L,
            text = "Legacy decrypted content must be encrypted during migration.",
        )

        try {
            kotlinx.coroutines.runBlocking {
                legacy.plaintextMessages().insert(original)
            }
        } finally {
            legacy.close()
        }

        val upgraded = Room.databaseBuilder(
            context,
            LocalChatDatabase::class.java,
            databaseName,
        )
            .addMigrations(LocalChatDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()

        try {
            val encrypted = requireNotNull(
                kotlinx.coroutines.runBlocking {
                    upgraded.encryptedOlmMessageDao().getMessage(
                        original.ownerUserId,
                        original.conversationId,
                        original.messageId,
                    )
                },
            )
            val decrypted = EncryptedOlmMessageVault().decrypt(encrypted)
            assertEquals(original.text, decrypted.text)
            assertEquals(original.senderDeviceId, decrypted.senderDeviceId)
            assertEquals(original.recipientDeviceId, decrypted.recipientDeviceId)
            assertEquals(EncryptedOlmMessageVault.CONTENT_ENCRYPTION_VERSION, encrypted.contentEncryptionVersion)
            assertFalse(encrypted.textCiphertextBase64.contains(original.text))

            val columnNames = mutableSetOf<String>()
            upgraded.openHelper.readableDatabase.query(
                "PRAGMA table_info(encrypted_olm_messages)",
            ).use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columnNames += cursor.getString(nameColumn)
            }
            assertFalse("Old plaintext text column must be removed", "text" in columnNames)
            assertTrue("Encrypted ciphertext column must exist", "textCiphertextBase64" in columnNames)
            assertTrue("Nonce column must exist", "textNonceBase64" in columnNames)
        } finally {
            upgraded.close()
            context.deleteDatabase(databaseName)
        }
    }
}
