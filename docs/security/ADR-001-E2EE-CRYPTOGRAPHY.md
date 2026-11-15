# ADR-001: Select vodozemac for end-to-end encryption

- Status: Accepted for an isolated prototype; production adoption is gated
- Date: 2026-11-14
- Branch: `chore/spark-free-worker-migration`

## Context

InChat is a native Kotlin/Android application using Firebase Realtime Database on the Spark plan and a small Cloudflare Worker. It needs asynchronous end-to-end encryption for direct and group conversations while keeping the current relay, offline delivery, Room cache, and free-tier architecture.

The project is publicly hosted on GitHub, but it does not currently contain a root `LICENSE` file. A dependency choice should not silently decide InChat's own licence.

## Decision

Prototype **Matrix vodozemac 0.11.2**, pinned to an exact version, as the cryptographic engine.

The upstream project is Apache-2.0 licensed and provides:

- Olm, a one-to-one Double Ratchet channel with forward secrecy and self-healing.
- Megolm, a group-message ratchet.
- Short Authentication String (SAS) support for verifying device-to-device identity.
- Serializable protocol state that can be restored between app launches.

Upstream project and API references:

- https://github.com/matrix-org/vodozemac
- https://matrix-org.github.io/vodozemac/vodozemac/

This dependency choice does not require InChat itself to adopt AGPL-3.0. Preserve all required third-party licence and notice files when packaging the app. This ADR does not choose InChat's own public licence.

## Why not Signal libsignal for this project now?

Signal's library is technically attractive and exposes Java/Android packages, but Signal states that its libraries are for the official Signal clients and that external use is unsupported; APIs, implementation, and bridge layers may change without notice. Its current repository is AGPL-3.0. Selecting it would therefore introduce both an app-licensing decision and an upstream-support risk that are avoidable for the first prototype.

References:

- https://github.com/signalapp/libsignal
- https://github.com/signalapp/libsignal/blob/main/README.md
- https://github.com/signalapp/libsignal/blob/main/LICENSE

## Protocol mapping

### Direct conversations

Use one Olm session per sender-device/recipient-device pair. For accounts with multiple devices, a message must be encrypted separately for every active recipient device and every other sender-owned device that should receive the message. Device identity verification and key-bundle lifecycle are required before trusted sessions are established.

### Group conversations

Use a Megolm outbound group session per sender device and group epoch. Distribute the session key only to the current member devices over authenticated Olm sessions. A new member must not receive keys for earlier epochs. On member addition/removal, create a new epoch and distribute only the new session to the remaining members.

Megolm has a known security trade-off: a party who obtains a group's current session state can derive subsequent message keys until the session is rotated. The vodozemac documentation explicitly notes that Megolm does not have Olm's self-healing property. Therefore:

- Rotate immediately on every membership change.
- Establish a maximum message-count and age for each group session before production.
- Never reuse a previous epoch after a removal.
- Keep the membership epoch in the authenticated message metadata.
- Test concurrent membership changes, offline members, replay, and missing key delivery.

This is a conscious prototype choice, not an assertion that Megolm is equivalent to a modern continuously evolving group ratchet.

## Android integration boundary

vodozemac is a Rust crate, not a ready-made Kotlin SDK. Build a small isolated Rust library and expose only the operations the Android repository needs through a narrow JNI/UniFFI boundary.

The Kotlin application should not handle private ratchet keys directly. The bridge should accept typed operations and return opaque ciphertext/envelopes. Rust protocol state may be serialized using vodozemac's supported pickle mechanism, but the serialized state must be encrypted with a random local wrapping key protected by Android Keystore before it is written to persistent storage.

The prototype must cover the Android ABIs actually shipped by InChat and the existing minSdk 26 before merging into the app. No new network service is required; Firebase remains the untrusted ciphertext relay and key-directory transport.

## Required prototype tests

1. Create two independent device identities and publish only public key material.
2. Establish a direct Olm session and exchange encrypted messages in both directions.
3. Persist, close, restore, and continue both Olm sessions.
4. Deliver ciphertext late, twice, and out of order; verify correct handling or a safe, explicit failure.
5. Create a group session and distribute it to at least two other devices through Olm.
6. Rotate the group epoch after removing a member; prove the removed device cannot decrypt later messages.
7. Verify device fingerprints/SAS and reject unverified or changed identity keys according to the defined UX.
8. Verify no plaintext or private protocol state appears in Firebase, Worker logs, FCM data, or inbox previews.
9. Verify app process death, offline send/retry, reinstall behavior, and unavailable-key behavior.
10. Run Rust unit tests and Android instrumented tests before enabling any encrypted send path.

## Implementation sequence

1. Add the isolated Rust crate and a minimal bridge; do not change message delivery.
2. Add local encrypted storage for protocol state and test restoration.
3. Add device identity and public-prekey directory endpoints/rules.
4. Add a versioned encrypted envelope and direct-message exchange behind a disabled feature flag.
5. Remove plaintext from notification and preview paths before any rollout.
6. Add group key distribution, epochs, removal rotation, and tests.
7. Add encrypted media with per-file keys carried only inside encrypted message envelopes.
8. Keep legacy messages readable and roll out only when both sender and receiver devices support the same encryption version.

## Consequences

Positive:

- Avoids introducing AGPL-3.0 into InChat.
- Uses an existing protocol implementation rather than custom cryptography.
- Covers both direct and group ratchets.
- Keeps current Firebase/Worker services as ciphertext transport; no paid infrastructure is required.

Costs and limitations:

- Requires Rust toolchain, Android NDK builds, ABI packaging, and a maintained Kotlin bridge.
- The bridge becomes security-critical and needs careful review.
- Megolm group-session compromise recovery is weaker than Olm; rotations and membership epochs are mandatory.
- Multi-device identity management, key verification, account recovery, and device removal need explicit product behavior.
- This prototype does not yet provide key backup or seamless history restoration on a replacement device.

## Safety boundary

No encryption code, dependency, database schema, Worker endpoint, Firebase rule, or notification behavior is changed by this ADR. The current plaintext messaging path remains active until the prototype is implemented and independently tested.