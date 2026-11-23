package com.example.inchat.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class TrustedDeviceRepositoryTest {
    @Test
    fun verificationAndRevocationWakeObserversAcrossRepositoryInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val writer = TrustedDeviceRepository(context)
        val observer = TrustedDeviceRepository(context)

        val verified = async(start = CoroutineStart.UNDISPATCHED) {
            observer.observeTrustChanges().first()
        }
        writer.markVerified(
            peerUserId = "trust-event-user-$suffix",
            peerDeviceId = "trust-event-device-$suffix",
            peerEd25519IdentityKey = "trust-event-key-$suffix",
            verifiedAtMillis = 1_800_000_000_000L,
        )
        verified.await()

        val revoked = async(start = CoroutineStart.UNDISPATCHED) {
            observer.observeTrustChanges().first()
        }
        assertTrue(writer.revoke("trust-event-user-$suffix", "trust-event-device-$suffix"))
        revoked.await()
    }

    @Test
    fun trustPersistsIsBoundToIdentityKeyAndCanBeRevoked() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val userId = "trusted-user-$suffix"
        val deviceId = "trusted-device-$suffix"
        val identityKey = "ed25519-public-key-$suffix"
        val verifiedAt = 1_800_000_000_000L

        val repository = TrustedDeviceRepository(context)
        repository.revoke(userId, deviceId)
        assertNull(repository.verifiedAtMillis(userId, deviceId, identityKey))

        repository.markVerified(userId, deviceId, identityKey, verifiedAt)

        val reopened = TrustedDeviceRepository(context)
        assertEquals(verifiedAt, reopened.verifiedAtMillis(userId, deviceId, identityKey))
        assertTrue(reopened.isVerified(userId, deviceId, identityKey))
        assertFalse(
            reopened.isVerified(
                userId, deviceId, "different-ed25519-public-key-$suffix"
            )
        )
        assertNull(
            reopened.verifiedAtMillis(
                userId, deviceId, "different-ed25519-public-key-$suffix"
            )
        )
        assertNull(reopened.verifiedAtMillis("different-user-$suffix", deviceId, identityKey))

        assertTrue(reopened.revoke(userId, deviceId))
        assertNull(reopened.verifiedAtMillis(userId, deviceId, identityKey))
        assertFalse(reopened.isVerified(userId, deviceId, identityKey))
        assertFalse(reopened.revoke(userId, deviceId))
    }
}
