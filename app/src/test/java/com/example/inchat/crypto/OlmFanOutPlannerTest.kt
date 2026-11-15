package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OlmFanOutPlannerTest {
    @Test
    fun plansRecipientDevicesAndOtherSenderDevicesWithoutIncludingCurrentDevice() {
        val senderCurrent = bundle("sender-current")
        val senderTablet = bundle("sender-tablet")
        val recipientPhone = bundle("recipient-phone")
        val recipientLaptop = bundle("recipient-laptop")
        val targets = OlmFanOutPlanner.plan(
            senderUserId = "alice",
            senderDeviceId = senderCurrent.deviceId,
            recipientDevicesByUser = mapOf("bob" to listOf(recipientLaptop, recipientPhone)),
            senderDevices = listOf(senderTablet, senderCurrent),
        )
        assertEquals(
            listOf("alice::sender-tablet", "bob::recipient-laptop", "bob::recipient-phone"),
            targets.map { it.userId + "::" + it.device.deviceId },
        )
        assertFalse(targets.any { it.userId == "alice" && it.device.deviceId == senderCurrent.deviceId })
    }

    @Test
    fun deduplicatesRepeatedDeviceEntries() {
        val recipient = bundle("recipient-phone")
        val targets = OlmFanOutPlanner.plan("alice", "sender-phone", mapOf("bob" to listOf(recipient, recipient)), emptyList())
        assertEquals(1, targets.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSenderListedAsRecipient() {
        OlmFanOutPlanner.plan("alice", "sender-phone", mapOf("alice" to listOf(bundle("other-device"))), emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDeviceWithMissingSignedFallbackKey() {
        OlmFanOutPlanner.plan("alice", "sender-phone", mapOf("bob" to listOf(bundle("recipient-phone").copy(fallbackKey = null))), emptyList())
    }

    private fun bundle(deviceId: String) = PublicDeviceKeyBundle(
        deviceId = deviceId,
        ed25519IdentityKey = "ed25519-" + deviceId,
        curve25519IdentityKey = "curve25519-" + deviceId,
        curve25519IdentityKeySignature = "signature-" + deviceId,
        fallbackKey = SignedOlmPreKey("fallback", "fallback-" + deviceId, "fallback-public-" + deviceId, "fallback-signature-" + deviceId),
        oneTimeKeys = listOf(SignedOlmPreKey("one_time", "otk-" + deviceId, "otk-public-" + deviceId, "otk-signature-" + deviceId)),
    )
}
