# InChat crypto core (experimental)

This is an isolated Rust prototype for InChat's end-to-end encryption work.

**It is not connected to message sending, receiving, Firebase, or chat UI.** The existing messaging path remains unchanged.

## Current contents

- Pins Matrix's `vodozemac` crate to exactly `0.11.2`.
- Builds as a Rust library and Android JNI shared library.
- Exposes only the vodozemac version and a local two-device Olm self-test to Kotlin.
- Includes an isolated Android `CryptoStateStore` for opaque native-state blobs. It uses a non-exportable AES-256-GCM key in Android Keystore, stores authenticated ciphertext in `noBackupFilesDir`, and writes atomically.
- Instrumentation tests verify encrypted state survives store recreation and modified ciphertext fails authentication.
- A native persistence test serializes a vodozemac Olm account inside Rust, passes the temporary state buffer directly to the Keystore-backed store for encryption, then restores it through a new store instance and checks that both public identity keys are unchanged.
- The serialized account blob has a separate version marker. The underlying vodozemac Serde pickle format is not treated as a stable cross-version format; a future app update must add explicit migration handling before changing the pinned crypto version.
- Tests a two-device Olm handshake and bidirectional encrypted message exchange.
- Tests rejection of an invalid message-type discriminator.
- Keeps private account/session state entirely inside Rust; the current JNI API does not return or persist any keys.

The self-test uses two independent in-memory device accounts. Bob's one-time public key is treated as if it were published to an untrusted server; Bob's private key remains in his local Rust account.

## Android bridge

The Android project uses the Gradle 9-compatible `net.mullvad.rust-android` plugin to build and package the native library. The targets are:

- `armv7-linux-androideabi` (32-bit ARM)
- `aarch64-linux-android` (64-bit ARM)
- `i686-linux-android` (32-bit x86)
- `x86_64-linux-android` (64-bit x86)

The app's minSdk is 26. The native bridge is loaded only by the instrumentation test at this stage; normal app startup and chat flows do not call it.

### Windows development setup

1. Install Rust using the official installer at https://rustup.rs. Use the stable toolchain (Rust 1.89 or newer). On Windows, Rust's default MSVC toolchain also needs the Visual Studio C++ build tools; the installer can guide you if they are missing.
2. In Android Studio, open **Tools > SDK Manager > SDK Tools** and install **NDK (Side by side)**.
3. Open a new PowerShell window and install the Android Rust targets:

   ```powershell
   rustup target add armv7-linux-androideabi aarch64-linux-android i686-linux-android x86_64-linux-android
   ```

4. From the InChat project root, build the debug app:

   ```powershell
   .\\gradlew assembleDebug
   ```

5. With an Android device or emulator connected, run the native bridge test:

   ```powershell
   .\\gradlew connectedDebugAndroidTest
   ```

Gradle invokes Cargo to build the native library and packages the matching ABI automatically. No manual copying of `.so` files is required.

## Host-side tests

On a machine with Rust installed, run from the repository root:

```powershell
cargo fmt --manifest-path rust/inchat-crypto-core/Cargo.toml -- --check
cargo test --manifest-path rust/inchat-crypto-core/Cargo.toml
```

The GitHub Actions workflow runs these host-side formatting and test checks.

## Not implemented yet

- No account creation, key-bundle publication, or device directory API is exposed to Kotlin.
- No JNI or Android API exposes private account keys or persisted ratchet state.
- No Android Keystore wrapping or persistent encrypted protocol state.
- No production InChat message envelope or versioned Firebase schema.
- No Megolm group-session distribution or membership-epoch rotation.
- No Worker, Firebase rules, FCM, Room, attachment, or UI changes.

Do not call this prototype production-ready E2EE. It proves that the pinned native engine can be loaded from Android and can execute an isolated two-device Olm exchange; it does not yet secure any InChat conversation.

## Dependency and licensing

The upstream `vodozemac` crate is Apache-2.0 licensed. Keep its upstream licence and required notices when distributing the app. This folder does not select or change the licence for the InChat project itself.

- Upstream: https://github.com/matrix-org/vodozemac
- Exact dependency documentation: https://docs.rs/vodozemac/0.11.2/
- Android Rust Gradle plugin: https://github.com/mullvad/rust-android-gradle
