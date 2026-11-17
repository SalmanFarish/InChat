package com.example.inchat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            val verified = protocol.verifyResponse(
                request,
                destination.encoded,
                sourceId,
                transactionId,
            )

            assertEquals(targetId, verified.targetDeviceId)
            assertEquals(transactionId, verified.transactionId)
            assertEquals(destination.sasCode, verified.sasCode)
            assertEquals(6, verified.sasCode.length)

            val altered = JSONObject(destination.encoded)
                .put("targetDeviceId", "attacker_$suffix")
                .toString()
            expectRejected {
                protocol.verifyResponse(request, altered, sourceId, transactionId)
            }
            expectRejected {
                protocol.verifyResponse(
                    request,
                    destination.encoded,
                    sourceId,
                    UUID.randomUUID().toString().replace("-", ""),
                )
            }
            expectRejected {
                protocol.verifyResponse(
                    request,
                    destination.encoded,
                    "other_$suffix",
                    transactionId,
                )
            }

            now += 2 * 60 * 1000L + 1L
            expectRejected {
                protocol.verifyResponse(request, destination.encoded, sourceId, transactionId)
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
            assertNotEquals(response1.sasCode, response2.sasCode)
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
