package com.example.inchat.crypto

/**
 * Shared HTTP failure policy for the encrypted relay adapter and sender outbox.
 *
 * Authentication failures are retried because Firebase may refresh a cached
 * ID token on the next attempt. Timeouts, rate limits, and server failures are
 * transient. Other 4xx responses describe a request/device/conversation that
 * will not become valid by uploading the same immutable delivery again.
 */
internal object OlmDeliveryRelayFailurePolicy {
    fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 401 ||
            statusCode == 408 ||
            statusCode == 429 ||
            statusCode in 500..599

    fun permanentErrorCode(statusCode: Int): String = when (statusCode) {
        403 -> RELAY_DELIVERY_FORBIDDEN
        404 -> RELAY_DEVICE_UNAVAILABLE
        410 -> RELAY_DELIVERY_EXPIRED
        else -> RELAY_REJECTED_DELIVERY
    }

    private const val RELAY_DELIVERY_FORBIDDEN = "relay_delivery_forbidden"
    private const val RELAY_DEVICE_UNAVAILABLE = "relay_device_unavailable"
    private const val RELAY_DELIVERY_EXPIRED = "relay_delivery_expired"
    private const val RELAY_REJECTED_DELIVERY = "relay_rejected_delivery"
}
