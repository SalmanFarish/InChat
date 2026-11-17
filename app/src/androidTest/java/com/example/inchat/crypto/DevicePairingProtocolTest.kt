package com.example.inchat.crypto

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
            expectRejected {
                destinationProtocol.confirmResponse(destination.encoded, targetId, "000000")
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

            // The QR exchange never contains a transfer key. Both phones derive
            // the same 32-byte key from their short-lived ECDH private keys.
            val sourceTransferKey = sourceProtocol.deriveSourceTransferKey(verified)
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

            val altered = JSONObject(destination.encoded)
                .put("targetDeviceId", "attacker_$suffix")
                .toString()
            expectRejected {
                sourceProtocol.verifyConfirmation(
                    request, altered, destinationConfirmation, sourceId, transactionId,
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

            now += 2 * 60 * 1000L + 1L
            expectRejected {
                sourceProtocol.verifyConfirmation(
                    request, destination.encoded, destinationConfirmation, sourceId,
                    transactionId, destination.sasCode,
                )
            }
        } finally {
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
