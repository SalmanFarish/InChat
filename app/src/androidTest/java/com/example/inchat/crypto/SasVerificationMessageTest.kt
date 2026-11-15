package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SasVerificationMessageTest {
    @Test
    fun canonicalMessageRoundTripsAndBindsBothDevices() {
        val original = message()
        val encoded = SasVerificationMessageCodec.encode(original)
        val decoded = SasVerificationMessageCodec.decode(
            encoded, "bob-user", "bob-device", original.createdAtMillis + 1_000,
        )
        assertEquals(original, decoded)
        assertEquals(original.deliveryId, decoded.deliveryId)
    }

    @Test
    fun rejectsExpiredFutureWrongRecipientAndMalformedMessages() {
        val original = message()
        val encoded = SasVerificationMessageCodec.encode(original)
        assertRejected {
            SasVerificationMessageCodec.decode(
                encoded, "bob-user", "bob-device", original.expiresAtMillis,
            )
        }
        assertRejected {
            SasVerificationMessageCodec.decode(
                encoded, "bob-user", "other-device", original.createdAtMillis + 1,
            )
        }
        val future = original.copy(createdAtMillis = 9_000_000, expiresAtMillis = 9_000_100)
        assertRejected {
            SasVerificationMessageCodec.decode(
                SasVerificationMessageCodec.encode(future),
                "bob-user", "bob-device", 1_000,
            )
        }
        assertRejected {
            SasVerificationMessageCodec.decode(
                encoded.dropLast(1) + ",\"extra\":true}",
                "bob-user", "bob-device", original.createdAtMillis + 1,
            )
        }
    }

    @Test
    fun durableLedgerRejectsReplayAndConflictingPayloadAfterDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sas-verification-replay-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val original = message()
        try {
            val ledger = SasVerificationMessageLedger(
                first.sasVerificationMessageDao(), "bob-user", "bob-device",
            )
            assertEquals(SasMessageRegistration.NEW, ledger.register(original, 1_001))
        } finally {
            first.close()
        }

        val second = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = SasVerificationMessageLedger(
                second.sasVerificationMessageDao(), "bob-user", "bob-device",
            )
            assertEquals(SasMessageRegistration.DUPLICATE, ledger.register(original, 1_002))
            assertEquals(
                SasMessageRegistration.CONFLICT,
                ledger.register(original.copy(payload = "b".repeat(64)), 1_003),
            )
            assertEquals(1, ledger.pruneExpired(original.expiresAtMillis))
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    private fun message() = SasVerificationMessage(
        transactionId = "sas-transaction-1",
        senderUserId = "alice-user",
        senderDeviceId = "alice-device",
        recipientUserId = "bob-user",
        recipientDeviceId = "bob-device",
        type = SasVerificationMessageType.COMMITMENT,
        payload = "a".repeat(64),
        createdAtMillis = 1_000,
        expiresAtMillis = 601_000,
    )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected invalid SAS message to be rejected", rejected)
    }
}
