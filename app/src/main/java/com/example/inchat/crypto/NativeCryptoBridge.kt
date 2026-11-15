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

    /** Create a temporary Olm account and persist its Rust pickle via Keystore. */
    external fun nativePersistAccountState(store: CryptoStateStore): String

    /** Restore that account from Keystore and compare its public identity keys. */
    external fun nativeRestoreAccountState(
        store: CryptoStateStore,
        expectedIdentityKeys: String
    ): Boolean
}
