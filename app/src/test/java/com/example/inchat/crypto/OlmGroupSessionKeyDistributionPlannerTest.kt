package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupSessionKeyDistributionPlannerTest {
    @Test
    fun plansOnlyTrustedDevicesOfCurrentMembersAndOtherSenderDevices() {
        val senderCurrent = bundle("sender-current")
        val senderTablet = bundle("sender-tablet")
        val recipientPhone = bundle("recipient-phone")
        val recipientLaptop = bundle("recipient-laptop")
        val thirdMemberPhone = bundle("third-member-phone")

        val trusted = setOf(
            trustKey("alice", senderTablet),
            trustKey("bob", recipientPhone),
            trustKey("carol", thirdMemberPhone),
        )
        val plan = OlmGroupSessionKeyDistributionPlanner.plan(
            senderUserId = "alice",
            senderDeviceId = senderCurrent.deviceId,
            currentMemberUserIds = setOf("alice", "bob", "carol"),
            recipientDevicesByUser = mapOf(
                "bob" to listOf(recipientLaptop, recipientPhone),
                "carol" to listOf(thirdMemberPhone),
            ),
            senderDevices = listOf(senderCurrent, senderTablet),
            trustVerifier = verifier(trusted),
        )

        assertEquals(
            listOf(
                "alice::sender-tablet",
                "bob::recipient-phone",
                "carol::third-member-phone",
            ),
            plan.targets.map { it.userId + "::" + it.device.deviceId }.sorted(),
        )
        assertEquals(1, plan.skippedUntrustedDeviceCount)
        assertFalse(plan.targets.any { it.device.deviceId == "sender-current" })
        assertFalse(plan.targets.any { it.device.deviceId == "recipient-laptop" })
    }

    @Test
    fun rejectsDirectoryResultsForNonMembersOrMissingMembers() {
        val sender = bundle("sender-current")

        assertRejected {
            OlmGroupSessionKeyDistributionPlanner.plan(
                senderUserId = "alice",
                senderDeviceId = sender.deviceId,
                currentMemberUserIds = setOf("alice", "bob"),
                recipientDevicesByUser = mapOf("mallory" to listOf(bundle("mallory-phone"))),
                senderDevices = listOf(sender),
                trustVerifier = verifier(emptySet()),
            )
        }
        assertRejected {
            OlmGroupSessionKeyDistributionPlanner.plan(
                senderUserId = "alice",
                senderDeviceId = sender.deviceId,
                currentMemberUserIds = setOf("alice", "bob", "carol"),
                recipientDevicesByUser = mapOf("bob" to listOf(bundle("bob-phone"))),
                senderDevices = listOf(sender),
                trustVerifier = verifier(emptySet()),
            )
        }
    }

    @Test
    fun rejectsSenderOutsideMembershipAndNoTrustedDeliveryTargets() {
        val sender = bundle("sender-current")

        assertRejected {
            OlmGroupSessionKeyDistributionPlanner.plan(
                senderUserId = "alice",
                senderDeviceId = sender.deviceId,
                currentMemberUserIds = setOf("bob", "carol"),
                recipientDevicesByUser = mapOf(
                    "bob" to listOf(bundle("bob-phone")),
                    "carol" to listOf(bundle("carol-phone")),
                ),
                senderDevices = listOf(sender),
                trustVerifier = verifier(emptySet()),
            )
        }

        assertRejected {
            OlmGroupSessionKeyDistributionPlanner.plan(
                senderUserId = "alice",
                senderDeviceId = sender.deviceId,
                currentMemberUserIds = setOf("alice", "bob"),
                recipientDevicesByUser = mapOf("bob" to listOf(bundle("bob-phone"))),
                senderDevices = listOf(sender),
                trustVerifier = verifier(emptySet()),
            )
        }
    }

    @Test
    fun enforcesTheSharedFanOutLimitAtThePlanningBoundary() {
        val senderCurrent = bundle("sender-current")
        val senderCompanions = (1..4).map { bundle("sender-tablet-$it") }
        val remoteUsers = (1..31).map { "member-$it" }
        val remoteDevices = remoteUsers.associateWith { userId ->
            (1..16).map { bundle("$userId-device-$it") }
        }
        val trustedAtLimit = buildSet {
            senderCompanions.forEach { add(trustKey("alice", it)) }
            remoteDevices.forEach { (userId, devices) ->
                devices.forEach { add(trustKey(userId, it)) }
            }
        }

        val exactLimit = OlmGroupSessionKeyDistributionPlanner.plan(
            senderUserId = "alice",
            senderDeviceId = senderCurrent.deviceId,
            currentMemberUserIds = (listOf("alice") + remoteUsers).toSet(),
            recipientDevicesByUser = remoteDevices,
            senderDevices = listOf(senderCurrent) + senderCompanions,
            trustVerifier = verifier(trustedAtLimit),
        )
        assertEquals(500, exactLimit.targets.size)

        val overflowingUsers = (1..32).map { "overflow-member-$it" }
        val overflowingDevices = overflowingUsers.associateWith { userId ->
            (1..16).map { bundle("$userId-device-$it") }
        }
        val trustedOverflow = overflowingDevices.flatMap { (userId, devices) ->
            devices.map { trustKey(userId, it) }
        }.toSet()

        assertRejected {
            OlmGroupSessionKeyDistributionPlanner.plan(
                senderUserId = "alice",
                senderDeviceId = senderCurrent.deviceId,
                currentMemberUserIds = (listOf("alice") + overflowingUsers).toSet(),
                recipientDevicesByUser = overflowingDevices,
                senderDevices = listOf(senderCurrent),
                trustVerifier = verifier(trustedOverflow),
            )
        }
    }

    private fun verifier(trusted: Set<String>) = OlmDeviceTrustVerifier {
            peerUserId, peerDeviceId, identityKey ->
        trustKey(peerUserId, peerDeviceId, identityKey) in trusted
    }

    private fun trustKey(userId: String, bundle: PublicDeviceKeyBundle): String =
        trustKey(userId, bundle.deviceId, bundle.ed25519IdentityKey)

    private fun trustKey(userId: String, deviceId: String, identityKey: String): String =
        "$userId::$deviceId::$identityKey"

    private fun bundle(deviceId: String) = PublicDeviceKeyBundle(
        deviceId = deviceId,
        ed25519IdentityKey = "ed25519-$deviceId",
        curve25519IdentityKey = "curve25519-$deviceId",
        curve25519IdentityKeySignature = "signature-$deviceId",
        fallbackKey = SignedOlmPreKey(
            "fallback",
            "fallback-$deviceId",
            "fallback-public-$deviceId",
            "fallback-signature-$deviceId",
        ),
        oneTimeKeys = listOf(
            SignedOlmPreKey(
                "one_time",
                "otk-$deviceId",
                "otk-public-$deviceId",
                "otk-signature-$deviceId",
            ),
        ),
    )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected invalid group key fan-out plan to be rejected", rejected)
    }
}
