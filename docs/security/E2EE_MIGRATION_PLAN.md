# InChat end-to-end encryption migration plan

Status: isolated E2EE prototype only. No live chat message encryption is enabled.

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

The durable sender outbox remains at-least-once. Its isolated Firebase Realtime Database transport uploads immutable ciphertext envelopes to per-user, per-device `olmInbox` paths. Rules require authenticated sender and recipient device registrations, enforce create-once / exact-retry semantics, reject changed payloads, restrict reads and deletes to the recipient, and prevent delivery between mutually blocked accounts. The transport does not use `chats/{chatId}/messages`.

An isolated `OlmOutboundDeliveryCoordinator` now joins the fan-out planner, first-message preparer, and durable queue for version-1 direct text messages. It includes every published recipient device and the sender's other published devices, validates all per-device authenticated payloads before attempting any prekey claim, prepares every ciphertext copy before inserting the complete batch, and asks the caller to schedule the account/device worker only after the Room enqueue. Replaying the same message is idempotent: the first stored ciphertext is retained and scheduling is requested again to repair a missed trigger. The schema is still direct-text only; it cannot be used for groups because it does not authenticate a conversation ID.

The isolated `EncryptedOlmDeliveryWorker` now processes sender acknowledgements before uploading due ciphertext rows, validates the authenticated account and existing local device identity, uses durable leases and retry state, and runs with a network constraint plus periodic recovery scheduling. Startup auth restoration recreates the account/device schedule without creating a device identity. These components remain disconnected from `ChatRepository`, `GroupChatRepository`, ordinary message listeners, and the live UI. The receiver receipt ledger provides ciphertext deduplication and acknowledgement recovery. The separate `OlmInboundHandoffCoordinator` now also requires a locally SAS-verified sender identity key before it advances a receive ratchet, consumes a one-time prekey, or stages plaintext; an identity-key change is rejected until it is re-verified. It then validates authenticated payload metadata and commits the message and receipt atomically in Room. The inbound handoff remains isolated from live listeners and UI.

## Rollout safety

- Existing plaintext message history remains untouched.
- Version 1 encrypted messages must never silently fall back to plaintext.
- Do not connect the isolated prototype to live chats until durable trusted-device verification, authenticated receiver decryption, atomic decrypt-and-store, notification privacy, delivery-status reconciliation, and end-to-end two-device recovery tests are complete.
- Keep all infrastructure within free Firebase Spark capabilities; no card-required or paid storage services.


## Device identity verification groundwork

A deterministic SHA-256 fingerprint helper now binds the installation device ID and
the Ed25519 identity public key using a versioned domain separator. It validates
the device ID, exact 32-byte public-key length, and canonical unpadded Base64
encoding, then formats the full 256-bit digest in four-character groups for
human comparison.

The fingerprint helper remains a display and manual-verification aid; by itself
it does not establish trust. The Kotlin SAS verification coordinator now
orchestrates the commitment/public-key/SAS/MAC exchange, and after both devices
confirm the comparison and authenticate their expected Ed25519 identity keys,
it stores a device-local trust record in the Keystore-encrypted trusted-device
store. Trust is bound to account ID, device ID, and the Ed25519 identity-key
fingerprint. A changed published identity key therefore does not inherit the
old trust decision and must be verified again.


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
code. The native bridge itself does not persist trust; the Kotlin coordinator
writes the locally encrypted trust record only after native completion.
Completed attempts release their native handle and active-attempt slot, and
incoming cancellation messages must match the exact peer account and device.

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

The SAS transaction coordinator and encrypted local trust repository are now
implemented, including account/device binding, explicit local SAS confirmation,
peer MAC verification, expiry/cancellation handling, and tests for durable
trust, key rotation, and cancellation-peer binding. The Compose
`DeviceVerificationScreen` is reachable from Settings and supports verifying
other devices on the same account as well as devices belonging to another
discoverable InChat username. It loads the peer's signature-verified public
device bundles, displays the three SAS decimal codes, supports local trust
revocation, and requires the user to compare the codes through an independent
trusted channel. The screen also allows a fresh verification after trust is
revoked or a device identity key changes; UI-state helper tests cover retry and
account/device isolation.

The isolated outbound direct-text coordinator refuses to prepare or enqueue
ciphertext unless every recipient and sender-owned target device has a local
trust record matching its currently published Ed25519 identity key. The
first-message preparer re-checks the freshly fetched directory key before
claiming a one-time prekey, and the Firebase relay transport reloads and
signature-verifies the current target bundle and re-checks local trust before
upload. The inbound handoff enforces the same trust rule before native
receive-state mutation. All of these components remain disconnected from live
chat sending and receiving. The new database rules have passed emulator tests
but have not been deployed to Firebase. Before enabling a live encrypted path,
complete real-device two-account SAS and transport recovery tests, notification
privacy, and delivery-status reconciliation.

## Two-account SAS coordinator verification coverage

The Kotlin SAS coordinator now depends on narrow local-identity and trust-store
interfaces, while production continues to use `DeviceIdentityRepository`,
`DeviceIdentityTransferDirectory`, and `TrustedDeviceRepository`. This seam
allows instrumentation tests to run two independent coordinator instances with
separate native identities, Room replay ledgers, authenticated account-scoped
directory views, and an in-memory SAS relay.

The new coordinator instrumentation suite exercises the complete native
commitment/public-key exchange, verifies that both installations derive the
same decimal and emoji comparison values, requires an explicit confirmation on
both sides, exchanges identity MACs, and checks that each local trust store
records the expected peer account/device/Ed25519 key only after completion.
Additional cases reject a CANCEL from a different account/device without
acknowledging it and reject a directory whose authenticated account differs
from the local identity. Starting, polling, and confirming re-check that the authenticated account
still matches the coordinator's local account. The Firebase SAS transport is
also bound to that original UID: inbox reads use a fixed account path and
re-check authentication after the asynchronous read, while uploads and
acknowledgements fail closed after an account switch. The relay used by the
coordinator test is test-only; production SAS messages still use the existing
Firebase Realtime Database transport and replay ledger.

A transient local trusted-device storage failure leaves the SAS MAC delivery
unprocessed. The coordinator now retries completion when the durable receiver
redelivers the same MAC, allowing the trust write to succeed without requiring
a new SAS attempt. An instrumentation case simulates a fail-once trust store and
verifies retry, completion, and relay acknowledgement.

This is coordinator-level protocol coverage, not yet a two-physical-device
Firebase emulator or real-network recovery test. The SAS screen and protocol
remain separate from live chat sending and receiving.

The SAS inbox receiver also verifies that each transport delivery key equals
the canonical message's derived delivery ID before registering or acknowledging
it. Instrumentation coverage now exercises callback failure followed by successful
redelivery, acknowledgement failure after the Room processed marker (without
replaying the callback), rejection of a mismatched transport key, and retention
of conflicting duplicate content without acknowledgement. The production
ordering remains: register, run the coordinator callback, mark processed, then
acknowledge the relay. These tests use the in-memory transport and Room ledger;
Firebase emulator/real-network failure injection remains outstanding.

The Realtime Database SAS rules regression suite additionally verifies that
senders cannot spoof the authenticated account or add unrecognized record
fields, timestamps outside the two-minute future-clock tolerance and ten-minute
maximum lifetime are rejected, only the recipient can delete a delivered
message, and blocking in either direction prevents creation. These checks run
against the local Realtime Database emulator through the repository's
 tests/database-rules workflow; they do not deploy rules or require a paid
Firebase plan.

The Firebase SAS inbox transport now prunes expired relay rows during recipient
polling. Cleanup is limited to entries with a valid 64-character hexadecimal
delivery key and an explicit numeric expiry at or before the poll's captured
clock time. The transport revalidates the authenticated account immediately
before each delete; unexpired entries and entries without a trustworthy expiry
are left untouched. This avoids repeatedly reading expired SAS traffic without
broadening cleanup to normal chats or the Olm inbox.
