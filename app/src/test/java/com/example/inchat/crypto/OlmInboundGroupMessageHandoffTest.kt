package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OlmInboundGroupMessageHandoffTest {
    @Test
    fun extractsOnlyAValidGroupMessageRoute() {
        assertEquals(
            "group-123",
            extractOlmGroupMessageRouteGroupId(
                """{"messageType":"group_message","groupId":"group-123"}""",
            ),
        )
    }

    @Test
    fun rejectsWrongPayloadTypeBeforeMembershipLookup() {
        assertInvalidRoute("""{"messageType":"direct_message","groupId":"group-123"}""")
    }

    @Test
    fun rejectsMissingOrNonStringGroupId() {
        assertInvalidRoute("""{"messageType":"group_message"}""")
        assertInvalidRoute("""{"messageType":"group_message","groupId":123}""")
    }

    @Test
    fun rejectsMalformedJsonAndInvalidGroupIdentifiers() {
        assertInvalidRoute("""{"messageType":"group_message"""")
        assertInvalidRoute("""{"messageType":"group_message","groupId":"../other-group"}""")
        assertInvalidRoute("""{"messageType":"group_message","groupId":""}""")
        val oversizedGroupId = "x".repeat(129)
        assertInvalidRoute("""{"messageType":"group_message","groupId":"$oversizedGroupId"}""")
    }

    private fun assertInvalidRoute(plaintext: String) {
        try {
            extractOlmGroupMessageRouteGroupId(plaintext)
            fail("Expected malformed authenticated group route to be rejected")
        } catch (_: IllegalArgumentException) {
            // Invalid routes must fail before membership/session access.
        }
    }
}
