package com.example.inchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedOlmMessageOwnershipPolicyTest {
    @Test
    fun recipientAccountOwnsIncomingCopy() {
        assertTrue(
            EncryptedOlmMessageOwnershipPolicy.isOwnedByAccount(
                ownerUserId = "bob",
                senderUserId = "alice",
                recipientUserId = "bob",
            ),
        )
        assertFalse(EncryptedOlmMessageOwnershipPolicy.isOutgoing("bob", "alice"))
    }

    @Test
    fun senderAccountOwnsOutgoingCopy() {
        assertTrue(
            EncryptedOlmMessageOwnershipPolicy.isOwnedByAccount(
                ownerUserId = "alice",
                senderUserId = "alice",
                recipientUserId = "bob",
            ),
        )
        assertTrue(EncryptedOlmMessageOwnershipPolicy.isOutgoing("alice", "alice"))
    }

    @Test
    fun senderAccountAlsoOwnsCopiesSyncedToItsOtherDevices() {
        assertTrue(
            EncryptedOlmMessageOwnershipPolicy.isOwnedByAccount(
                ownerUserId = "alice",
                senderUserId = "alice",
                recipientUserId = "alice",
            ),
        )
        assertTrue(EncryptedOlmMessageOwnershipPolicy.isOutgoing("alice", "alice"))
    }

    @Test
    fun unrelatedAccountCannotOwnEitherCopy() {
        assertFalse(
            EncryptedOlmMessageOwnershipPolicy.isOwnedByAccount(
                ownerUserId = "mallory",
                senderUserId = "alice",
                recipientUserId = "bob",
            ),
        )
    }
}
