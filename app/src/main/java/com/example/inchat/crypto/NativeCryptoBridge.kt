package com.example.inchat.crypto

/**
 * Narrow Android boundary for the experimental native crypto core.
 *
 * This bridge is currently used only by instrumentation tests. It does not
 * expose private keys, session state, Firebase access, or message operations.
 */
internal object NativeCryptoBridge {

    init {
        System.loadLibrary("inchat_crypto_core")
    }

    external fun nativeVodozemacVersion(): String

    external fun nativeOlmSelfTest(): Boolean
}
