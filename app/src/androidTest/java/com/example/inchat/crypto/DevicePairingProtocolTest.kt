package com.example.inchat.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DevicePairingProtocolTest {
    @Test
    fun destinationKeystoreProofIsBoundToBothDevicesAndTransaction() {
        var now = 1_800_000_000_000L
        val sourceProtocol = DevicePairingProtocol(clock = { now })
        val destinationProtocol = DevicePairingProtocol(clock = { now })
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val sourceId = "source_$suffix"
        val targetId = "target_$suffix"
        val transactionId = UUID.randomUUID().toString().replace("-", "")

        try {
            val request = sourceProtocol.createRequest(sourceId, transactionId)
            val destination = destinationProtocol.respondToRequest(request, targetId)
            val preview = sourceProtocol.inspectResponse(
                request,
                destination.encoded,
                sourceId,
                transactionId,
            )
            expectRejected {
                destinationProtocol.deriveDestinationTransferKey(targetId, transactionId)
            }
            assertEquals(targetId, preview.targetDeviceId)
            assertEquals(destination.sasCode, preview.sasCode)
            expectRejected {
                sourceProtocol.inspectResponse(
                    request,
                    JSONObject(destination.encoded).put("signature", "YWJj").toString(),
                    sourceId,
                    transactionId,
                )
            }
            expectRejected {
                destinationProtocol.confirmResponse(destination.encoded, targetId, "000000")
            }
            val changedSourceKey = JSONObject(destination.encoded)
                .put("sourceEphemeralPublicKey", "YWJj")
                .toString()
            expectRejected {
                destinationProtocol.confirmResponse(
                    changedSourceKey,
                    targetId,
                    destination.sasCode,
                )
            }
            val destinationConfirmation = destinationProtocol.confirmResponse(
                destination.encoded,
                targetId,
                destination.sasCode,
            )
            val verified = sourceProtocol.verifyConfirmation(
                request,
                destination.encoded,
                destinationConfirmation,
                sourceId,
                transactionId,
                destination.sasCode,
            )

            assertEquals(targetId, verified.targetDeviceId)
            assertEquals(transactionId, verified.transactionId)
            assertEquals(destination.sasCode, verified.sasCode)
            assertEquals(6, verified.sasCode.length)

            // Reject tampering while the source's one-time ECDH session is still
            // available; successful derivation below deliberately consumes it.
            val alteredSourceEphemeral = JSONObject(destination.encoded)
                .put("sourceEphemeralPublicKey", "YWJj")
                .toString()
            expectRejected {
                sourceProtocol.verifyConfirmation(
                    request, alteredSourceEphemeral, destinationConfirmation, sourceId,
                    transactionId, destination.sasCode,
                )
            }
            val alteredTarget = JSONObject(destination.encoded)
                .put("targetDeviceId", "attacker_$suffix")
                .toString()
            expectRejected {
                sourceProtocol.verifyConfirmation(
                    request, alteredTarget, destinationConfirmation, sourceId, transactionId,
                    destination.sasCode,
                )
            }
            expectRejected {
                sourceProtocol.verifyConfirmation(
                    request, destination.encoded, destinationConfirmation, sourceId,
                    UUID.randomUUID().toString().replace("-", ""), destination.sasCode,
                )
            }
            expectRejected {
                sourceProtocol.verifyConfirmation(
                    request, destination.encoded, destinationConfirmation, "other_$suffix",
                    transactionId, destination.sasCode,
                )
            }

            // The QR exchange never contains a transfer key. Both phones derive
            // the same 32-byte key from their short-lived ECDH private keys.
            val sourceTransferKey = sourceProtocol.deriveSourceTransferKey(verified)
            // The QR proof expires after two minutes, but once the destination
            // has signed its confirmation it may need longer to receive a large
            // relay payload. Its confirmed ECDH session remains time-bounded.
            now += 3 * 60 * 1000L
            val destinationTransferKey = destinationProtocol.deriveDestinationTransferKey(
                targetId,
                transactionId,
            )
            try {
                assertEquals(32, sourceTransferKey.size)
                assertEquals(
                    true,
                    java.security.MessageDigest.isEqual(
                        sourceTransferKey,
                        destinationTransferKey,
                    ),
                )
            } finally {
                sourceTransferKey.fill(0)
                destinationTransferKey.fill(0)
            }

        } finally {
            sourceProtocol.clearEphemeralSession(transactionId)
            destinationProtocol.clearEphemeralSession(transactionId)
            destinationProtocol.deletePairingKey(targetId)
        }
    }


    @Test
    fun destinationPairingCanResumeAfterProtocolInstanceRestart() {
        var now = 1_800_000_000_000L
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceProtocol = DevicePairingProtocol(clock = { now })
        val destinationProtocol = DevicePairingProtocol(clock = { now }, context = context)
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val sourceId = "source_$suffix"
        val targetId = "target_$suffix"
        val transactionId = UUID.randomUUID().toString().replace("-", "")

        try {
            val request = sourceProtocol.createRequest(sourceId, transactionId)
            val response = destinationProtocol.respondToRequest(request, targetId)
            val confirmation = destinationProtocol.confirmResponse(
                response.encoded,
                targetId,
                response.sasCode,
            )
            val verified = sourceProtocol.verifyConfirmation(
                request,
                response.encoded,
                confirmation,
                sourceId,
                transactionId,
                response.sasCode,
            )
            val sourceKey = sourceProtocol.deriveSourceTransferKey(verified)

            // Simulate Android recreating the screen/protocol object after process death.
            val restartedDestination = DevicePairingProtocol(clock = { now }, context = context)
            val recovery = requireNotNull(restartedDestination.pendingDestinationRecovery())
            assertEquals(sourceId, recovery.sourceDeviceId)
            assertEquals(targetId, recovery.targetDeviceId)
            assertEquals(transactionId, recovery.transactionId)
            assertEquals(request, recovery.request)
            assertEquals(response.encoded, recovery.response)
            assertEquals(confirmation, recovery.confirmation)
            assertEquals(response.sasCode, recovery.sasCode)

            val destinationKey = restartedDestination.deriveDestinationTransferKey(
                targetId,
                transactionId,
            )
            try {
                assertEquals(
                    true,
                    java.security.MessageDigest.isEqual(sourceKey, destinationKey),
                )
            } finally {
                sourceKey.fill(0)
                destinationKey.fill(0)
            }

            restartedDestination.markDestinationImportStarted(transactionId)
            restartedDestination.markDestinationIdentityImported(transactionId)
            val afterImportRestart = DevicePairingProtocol(clock = { now }, context = context)
                .pendingDestinationRecovery()
            assertEquals(true, afterImportRestart?.importStarted)
            assertEquals(true, afterImportRestart?.identityImported)
        } finally {
            sourceProtocol.clearEphemeralSession(transactionId)
            destinationProtocol.clearEphemeralSession(transactionId)
            DevicePairingProtocol(context = context).clearEphemeralSession(transactionId)
            destinationProtocol.deletePairingKey(targetId)
        }
    }

    @Test
    fun expiredPairingRequestIsRejectedBeforeKeyDerivation() {
        var now = 1_800_000_000_000L
        val sourceProtocol = DevicePairingProtocol(clock = { now })
        val destinationProtocol = DevicePairingProtocol(clock = { now })
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val sourceId = "source_$suffix"
        val targetId = "target_$suffix"
        val transactionId = UUID.randomUUID().toString().replace("-", "")
        try {
            val request = sourceProtocol.createRequest(sourceId, transactionId)
            val response = destinationProtocol.respondToRequest(request, targetId)
            val confirmation = destinationProtocol.confirmResponse(
                response.encoded,
                targetId,
                response.sasCode,
            )
            now += 2 * 60 * 1000L + 1L
            expectRejected {
                sourceProtocol.verifyConfirmation(
                    request,
                    response.encoded,
                    confirmation,
                    sourceId,
                    transactionId,
                    response.sasCode,
                )
            }
        } finally {
            sourceProtocol.clearEphemeralSession(transactionId)
            destinationProtocol.clearEphemeralSession(transactionId)
            destinationProtocol.deletePairingKey(targetId)
        }
    }

    @Test
    fun aFreshPairingRequestProducesANewComparisonCode() {
        var now = 1_800_000_000_000L
        val sourceProtocol = DevicePairingProtocol(clock = { now })
        val destinationProtocol = DevicePairingProtocol(clock = { now })
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val sourceId = "source_$suffix"
        val targetId = "target_$suffix"
        val tx1 = UUID.randomUUID().toString().replace("-", "")
        val tx2 = UUID.randomUUID().toString().replace("-", "")
        try {
            val request1 = sourceProtocol.createRequest(sourceId, tx1)
            val response1 = destinationProtocol.respondToRequest(request1, targetId)
            val request2 = sourceProtocol.createRequest(sourceId, tx2)
            val response2 = destinationProtocol.respondToRequest(request2, targetId)
            org.junit.Assert.assertNotEquals(response1.encoded, response2.encoded)
        } finally {
            sourceProtocol.clearEphemeralSession(tx1)
            sourceProtocol.clearEphemeralSession(tx2)
            destinationProtocol.clearEphemeralSession(tx1)
            destinationProtocol.clearEphemeralSession(tx2)
            destinationProtocol.deletePairingKey(targetId)
        }
    }

    private fun expectRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected pairing proof validation to reject the input")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        } catch (_: org.json.JSONException) {
            // Malformed QR payloads are also rejected.
        }
    }
}
