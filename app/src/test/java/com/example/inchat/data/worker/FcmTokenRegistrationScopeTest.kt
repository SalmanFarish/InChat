package com.example.inchat.data.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmTokenRegistrationScopeTest {
    @Test
    fun requiresAnExistingValidDeviceIdBeforeRegistrationCanComplete() {
        assertTrue(isReadyForDeviceScopedFcmRegistration("device-123", lookupFailed = false))
        assertFalse(isReadyForDeviceScopedFcmRegistration(null, lookupFailed = false))
        assertFalse(isReadyForDeviceScopedFcmRegistration("", lookupFailed = false))
        assertFalse(isReadyForDeviceScopedFcmRegistration("invalid/device", lookupFailed = false))
        assertFalse(isReadyForDeviceScopedFcmRegistration("device-123", lookupFailed = true))
    }
}
