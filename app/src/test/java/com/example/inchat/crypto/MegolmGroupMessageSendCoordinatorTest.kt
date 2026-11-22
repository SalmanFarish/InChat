package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MegolmGroupMessageSendCoordinatorTest {
    @Test
    fun startsKeyDistributionAndWaitsWithoutCallingEncryptedSender() = runBlocking {
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        var distributeCalls = 0
        var sendCalls = 0
        var state: OlmGroupKeyDistributionReadinessState =
            OlmGroupKeyDistributionReadinessState.Missing(distributionId(membership, session.sessionId))

        val coordinator = coordinator(
            membershipReads = listOf(membership, membership),
            targets = targets,
            session = session,
            readiness = { state },
            distribute = { groupId, distributionId, issuedAt ->
                assertEquals("group-1", groupId)
                assertEquals(distributionId(membership, session.sessionId), distributionId)
                assertEquals(555L, issuedAt)
                distributeCalls++
                state = OlmGroupKeyDistributionReadinessState.AwaitingAcknowledgements(
                    distributionId,
                    setOf("bob" to "bob-phone"),
                )
                MegolmGroupKeyDistributionResult(
                    distributionId = distributionId,
                    groupId = groupId,
                    membershipEpoch = membership.epoch,
                    megolmSessionId = session.sessionId,
                    recipientDeviceCount = targets.size,
                    newlyQueuedDeliveryCount = 1,
                    skippedUntrustedDeviceCount = 0,
                )
            },
            send = { _, _, _, _, _ ->
                sendCalls++
                error("Message must wait for the key acknowledgement")
            },
        )

        val result = coordinator.send("group-1", "message-1", "secret text", 777L)

        assertTrue(result is MegolmGroupMessageSendOutcome.AwaitingKeyAcknowledgements)
        val waiting = result as MegolmGroupMessageSendOutcome.AwaitingKeyAcknowledgements
        assertEquals(setOf("bob" to "bob-phone"), waiting.pendingTargets)
        assertEquals(1, waiting.newlyQueuedKeyDeliveries)
        assertEquals(1, distributeCalls)
        assertEquals(0, sendCalls)
    }

    @Test
    fun reusesReadyKeyDistributionWithoutRedistributingAndQueuesEncryptedMessage() = runBlocking {
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        val token = token(membership, session, targets)
        var distributeCalls = 0
        var sendCalls = 0

        val coordinator = coordinator(
            membershipReads = listOf(membership, membership),
            targets = targets,
            session = session,
            readiness = {
                OlmGroupKeyDistributionReadinessState.Ready(token)
            },
            distribute = { _, _, _ ->
                distributeCalls++
                error("A ready distribution must not be recreated")
            },
            send = { snapshot, id, text, sentAt, receivedToken ->
                assertEquals(membership, snapshot)
                assertEquals("message-2", id)
                assertEquals("hello", text)
                assertEquals(778L, sentAt)
                assertEquals(token, receivedToken)
                sendCalls++
                MegolmGroupMessageOutboxCommitResult(
                    messageId = id,
                    groupId = snapshot.groupId,
                    membershipEpoch = snapshot.epoch,
                    queuedDeviceDeliveries = 1,
                    recipientDeviceCount = 1,
                )
            },
        )

        val result = coordinator.send("group-1", "message-2", "hello", 778L)

        assertTrue(result is MegolmGroupMessageSendOutcome.Queued)
        assertEquals(0, distributeCalls)
        assertEquals(1, sendCalls)
        assertEquals("message-2", (result as MegolmGroupMessageSendOutcome.Queued).result.messageId)
    }

    @Test
    fun retriesReadinessAfterFirstDistributionAndSendsOnlyIfAcknowledgementsAlreadyArrived() = runBlocking {
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        val token = token(membership, session, targets)
        var readinessCalls = 0
        var sends = 0
        val coordinator = coordinator(
            membershipReads = listOf(membership, membership, membership),
            targets = targets,
            session = session,
            readiness = {
                readinessCalls++
                if (readinessCalls == 1) {
                    OlmGroupKeyDistributionReadinessState.Missing(distributionId(membership, session.sessionId))
                } else {
                    OlmGroupKeyDistributionReadinessState.Ready(token)
                }
            },
            distribute = { groupId, distributionId, issuedAt ->
                assertEquals("group-1", groupId)
                MegolmGroupKeyDistributionResult(
                    distributionId = distributionId,
                    groupId = groupId,
                    membershipEpoch = membership.epoch,
                    megolmSessionId = session.sessionId,
                    recipientDeviceCount = targets.size,
                    newlyQueuedDeliveryCount = 1,
                    skippedUntrustedDeviceCount = 0,
                )
            },
            send = { snapshot, id, _, _, _ ->
                sends++
                MegolmGroupMessageOutboxCommitResult(id, snapshot.groupId, snapshot.epoch, 1, 1)
            },
        )

        val result = coordinator.send("group-1", "message-3", "hello", 779L)

        assertTrue(result is MegolmGroupMessageSendOutcome.Queued)
        assertEquals(2, readinessCalls)
        assertEquals(1, sends)
    }

    @Test
    fun rejectsPendingAcknowledgementsForDifferentDistributionOrUnknownDevice() = runBlocking {
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        val mismatched = OlmGroupKeyDistributionReadinessState.AwaitingAcknowledgements(
            distributionId = "different-distribution",
            pendingTargets = setOf("mallory" to "unknown-device"),
        )
        val coordinator = coordinator(
            membershipReads = listOf(membership),
            targets = targets,
            session = session,
            readiness = { mismatched },
            distribute = { _, _, _ -> error("Existing state must not trigger redistributing") },
            send = { _, _, _, _, _ -> error("Mismatched readiness must not send") },
        )

        var rejected = false
        try {
            coordinator.send("group-1", "message-x", "hello", 782L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun doesNotRedistributeWhenAnExistingDistributionIsWaiting() = runBlocking {
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        var distributeCalls = 0
        val waiting = OlmGroupKeyDistributionReadinessState.AwaitingAcknowledgements(
            distributionId(membership, session.sessionId),
            setOf("bob" to "bob-phone"),
        )
        val coordinator = coordinator(
            membershipReads = listOf(membership),
            targets = targets,
            session = session,
            readiness = { waiting },
            distribute = { _, _, _ ->
                distributeCalls++
                error("Existing pending distribution must not be recreated")
            },
            send = { _, _, _, _, _ -> error("Pending key cannot send a message") },
        )

        val result = coordinator.send("group-1", "message-4", "hello", 780L)

        assertTrue(result is MegolmGroupMessageSendOutcome.AwaitingKeyAcknowledgements)
        assertEquals(0, distributeCalls)
    }

    @Test
    fun refusesToSendWhenTrustedRosterChangesAfterKeyReadiness() = runBlocking {
        val membership = membership()
        val targetSet = targets()
        val session = session(membership, targetSet)
        val token = token(membership, session, targetSet)
        var targetReads = 0
        var sendCalls = 0
        val coordinator = coordinator(
            membershipReads = listOf(membership, membership),
            targets = targetSet,
            session = session,
            currentTargets = {
                targetReads++
                if (targetReads == 1) targetSet else targetSet + ("bob" to "bob-new-phone")
            },
            readiness = { OlmGroupKeyDistributionReadinessState.Ready(token) },
            distribute = { _, _, _ -> error("Ready token should not redistribute") },
            send = { _, _, _, _, _ ->
                sendCalls++
                error("Roster drift must block message send")
            },
        )

        var rejected = false
        try {
            coordinator.send("group-1", "message-5", "hello", 781L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(0, sendCalls)
    }

    @Test
    fun accountSwitchDuringTrustedRosterLookupPreventsNativeSessionCreation() = runBlocking {
        var currentUser = "alice"
        var ensureCalls = 0
        var sendCalls = 0
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        val token = token(membership, session, targets)
        val coordinator = coordinator(
            membershipReads = listOf(membership),
            targets = targets,
            session = session,
            readiness = { OlmGroupKeyDistributionReadinessState.Ready(token) },
            distribute = { _, _, _ -> error("Identity switch must be caught before distribution") },
            send = { _, _, _, _, _ -> sendCalls++; error("Identity switch must block sending") },
            currentTargets = { currentUser = "mallory"; targets },
            authenticatedUser = { currentUser },
            ensureSession = { _, _ -> ensureCalls++; session },
        )

        var rejected = false
        try {
            coordinator.send("group-1", "message-account-switch", "hello", 783L)
        } catch (expected: IllegalArgumentException) {
            rejected = expected.message.orEmpty().contains("identity changed during trusted-device lookup")
        }
        assertTrue("Account replacement must be caught before native session creation", rejected)
        assertEquals(0, ensureCalls)
        assertEquals(0, sendCalls)
    }

    @Test
    fun deviceIdentityChangeDuringTrustedRosterLookupPreventsNativeSessionCreation() = runBlocking {
        var currentDevice = "alice-phone"
        var ensureCalls = 0
        var sendCalls = 0
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        val token = token(membership, session, targets)
        val coordinator = coordinator(
            membershipReads = listOf(membership),
            targets = targets,
            session = session,
            readiness = { OlmGroupKeyDistributionReadinessState.Ready(token) },
            distribute = { _, _, _ -> error("Device switch must be caught before distribution") },
            send = { _, _, _, _, _ -> sendCalls++; error("Device switch must block sending") },
            currentTargets = { currentDevice = "other-device"; targets },
            localDevice = { currentDevice },
            ensureSession = { _, _ -> ensureCalls++; session },
        )

        var rejected = false
        try {
            coordinator.send("group-1", "message-device-switch", "hello", 784L)
        } catch (expected: IllegalArgumentException) {
            rejected = expected.message.orEmpty().contains("identity changed during trusted-device lookup")
        }
        assertTrue("Device identity replacement must be caught before native session creation", rejected)
        assertEquals(0, ensureCalls)
        assertEquals(0, sendCalls)
    }

    @Test
    fun accountSwitchDuringFinalMembershipRefreshPreventsMegolmSend() = runBlocking {
        var currentUser = "alice"
        var sendCalls = 0
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        val token = token(membership, session, targets)
        val coordinator = coordinator(
            membershipReads = listOf(membership, membership),
            targets = targets,
            session = session,
            readiness = { OlmGroupKeyDistributionReadinessState.Ready(token) },
            distribute = { _, _, _ -> error("Ready key distribution must not be recreated") },
            send = { _, _, _, _, _ -> sendCalls++; error("Account switch must block send") },
            authenticatedUser = { currentUser },
            onMembershipRead = { read -> if (read == 2) currentUser = "mallory" },
        )

        var rejected = false
        try {
            coordinator.send("group-1", "message-final-account-switch", "hello", 785L)
        } catch (expected: IllegalArgumentException) {
            rejected = expected.message.orEmpty().contains("identity changed before encrypted message send")
        }

        assertTrue("Account replacement during final membership refresh must block send", rejected)
        assertEquals(0, sendCalls)
    }

    @Test
    fun deviceSwitchDuringFinalMembershipRefreshPreventsMegolmSend() = runBlocking {
        var currentDevice = "alice-phone"
        var sendCalls = 0
        val membership = membership()
        val targets = targets()
        val session = session(membership, targets)
        val token = token(membership, session, targets)
        val coordinator = coordinator(
            membershipReads = listOf(membership, membership),
            targets = targets,
            session = session,
            readiness = { OlmGroupKeyDistributionReadinessState.Ready(token) },
            distribute = { _, _, _ -> error("Ready key distribution must not be recreated") },
            send = { _, _, _, _, _ -> sendCalls++; error("Device switch must block send") },
            localDevice = { currentDevice },
            onMembershipRead = { read -> if (read == 2) currentDevice = "replacement-device" },
        )

        var rejected = false
        try {
            coordinator.send("group-1", "message-final-device-switch", "hello", 786L)
        } catch (expected: IllegalArgumentException) {
            rejected = expected.message.orEmpty().contains("identity changed before encrypted message send")
        }

        assertTrue("Device replacement during final membership refresh must block send", rejected)
        assertEquals(0, sendCalls)
    }

    private fun coordinator(
        membershipReads: List<GroupCryptoMembershipSnapshot?>,
        targets: Set<Pair<String, String>>,
        session: OutboundMegolmSessionInfo,
        readiness: () -> OlmGroupKeyDistributionReadinessState,
        distribute: suspend (String, String, Long) -> MegolmGroupKeyDistributionResult,
        send: suspend (
            GroupCryptoMembershipSnapshot,
            String,
            String,
            Long,
            OlmGroupKeyDistributionReadinessToken,
        ) -> MegolmGroupMessageOutboxCommitResult,
        currentTargets: suspend (GroupCryptoMembershipSnapshot) -> Set<Pair<String, String>> = { targets },
        authenticatedUser: () -> String = { "alice" },
        localDevice: () -> String = { "alice-phone" },
        ensureSession: (GroupCryptoMembershipSnapshot, Set<Pair<String, String>>) -> OutboundMegolmSessionInfo = { _, requestedTargets ->
            assertEquals(targets, requestedTargets)
            session
        },
        onMembershipRead: (Int) -> Unit = {},
    ): MegolmGroupMessageSendCoordinator {
        val remainingMembershipReads = membershipReads.toMutableList()
        var membershipReadCount = 0
        return MegolmGroupMessageSendCoordinator(
            membershipForGroup = {
                membershipReadCount++
                onMembershipRead(membershipReadCount)
                if (remainingMembershipReads.isEmpty()) null else remainingMembershipReads.removeAt(0)
            },
            authenticatedUserId = authenticatedUser,
            localDeviceId = localDevice,
            currentTrustedTargetDevices = currentTargets,
            ensureOutboundSession = ensureSession,
            evaluateKeyReadiness = { _, _, _, _ -> readiness() },
            distributeKey = distribute,
            sendEncryptedMessage = send,
            nowMillis = { 555L },
        )
    }

    private fun membership() = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = 7L,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private fun targets() = setOf("alice" to "alice-tablet", "bob" to "bob-phone")

    private fun session(
        membership: GroupCryptoMembershipSnapshot,
        targets: Set<Pair<String, String>>,
    ) = OutboundMegolmSessionInfo(
        sessionId = "1234567890abcdef",
        groupId = membership.groupId,
        membershipEpoch = membership.epoch,
        created = false,
        targetRosterFingerprint = GroupDeviceRosterFingerprint.create(membership, targets),
    )

    private fun distributionId(membership: GroupCryptoMembershipSnapshot, sessionId: String) =
        OlmGroupSessionKeyDistributionId.create(
            membership,
            "alice",
            "alice-phone",
            sessionId,
        )

    private fun token(
        membership: GroupCryptoMembershipSnapshot,
        session: OutboundMegolmSessionInfo,
        targets: Set<Pair<String, String>>,
    ) = OlmGroupKeyDistributionReadinessToken(
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        distributionId = distributionId(membership, session.sessionId),
        groupId = membership.groupId,
        membershipEpoch = membership.epoch,
        megolmSessionId = session.sessionId,
        targetDevices = targets,
    )
}
