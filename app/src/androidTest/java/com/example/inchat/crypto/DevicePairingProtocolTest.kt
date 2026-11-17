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
        val protocol = DevicePairingProtocol(clock = { now })
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val sourceId = "source_$suffix"
        val targetId = "target_$suffix"
        val transactionId = UUID.randomUUID().toString().replace("-", "")

        try {
            val request = protocol.createRequest(sourceId, transactionId)
            val destination = protocol.respondToRequest(request, targetId)
            expectRejected {
                protocol.confirmResponse(destination.encoded, targetId, "000000")
            }
            val destinationConfirmation = protocol.confirmResponse(
                destination.encoded,
                targetId,
                destination.sasCode,
            )
            val verified = protocol.verifyConfirmation(
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

            val altered = JSONObject(destination.encoded)
                .put("targetDeviceId", "attacker_$suffix")
                .toString()
            expectRejected {
                protocol.verifyConfirmation(
                    request, altered, destinationConfirmation, sourceId, transactionId,
                    destination.sasCode,
                )
            }
            expectRejected {
                protocol.verifyConfirmation(
                    request, destination.encoded, destinationConfirmation, sourceId,
                    UUID.randomUUID().toString().replace("-", ""), destination.sasCode,
                )
            }
            expectRejected {
                protocol.verifyConfirmation(
                    request, destination.encoded, destinationConfirmation, "other_$suffix",
                    transactionId, destination.sasCode,
                )
            }

            now += 2 * 60 * 1000L + 1L
            expectRejected {
                protocol.verifyConfirmation(
                    request, destination.encoded, destinationConfirmation, sourceId,
                    transactionId, destination.sasCode,
                )
            }
        } finally {
            protocol.deletePairingKey(targetId)
        }
    }

    @Test
    fun aFreshPairingRequestProducesANewComparisonCode() {
        var now = 1_800_000_000_000L
        val protocol = DevicePairingProtocol(clock = { now })
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val sourceId = "source_$suffix"
        val targetId = "target_$suffix"
        val tx1 = UUID.randomUUID().toString().replace("-", "")
        val tx2 = UUID.randomUUID().toString().replace("-", "")
        try {
            val request1 = protocol.createRequest(sourceId, tx1)
            val response1 = protocol.respondToRequest(request1, targetId)
            val request2 = protocol.createRequest(sourceId, tx2)
            val response2 = protocol.respondToRequest(request2, targetId)
            org.junit.Assert.assertNotEquals(response1.encoded, response2.encoded)
        } finally {
            protocol.deletePairingKey(targetId)
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
