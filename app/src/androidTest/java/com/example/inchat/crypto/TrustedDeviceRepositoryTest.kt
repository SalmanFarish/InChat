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

@RunWith(AndroidJUnit4::class)
class TrustedDeviceRepositoryTest {
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
        assertNull(
            reopened.verifiedAtMillis(
                userId, deviceId, "different-ed25519-public-key-$suffix"
            )
        )
        assertNull(reopened.verifiedAtMillis("different-user-$suffix", deviceId, identityKey))

        assertTrue(reopened.revoke(userId, deviceId))
        assertNull(reopened.verifiedAtMillis(userId, deviceId, identityKey))
        assertFalse(reopened.revoke(userId, deviceId))
    }
}
