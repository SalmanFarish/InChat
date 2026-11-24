package com.example.inchat.crypto

import com.example.inchat.data.repository.CloudflareWorkerConfig

/**
 * Purges a device's temporary encrypted mailbox after its public device key
 * has been removed from the account directory.
 *
 * The rollout flag stays disabled until the Worker is deployed and verified.
 * Once enabled, a purge failure is surfaced so the caller's existing retirement
 * journal can retry cleanup without restoring the revoked public key.
 */
internal class RetiredDeviceMailboxPurger(
    private val isEnabled: () -> Boolean = {
        CloudflareWorkerConfig.ENCRYPTED_OLM_MAILBOX_ENABLED
    },
    private val purge: suspend (String, String) -> Unit = { accountUid, deviceId ->
        CloudflareOlmRelayStore().purgeOwnDeviceMailbox(accountUid, deviceId)
    },
) {
    suspend fun purgeAfterRevocation(accountUid: String, deviceId: String) {
        if (!isEnabled()) return
        purge(accountUid, deviceId)
    }
}
