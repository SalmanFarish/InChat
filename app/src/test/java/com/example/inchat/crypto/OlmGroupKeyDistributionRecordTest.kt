package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OlmGroupKeyDistributionRecordTest {
    @Test fun canonicalTargetsIgnoreInputOrder() {
        val a = record(listOf("bob" to "bob-phone", "alice" to "alice-tablet")).toEntity()
        val b = record(listOf("alice" to "alice-tablet", "bob" to "bob-phone")).toEntity()
        assertEquals(a, b)
        assertEquals("alice/alice-tablet,bob/bob-phone", a.targetDevices)
    }
    @Test fun malformedOrNonCanonicalPersistedTargetsAreRejected() {
        val valid = record(listOf("bob" to "bob-phone", "alice" to "alice-tablet")).toEntity()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(targetDevices = "bob/bob-phone,alice/alice-tablet").targetDevicePairs() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(targetDevices = "alice/tablet/extra").targetDevicePairs() }
    }
    @Test fun duplicateTargetsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { record(listOf("bob" to "bob-phone", "bob" to "bob-phone")).toEntity() }
    }
    @Test fun recordIdentityIsScopedToAccountAndDevice() {
        val ids = setOf(recordId("alice", "phone"), recordId("alice", "tablet"), recordId("bob", "phone"))
        assertEquals(3, ids.size)
    }
    private fun record(targets: List<Pair<String, String>>) = OlmGroupKeyDistributionRecord("alice", "alice-phone", "group-key-7", "group-1", 7L, "1234567890abcdef", targets)
    private fun recordId(owner: String, device: String) = OlmGroupKeyDistributionRecord.recordId(owner, device, "group-key-7")
}