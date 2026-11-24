package com.example.inchat.data.repository

/**
 * Shared base URL for the existing InChat Cloudflare Worker.
 *
 * Public base URL for the deployed InChat Cloudflare Worker.
 * Keep Firebase service-account credentials in Cloudflare secrets, never here.
 */
object CloudflareWorkerConfig {
    const val BASE_URL =
        "https://icy-base-2bc4.s91670002.workers.dev"

    const val API_BASE_URL = BASE_URL + "/v1"

    /**
     * Keep the new mailbox transport disabled until its Durable Object
     * migration is deployed and OLM_MAILBOX_RETENTION_MS is configured.
     * Firebase remains the active encrypted relay until that rollout gate moves.
     */
    const val ENCRYPTED_OLM_MAILBOX_ENABLED = false

    val isConfigured: Boolean
        get() = !BASE_URL.contains("REPLACE_WITH_")
}
