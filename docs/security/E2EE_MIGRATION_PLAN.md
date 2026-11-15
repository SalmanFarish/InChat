# InChat end-to-end encryption migration plan

Status: design and compatibility audit only. No message encryption is enabled by this document.

## Goals

- Keep the existing native Kotlin/Jetpack Compose application.
- Keep Firebase Realtime Database on Spark and the existing Cloudflare Worker.
- Do not add a paid service, billing requirement, R2 bucket, or third-party message host.
- Make message and media contents unreadable to Firebase and the Worker.
- Keep offline delivery, retries, Room caching, message edits, replies, groups, and multi-device support.
- Use an established, maintained cryptographic protocol implementation. Do not design a new ratchet or key-exchange protocol in application code.

## Current plaintext exposure

The audit of the migration branch found these content paths:

1. Realtime Database message records use `chats/{chatId}/messages/{messageId}.text` and may also contain `replyTo.text`, reactions, and attachment metadata.
2. Room stores message text and reply text in `cached_messages`.
3. Worker inbox-preview code reads the latest message and writes readable text to `userChats/{uid}/{chatId}/lastMessage`.
4. Direct and group FCM payloads currently include readable message text. The Android service displays that payload as a notification.
5. Attachment metadata and media bytes are handled separately by the existing Worker.

Changing only the message transport would not provide E2EE while these other plaintext copies remain.

## Required message-format compatibility

New messages need an explicit versioned envelope. The isolated Olm prototype now defines envelope version 1 as JSON with these fields:

- `encryptionVersion`: integer `1` for this format. Existing records remain legacy plaintext and are not rewritten.
- `algorithm`: `"olm"` for this pairwise protocol.
- `senderDeviceId` and `recipientDeviceId`: validated device-routing identifiers.
- `sessionId`: the local Olm session selector.
- `ciphertext`: URL-safe Base64 of the opaque native Olm wire message.

The codec rejects unsupported versions/algorithms, invalid device IDs, self-addressed envelopes, malformed Base64, and oversized or truncated ciphertext. Device IDs and session IDs are routing metadata, not cryptographic authentication; Olm ciphertext authentication remains authoritative. This codec is not connected to Firebase or live chat records. The app-facing Olm repository now has envelope-aware methods to encrypt for a recipient device, establish an inbound session from a matching envelope, and decrypt only when the envelope's recipient and expected sender routing IDs match. The two-device instrumentation test exercises an envelope-wrapped initial prekey message and encrypted reply.

Existing message ID, account sender ID, timestamp, and delivery bookkeeping remain separate from message contents. The eventual database adapter must map legacy records to encryption version 0 and must never silently downgrade a version 1 send to plaintext.

Version 0 messages must remain readable for history. Version 1 messages must never silently fall back to plaintext if a key or session is missing. The UI should clearly distinguish older unencrypted history from encrypted messages.

Edits, replies, reactions, and any message text displayed in previews must follow the encryption policy too. Server-side preview generation cannot read encrypted message contents.

## Key and device model

Encryption must be device-aware, not just account-aware.

Each installation needs its own cryptographic device identity and protocol state. Public identity/prekey material may be published to a server directory; private identity, ratchet, and session state must remain on the device and be protected by Android Keystore-backed storage.

The isolated Android prototype now has an outbound-delivery preparer for a single target device: it loads that device's verified public bundle, atomically claims an available one-time prekey, uses the signed fallback key only when all one-time keys are already claimed, creates a native Olm session, and emits the first encrypted envelope. It remains a preparation primitive only; it does not enqueue or send chat messages. A claimed one-time key is intentionally never reclaimed if a later local session-creation step fails, avoiding unsafe key reuse.

The server-side directory must support:

- User ID and stable device ID.
- Public identity key and signed-prekey bundle.
- A finite set of one-time public prekeys.
- Key-bundle version/rotation metadata.
- Authenticated key-bundle publication.
- Atomic one-time-prekey claim/consumption to avoid two senders using the same prekey.

A multi-device account must publish and receive keys for each active device. Logout, device removal, reinstall, account recovery, and key changes need explicit lifecycle behavior. Public key changes should be visible and verifiable; a QR/fingerprint verification flow is a later requirement. The prototype now includes a pure fan-out planner that deterministically selects every listed recipient device plus the sender's other devices, excludes the currently sending device, deduplicates entries, validates bundle shape, and enforces bounded device counts. It does not create sessions, claim prekeys, encrypt, or enqueue network deliveries. A separate outbound preparer creates one encrypted envelope per device without transmitting it.

## Direct-message protocol requirements

Use an established asynchronous key agreement and ratcheting implementation that provides authenticated sessions, per-message key evolution, replay handling, and forward secrecy. The server may relay public prekeys and ciphertext but must never receive private keys or shared session secrets.

The server may still see routing metadata such as participant IDs, chat IDs, message sizes, and timestamps. E2EE protects content, not all metadata.

## Group protocol requirements

Do not encrypt a group by inventing one shared static key and placing it in Realtime Database.

Group encryption needs a defined sender-key or group-ratchet protocol, authenticated key distribution to each member device, and a membership epoch. Adding or removing a member must rotate the relevant group sending keys so removed members cannot decrypt future messages. New devices must receive group keys through authenticated pairwise sessions.

Group membership changes, pending offline devices, retries, and concurrent key rotations need explicit tests before enabling encrypted group messages.

## Notifications and previews

The Worker cannot create readable notification snippets after E2EE is enabled.

The safe initial behavior is a generic notification such as “New message” plus the chat ID. The Android app may display a decrypted preview only when the message and keys are available locally. Do not place message text, reply text, attachment keys, or plaintext group content in FCM payloads or Worker-generated inbox previews.

Worker-maintained unread counts and timestamps can remain server-side. The preview text should become a generic content indicator or be populated locally from decrypted Room data.

## Local data and key protection

E2EE does not automatically encrypt the local Room database. Private identity keys, signed-prekey private keys, one-time-prekey private keys, and ratchet state must be protected using Android Keystore-backed key wrapping. Room message-cache encryption at rest is a separate layer and must be planned without losing offline history.

Backup and restore must not silently copy private keys into an unprotected Android backup. Device transfer or encrypted backup is a separate feature and must be designed before promising account-wide history recovery.

## Media

Before uploading media, generate a random per-file content key and encrypt the bytes on-device using an authenticated encryption mode. The file key and any required nonce/metadata belong inside the encrypted message envelope. The Worker should store/relay opaque encrypted bytes and should not need the file key.

View-once behavior, attachment deletion, integrity checks, size limits, and retries must continue to work with encrypted bytes.

## Library evaluation

### Signal libsignal

- Signal's maintained implementation includes the Signal Protocol and Android artifacts.
- The repository and published Android artifact are AGPL-3.0.
- The library documentation says it is published for Signal's own use and APIs may change; external use is not presented as a stable supported SDK.
- This is the most direct route technically, but it creates an important licensing decision. Do not add it until the owner explicitly agrees to the applicable AGPL obligations and to licensing InChat accordingly, or obtains appropriate separate permission.

References:
- https://github.com/signalapp/libsignal
- https://github.com/signalapp/libsignal/blob/main/README.md
- https://central.sonatype.com/artifact/org.signal/libsignal-android

### Matrix vodozemac

- vodozemac is an Apache-2.0 Rust implementation of Olm and Megolm.
- It avoids the AGPL issue and provides established pairwise and group ratchets.
- It does not provide a drop-in Kotlin SDK for this Firebase-backed app. Integrating it means building and maintaining a small Android JNI/UniFFI bridge and implementing the app's device directory, session lifecycle, and key distribution correctly.

References:
- https://github.com/matrix-org/vodozemac
- https://github.com/matrix-org/vodozemac/blob/main/Cargo.toml

### Google Tink HPKE

- Tink supports standard HPKE, including X25519/HKDF-SHA256/AES-GCM.
- HPKE alone is not a replacement for an asynchronous messaging protocol with forward secrecy and session ratcheting. Tink's documentation also warns that hybrid encryption alone does not authenticate the sender.
- Do not use Tink HPKE alone as the basis for claiming WhatsApp-style E2EE.

References:
- https://developers.google.com/tink/hybrid
- https://developers.google.com/tink/supported-key-types

## Implementation gates

1. Resolve the cryptographic library and licensing choice.
2. Document a versioned device/key schema and Worker endpoints. Add rules that allow only the intended public-key operations and never expose private keys.
3. Implement and test key generation/storage and public-prekey publication without changing message delivery.
4. Implement pairwise encrypted text messaging behind an explicit protocol-version gate.
5. Remove plaintext from Worker previews and FCM payloads before rollout.
6. Add group key distribution and membership-epoch rotation.
7. Add encrypted media upload/download.
8. Migrate UI and local-cache behavior, then test with at least two real accounts/devices.

## Rollout safety

- No existing plaintext message is automatically converted or deleted.
- New encrypted messaging is not enabled until sender and receiver devices both support the same protocol version.
- A missing key/session must be visible and recoverable; it must not trigger a silent plaintext send.
- Keep the old message reader for legacy history.
- Test offline send, retry, duplicate delivery, out-of-order delivery, app reinstall, device addition/removal, group member removal, edits, replies, reactions, media, and Android process death.
- A single-account emulator build can validate local crypto and storage, but cannot prove end-to-end exchange. At least two independent device identities are required for that acceptance test.

## Current decision

The selected implementation route is **vodozemac 0.11.2** (Apache-2.0), subject to a small isolated Android native-bridge prototype and interoperability/security tests.

- Use Olm for one-to-one device sessions.
- Use Megolm for group messages, with each group's outbound session distributed to member devices over authenticated Olm sessions.
- Rotate group sessions on membership changes and define additional rotation limits before production use.
- Keep protocol state local and persist it only in an encrypted form protected by Android Keystore-backed key wrapping.
- Build a narrow Rust-to-Kotlin bridge; do not expose raw private keys or ratchet internals to application UI code.
- Keep legacy plaintext message reading. Do not silently send plaintext if encryption is unavailable.
- Do not add Signal's AGPL-3.0 dependency or change InChat's own project licence as part of this decision.

Megolm has a documented trade-off: compromise of a current group session can expose subsequent messages until rotation, so the group-rotation design and removal behavior must be tested carefully. This choice is not a claim that group encryption is complete or enabled.

The library integration prototype must pass two-independent-device tests before encrypted messaging is enabled. Until then, keep the current messaging path unchanged. See [ADR-001](ADR-001-E2EE-CRYPTOGRAPHY.md) for the rationale and implementation gates.


## Encrypted outbox prototype

The isolated queue coordinator now models one outbox row per target device. Enqueue is idempotent using a deterministic SHA-256 delivery ID derived from the message ID, recipient user ID, and recipient device ID. It validates that each supplied ciphertext envelope is addressed to the expected device and uses the expected Olm session.

The queue state machine supports QUEUED, IN_FLIGHT, ACKNOWLEDGED, and terminal FAILED states. Claims use leases so an expired in-flight row can be retried after process interruption. Retry attempts use bounded exponential backoff and a configurable attempt limit. Acknowledgements are conditional on both delivery ID and message ID, and duplicate acknowledgements do not mutate terminal state.

This milestone defines an atomic storage interface and exercises it with an in-memory test store. It does **not** yet provide a durable Room adapter or a network sender. A production adapter must implement insertion, lease claims, and state transitions transactionally across threads and process restarts before this queue can be used. Transport is at-least-once: if the app dies after the remote side accepts a message but before local acknowledgement, the sender may retry. The receiver must therefore deduplicate using the stable message ID. No plaintext message body, preview, or attachment key belongs in the outbox.
