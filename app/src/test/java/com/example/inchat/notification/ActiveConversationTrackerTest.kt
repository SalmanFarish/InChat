package com.example.inchat.notification

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveConversationTrackerTest {

    @After
    fun resetTracker() {
        ActiveConversationTracker.clearActiveChat("shared-chat", "account-one")
        ActiveConversationTracker.clearActiveChat("shared-chat", "account-two")
        ActiveConversationTracker.setAppInForeground(false)
    }

    @Test
    fun suppressesOnlyForTheExactVisibleAccountAndConversation() {
        ActiveConversationTracker.setAppInForeground(true)
        ActiveConversationTracker.setActiveChat("shared-chat", "account-one")

        assertTrue(
            ActiveConversationTracker.shouldSuppressNotification(
                "shared-chat",
                "account-one",
            ),
        )
        assertFalse(
            ActiveConversationTracker.shouldSuppressNotification(
                "shared-chat",
                "account-two",
            ),
        )
        assertFalse(
            ActiveConversationTracker.shouldSuppressNotification(
                "different-chat",
                "account-one",
            ),
        )
    }

    @Test
    fun staleScreenDisposalCannotClearTheNewAccountsActiveConversation() {
        ActiveConversationTracker.setAppInForeground(true)
        ActiveConversationTracker.setActiveChat("shared-chat", "account-one")
        ActiveConversationTracker.setActiveChat("shared-chat", "account-two")

        ActiveConversationTracker.clearActiveChat("shared-chat", "account-one")

        assertTrue(
            ActiveConversationTracker.shouldSuppressNotification(
                "shared-chat",
                "account-two",
            ),
        )

        ActiveConversationTracker.clearActiveChat("shared-chat", "account-two")
        assertFalse(
            ActiveConversationTracker.shouldSuppressNotification(
                "shared-chat",
                "account-two",
            ),
        )
    }

    @Test
    fun foregroundStateCanBeReadSafelyByThePushCallback() {
        ActiveConversationTracker.setAppInForeground(true)
        assertTrue(ActiveConversationTracker.isAppInForeground())

        ActiveConversationTracker.setAppInForeground(false)
        assertFalse(ActiveConversationTracker.isAppInForeground())
    }

    @Test
    fun backgroundNotificationsAreNeverSuppressed() {
        ActiveConversationTracker.setActiveChat("shared-chat", "account-one")
        ActiveConversationTracker.setAppInForeground(false)

        assertFalse(
            ActiveConversationTracker.shouldSuppressNotification(
                "shared-chat",
                "account-one",
            ),
        )
    }
}
