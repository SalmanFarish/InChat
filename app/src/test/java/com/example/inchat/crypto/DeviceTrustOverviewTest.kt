package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTrustOverviewTest {
    @Test
    fun emptyDirectoryExplainsThatPeerMustOpenInChat() {
        val overview = DeviceTrustOverview.from(emptyList())

        assertEquals("No published devices yet", overview.title)
        assertTrue(overview.description.contains("open InChat once"))
        assertFalse(overview.requiresReview)
    }

    @Test
    fun firstUsePinIsNeverPresentedAsSasVerification() {
        val overview = DeviceTrustOverview.from(
            listOf(DeviceTrustStatus.FIRST_USE_PINNED, DeviceTrustStatus.SAS_VERIFIED),
        )

        assertEquals("First-use identity pinned", overview.title)
        assertTrue(overview.description.contains("Safety numbers have not been compared"))
        assertFalse(overview.requiresReview)
    }

    @Test
    fun changedOrRevokedIdentityTakesPriorityOverOtherDeviceStates() {
        val overview = DeviceTrustOverview.from(
            listOf(
                DeviceTrustStatus.SAS_VERIFIED,
                DeviceTrustStatus.FIRST_USE_PINNED,
                DeviceTrustStatus.BLOCKED_OR_CHANGED,
            ),
        )

        assertEquals("Identity needs review", overview.title)
        assertTrue(overview.requiresReview)
        assertTrue(overview.description.contains("changed or was blocked"))
    }

    @Test
    fun unseenDevicesAreNotReportedAsVerified() {
        val overview = DeviceTrustOverview.from(
            listOf(DeviceTrustStatus.SAS_VERIFIED, DeviceTrustStatus.NOT_SEEN),
        )

        assertEquals("Not yet checked on this phone", overview.title)
        assertFalse(overview.requiresReview)
    }

    @Test
    fun verifiedSummaryRequiresEveryPublishedDeviceToBeSasVerified() {
        val overview = DeviceTrustOverview.from(
            listOf(DeviceTrustStatus.SAS_VERIFIED, DeviceTrustStatus.SAS_VERIFIED),
        )

        assertEquals("All devices SAS-verified", overview.title)
        assertTrue(overview.description.contains("compared and confirmed"))
        assertFalse(overview.requiresReview)
    }
}
