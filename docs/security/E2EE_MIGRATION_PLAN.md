# InChat end-to-end encryption migration plan

Status: design and compatibility audit only. No message encryption is enabled by this document.

## Goals

- Keep the existing native Kotlin/Jetpack Compose application.
- Keep Firebase Realtime Database on Spark and the existing Cloudflare Worker.
- Do not add a paid service, billing requirement, R2 bucket, or third-party message host.
- Make message and media contents unreadable to Firebase and the Worker.
- Keep offline delivery, retries, Room caching, message edits, replies, groups, and multi-device support.
- Use an established, maintained cryptographic protocol implementation. Do not design a new ratchet or key-exchange protocol.

## Current prototype status

The isolated vodozemac 0.11.2 prototype includes device identity and signed prekeys, public device-key publication and one-time-key claims, per-device fan-out planning, outbound Olm preparation, a ciphertext-only durable Room outbox, and a receiver-side inbound receipt ledger.

The inbound ledger stores only the encrypted envelope and routing/deduplication metadata. Its unique identity is sender account + sender device + message ID + recipient device. It records a SHA-256 digest of the exact envelope: an identical retry is recognized as pending or already processed, while reuse of the same message identity with different ciphertext is reported as a conflict. Pending receipts can be retried after process death; processed receipts are not decrypted again.

The isolated Olm handoff now uses a strict version-1 authenticated inner payload for direct text messages. Message ID, sender account/device, recipient account/device, timestamp, and text are encoded inside the Olm plaintext, so successful Olm decryption authenticates these fields as a unit. The receiver checks every identity against the durable receipt and the local account/device before creating a cached message. The handoff coordinator also obtains the authenticated local account from its injected auth-backed provider and rejects receipts addressed to another local device; unknown fields, unsupported versions, non-canonical JSON, fractional timestamps, and mismatches are rejected. The first-message preparer accepts this typed payload and validates it before claiming a one-time prekey. This schema is text-only: attachment descriptors, edits, reactions, group/conversation IDs, and nickname semantics still need separately authenticated protocol definitions. Raw legacy plaintext is not accepted by this isolated handoff. This does not enable encryption or change live chat behavior.

The ledger is deliberately not connected to Firebase listeners, the existing chat receiver, message persistence, notifications, or the UI. Its markProcessed operation must eventually be coordinated transactionally with local message persistence. Otherwise a process death between marking a receipt and saving the decrypted message could lose the local copy. No live chat behavior has changed.

The isolated inbox receiver now polls the authenticated device inbox, checks that the transport sender-device field matches the sender device embedded in the encrypted envelope, validates each envelope's routing metadata, and registers the ciphertext in the Room receipt ledger on an IO dispatcher. Instrumentation coverage also verifies that mismatched sender metadata is not acknowledged and that a failed remote acknowledgement is retried safely from the already-persisted pending receipt. It deletes the remote item only after the local receipt insert or duplicate check succeeds. A crash after local persistence but before remote deletion is safe: the next poll sees the same pending receipt and retries the acknowledgement. A reused sender/device/message identity with different ciphertext is treated as a conflict and is deliberately not acknowledged. This receiver only stages ciphertext; it does not decrypt, create plaintext messages, or connect to the live chat cache.

The durable sender outbox remains at-least-once. An isolated Firebase Realtime Database transport now uploads immutable ciphertext envelopes to per-user, per-device `olmInbox` paths. Rules require authenticated sender and recipient device registrations, enforce create-once / exact-retry semantics, reject changed payloads, restrict reads and deletes to the recipient, and prevent delivery between mutually blocked accounts. The transport does not use `chats/{chatId}/messages` and has no live listener or WorkManager integration yet. The receiver receipt ledger provides local deduplication, but there is still no end-to-end transport worker or atomic decrypt-and-store transaction.

## Rollout safety

- Existing plaintext message history remains untouched.
- Version 1 encrypted messages must never silently fall back to plaintext.
- Do not connect the isolated prototype to live chats until sender transport, receiver authentication, deduplication, local persistence, notification privacy, and two-device tests are complete.
- Keep all infrastructure within free Firebase Spark capabilities; no card-required or paid storage services.


## Device identity verification groundwork

A deterministic SHA-256 fingerprint helper now binds the installation device ID and
the Ed25519 identity public key using a versioned domain separator. It validates
the device ID, exact 32-byte public-key length, and canonical unpadded Base64
encoding, then formats the full 256-bit digest in four-character groups for
human comparison.

This helper is only a display/verification primitive. It does not mark a device
as trusted, persist a trust decision, or make a self-signed directory entry
authentic. Users must compare the complete fingerprint through an independent
trusted channel. The next required work is an authenticated SAS verification
exchange and durable local identity-pin state; until those exist, directory
bundles must not be treated as verified people and no encrypted send path may
be enabled.


## Authenticated SAS verification protocol groundwork

The Rust crypto core now contains an isolated, process-local SAS verification state
machine built on vodozemac 0.11.2. It binds the exchange transcript to a caller-
supplied transaction ID and the ordered initiator/responder user IDs, device IDs,
and Ed25519 identity public keys.

Each side first publishes a SHA-256 commitment to its ephemeral Curve25519 public
key. The local public key cannot be revealed until the peer commitment is accepted;
the peer key is accepted only when it opens that commitment. vodozemac then derives
the SAS emoji indices and decimal code from the shared secret and the same bound
transcript. After an explicit local "codes match" confirmation, each side creates
a MAC over its own expected Ed25519 identity key; the other side must verify that
MAC against the expected peer identity key. The state machine reports completion
only after local confirmation and successful peer-MAC verification.

The attempt deliberately has no serialization support: cancellation or process
death discards the ephemeral secret and requires a fresh transaction. This is
safer than persisting an unfinished ephemeral exchange. A narrow JNI bridge now
exposes the state machine through opaque process-local handles, and an Android
instrumentation test exercises a complete two-device exchange through native
code. The bridge still does not persist trust.

The isolated `sasVerificationInbox` Realtime Database transport carries only
short-lived SAS protocol messages and is separate from both `olmInbox` and the
live `chats` tree. Messages bind transaction, sender/recipient accounts and
device IDs, type, payload, creation time, and expiry. The codec enforces a
canonical schema, ten-minute maximum lifetime, two-minute future-clock
tolerance, strict message types, and recipient binding. A Room replay ledger
records message identities before remote acknowledgement, detects changed
payloads for an existing transaction/type, survives process restarts, and prunes
expired records. Database rules restrict creation to registered sender devices,
delivery to registered recipient devices, immutable exact retries, recipient-
only reads/deletes, expiry, and blocked-account checks. Emulator tests cover
these rule boundaries.

This is transport and replay groundwork, not yet the complete verification
orchestration: no UI or transaction coordinator currently enforces the ordered
commitment/public-key/MAC state transitions across these messages, and no
durable trusted-device pin is written. The new database rules have passed
emulator tests but have not been deployed to Firebase. Do not enable a live
verification or encrypted messaging path until orchestration, account/device
binding to authenticated local state and signature-verified remote bundles,
transaction cancellation/timeout handling, and durable trust-pin tests are
complete.
