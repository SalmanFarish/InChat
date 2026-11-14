# InChat crypto core (experimental)

This is an isolated Rust prototype for InChat's end-to-end encryption work.

**It is not connected to the Android app and does not encrypt real InChat messages.** The existing messaging path remains unchanged.

## Current contents

- Pins Matrix's `vodozemac` crate to exactly `0.11.2`.
- Preserves the Olm message-type discriminator when converting to and from a transport-friendly pair of values.
- Tests a two-device Olm handshake and bidirectional encrypted message exchange.
- Tests rejection of an invalid message-type discriminator.
- Forbids unsafe Rust in this crate.

The test uses two independently generated in-memory device accounts. Bob's one-time public key is treated as if it were published to an untrusted server; Bob's private key remains in his local account.

## Requirements

- Rust 1.89 or newer (the minimum required by vodozemac 0.11.x).
- Cargo.

Run from the repository root:

```powershell
cargo test --manifest-path rust/inchat-crypto-core/Cargo.toml
```

## Not implemented yet

- No JNI or UniFFI bridge.
- No Android Gradle integration.
- No persistent account/session storage or Android Keystore wrapping.
- No device directory, signed-prekey API, one-time-prekey claim endpoint, or identity verification.
- No production InChat message envelope or versioned Firebase schema.
- No Megolm group-session distribution or rotation.
- No Worker, Firebase rules, FCM, Room, attachment, or UI changes.

Do not call this prototype production-ready E2EE. It is only an initial proof that two independent vodozemac device accounts can establish and exchange Olm messages.

## Dependency and licensing

The upstream `vodozemac` crate is Apache-2.0 licensed. Keep its upstream licence and required notices when distributing the app. This folder does not select or change the licence for the InChat project itself.

- Upstream: https://github.com/matrix-org/vodozemac
- Exact dependency documentation: https://docs.rs/vodozemac/0.11.2/
