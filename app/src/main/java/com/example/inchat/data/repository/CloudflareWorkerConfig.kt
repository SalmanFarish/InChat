package com.example.inchat.data.repository

/**
 * Shared base URL for the existing InChat Cloudflare Worker.
 *
 * Replace the placeholder after deploying the Worker. Keep secrets in
 * Cloudflare Worker secrets; this URL is public configuration.
 */
object CloudflareWorkerConfig {
    const val BASE_URL =
        "https://icy-base-2bc4.s91670002.workers.dev"

    const val API_BASE_URL = BASE_URL + "/v1"

    val isConfigured: Boolean
        get() = !BASE_URL.contains("REPLACE_WITH_")
}
