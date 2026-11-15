package com.example.inchat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCryptoBridgeTest {

    @Test
    fun nativeLibraryLoadsAndReportsPinnedVersion() {
        assertEquals(
            "0.11.2",
            NativeCryptoBridge.nativeVodozemacVersion()
        )
    }

    @Test
    fun nativeOlmSelfTestExchangesEncryptedMessagesBetweenTwoDevices() {
        assertTrue(
            "The native two-device Olm self-test failed",
            NativeCryptoBridge.nativeOlmSelfTest()
        )
    }
}
