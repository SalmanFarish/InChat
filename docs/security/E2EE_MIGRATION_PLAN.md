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

The isolated Olm handoff now uses a strict version-2 authenticated inner payload for direct text messages. Message ID, direct conversation ID and peer account, sender account/device, recipient account/device, timestamp, and text are encoded inside the Olm plaintext, so successful Olm decryption authenticates these fields as a unit. The receiver checks every identity against the durable receipt and the local account/device before mapping it into the Keystore-encrypted record in the dedicated encrypted_olm_messages table. The direct conversation ID must match the existing canonical ChatRepository ID derived from the two participants; a delivery may target either the peer's devices or another device owned by the sender. The handoff coordinator also obtains the authenticated local account from its injected auth-backed provider and rejects receipts addressed to another local device; unknown fields, unsupported versions, non-canonical JSON, fractional timestamps, and mismatches are rejected. The first-message preparer accepts this typed payload and validates it before claiming a one-time prekey. This schema is text-only: attachment descriptors, edits, reactions, groups, and nickname semantics still need separately authenticated protocol definitions. Raw legacy plaintext is not accepted by this isolated handoff. This does not enable encryption or change live chat behavior.

The receipt ledger is deliberately separate from Firebase plaintext listeners, the existing chat receiver, notifications, and the UI. The encrypted-only pending-receipt processor now routes durable receipts through the SAS-gated handoff coordinator. The coordinator commits the decrypted message and receipt in one Room transaction, then finalizes native staged plaintext. Receipt states distinguish PENDING, WAITING_FOR_DEVICE, PROCESSED, FINALIZED, and REJECTED so untrusted devices do not block newer messages and unknown Olm wire discriminators are quarantined instead of retried forever. No live chat behavior has changed.

The isolated inbox receiver now polls the authenticated device inbox, checks that the transport sender-device field matches the sender device embedded in the encrypted envelope, validates each envelope's routing metadata, and registers the ciphertext in the Room receipt ledger on an IO dispatcher. Instrumentation coverage also verifies that mismatched sender metadata is not acknowledged and that a failed remote acknowledgement is retried safely from the already-persisted pending receipt. It deletes the remote item only after the local receipt insert or duplicate check succeeds. A crash after local persistence but before remote deletion is safe: the next poll sees the same pending receipt and retries the acknowledgement. A reused sender/device/message identity with different ciphertext is treated as a conflict and is deliberately not acknowledged. This receiver only stages ciphertext; it does not decrypt, create plaintext messages, or connect to the live chat cache.

The isolated inbox receiver now also has its own `EncryptedOlmInboxWorker`.
After Firebase restores the account, startup schedules an immediate, network-
constrained inbox poll and a 15-minute periodic recovery poll for the existing
local account/device identity. The worker binds its Firebase transport to the
scheduled account and rechecks that account after asynchronous reads and before
remote mutations. A stale account or device scope is retired without deleting
the Room receipt ledger. Reconnection or a later process start therefore
retries any relay item whose local receipt was stored but whose acknowledgement
or remote deletion was interrupted. After the ciphertext is durably staged and remotely acknowledged, the same
isolated worker now scans pending Room receipts. It resolves each sender's
public device bundle through the signature-verifying directory, requires the
matching device-local SAS trust record, classifies the explicit Olm wire-message
type, and calls the existing authenticated handoff coordinator. Decrypted
messages are committed only to the dedicated encrypted_olm_messages Room table;
no Firebase plaintext listener, notification preview, or live chat UI is connected.

The Olm Firebase transport now separates its Realtime Database operations
behind `OlmRelayStore`, with the production adapter still using the same
`olmInbox` and `olmDeliveryAcks` paths. A failure-injection suite exercises a
relay-accepted upload whose completion is lost, retry of the identical
immutable ciphertext, failed inbox reads, acknowledgement-write completion
loss, inbox-delete completion loss, and account changes during directory/inbox
reads. Relay records are parsed with exact field allowlists before they can
enter the receipt or acknowledgement pipeline. These tests do not contact the
Firebase Auth emulator and do not replace the separate physical-device /
Firebase reconnect validation.

The durable sender outbox remains at-least-once. Its isolated Firebase Realtime Database transport uploads immutable ciphertext envelopes to per-user, per-device `olmInbox` paths. Rules require authenticated sender and recipient device registrations, enforce create-once / exact-retry semantics, reject changed payloads, restrict reads and deletes to the recipient, and prevent delivery between mutually blocked accounts. The transport does not use `chats/{chatId}/messages`.

An isolated `OlmOutboundDeliveryCoordinator` now joins the fan-out planner, first-message preparer, and durable queue for version-2 direct text messages. It includes every published recipient device and the sender's other published devices, validates the authenticated direct conversation ID and all per-device payloads before attempting any prekey claim, prepares every ciphertext copy before inserting the complete batch, and asks the caller to schedule the account/device worker only after the Room enqueue. Replaying the same message is idempotent: the first stored ciphertext is retained and scheduling is requested again to repair a missed trigger. The schema remains direct-text only; group messages require their own authenticated conversation schema.

The isolated `EncryptedOlmDeliveryWorker` now processes sender acknowledgements before uploading due ciphertext rows, validates the authenticated account and existing local device identity, uses durable leases and retry state, and runs with a network constraint plus periodic recovery scheduling. Startup auth restoration recreates the account/device schedule without creating a device identity. These components remain disconnected from `ChatRepository`, `GroupChatRepository`, ordinary message listeners, and the live UI. The receiver receipt ledger provides ciphertext deduplication and acknowledgement recovery. The separate `OlmInboundHandoffCoordinator` now also requires a locally SAS-verified sender identity key before it advances a receive ratchet, consumes a one-time prekey, or stages plaintext; an identity-key change is rejected until it is re-verified. It then validates authenticated payload metadata and commits the message and receipt atomically in Room. The inbound handoff remains isolated from live listeners and UI.

## Rollout safety

- Existing plaintext message history remains untouched.
- Version 1 encrypted messages must never silently fall back to plaintext.
- Do not connect the isolated prototype to live chats until real two-device Firebase recovery, user-visible encrypted notification privacy and tap routing, delivery-status reconciliation, and final end-to-end validation are complete.
- Keep all infrastructure within free Firebase Spark capabilities; no card-required or paid storage services.



## Encrypted notification privacy boundary

The isolated encrypted push path now has a separate authenticated Cloudflare
endpoint, `POST /v1/olm/notify`. The sender's encrypted outbox worker calls it
only after the immutable ciphertext has been accepted at the exact recipient
device's `olmInbox` path. The normal `/v1/events/message` plaintext route is
not called or modified by this path.

The Worker derives the recipient account from the existing canonical direct
conversation and verifies the sender is a participant. It rejects group chats,
checks both block directions, confirms that both sender and recipient device
entries are still published, and validates the exact relay record against the
authenticated sender account/device, message ID, recipient device and Olm
envelope metadata. It reads only the matching
`privateUsers/{uid}/olmDeviceFcmTokens/{deviceId}` token; it never falls back
to the legacy account-level FCM token. Per-account and per-IP Worker rate
limits apply, and ETag-guarded dispatch receipts prevent concurrent claims and suppress
retries after a successful/unregistered result is recorded. Because FCM
acceptance and the RTDB receipt cannot be committed atomically, a lost network
response after FCM acceptance may cause a duplicate wake-up; the receiver
coalesces inbox work and deduplicates ciphertext using its stable delivery ID.
Token cleanup is conditional on the stored token still matching the rejected
token, protecting a concurrently rotated registration.

FCM receives a **normal-priority data-only** message containing only `type=encrypted_olm` and
the deterministic `deliveryId`. No FCM `notification` object, chat ID,
sender display name, group name, message text, or decrypted preview is sent.
The Android FCM service accepts only this narrow signal and schedules an
immediate `EncryptedOlmInboxWorker` pass for its existing authenticated
account/device identity. Startup continues to maintain the separate periodic
recovery poll. The wake-up metadata is not used as a chat route or message
identity; the encrypted relay record and the authenticated Olm payload remain
the source of truth.

The encrypted inbox worker now performs authenticated inbound decryption
and atomic local Room persistence after the durable ciphertext acknowledgement.
It still does not display a user-visible message notification, and the resulting
dedicated encrypted_olm_messages table remains isolated from the live chat UI. Decrypted notification
presentation and tap navigation must wait for the remaining notification and
live-chat integration milestones. Until then, a silent wake-up is safer than
showing an unverified sender or conversation.

The existing `chat_message` and `group_message` plaintext notification
presentation, history, and delivery pipeline remain unchanged. No Firebase
Cloud Function, Firebase Cloud Storage, R2 bucket, Durable Object, or paid
infrastructure is introduced by the encrypted endpoint.

**This completes isolated encrypted FCM dispatch and inbox wake-up plumbing,
not production E2EE rollout.** Real two-device Firebase validation, decrypt-
to-UI handoff, user-visible encrypted notification behavior, tap routing, and
delivery-status reconciliation are still required before connecting the
normal composer or enabling encrypted messaging by default.

## Encrypted delivery status lifecycle

Each account/device outbox row distinguishes an active upload lease from
successful relay acceptance. A successful Firebase Realtime Database write
moves the immutable envelope to `UPLOADED` and records its next exact-retry
time. If no recipient acknowledgement arrives within 15 minutes, the same
delivery ID and ciphertext are retried. The existing bounded attempt policy
still applies. An interrupted upload remains `IN_FLIGHT` until its lease
expires and is reclaimed.

The encrypted-only status projection combines the sender's scoped outbox
states with its durable recipient acknowledgement ledger:
`QUEUED` → Waiting, `IN_FLIGHT` → Sending, `UPLOADED` → Sent,
a recipient-device acknowledgement → Delivered, and an exhausted queue row
→ Failed. An acknowledgement from another device owned by the sender is not
counted as recipient delivery. The projection reads status strings only; it
does not expose queued ciphertext. "Delivered" means at least one recipient
device durably staged the ciphertext. Read receipts are not implemented, so
this path never reports "Read".

## Encrypted delivery acknowledgement persistence

Sender-side recipient-device acknowledgements are now retained in a separate,
metadata-only Room table. After the remote acknowledgement is deleted, the
worker records the exact sender/recipient account, device, message, and delivery
identity before removing the corresponding ciphertext outbox row. A crash
between local receipt recording and outbox cleanup is safe: the outbox copy may
be retried, and the recipient's durable receipt ledger emits the same
acknowledgement again. Duplicate local receipt inserts do not replace the
original acknowledgement timestamp.

This table contains no ciphertext, plaintext, or preview. Its query boundary
exposes delivered message IDs scoped to the sender account/device and recipient
account, ready for the encrypted chat status presenter. An acknowledgement
means at least one recipient device durably staged the ciphertext; it does not
mean the message was decrypted, displayed, or read. Encrypted read receipts and
live chat UI integration remain outstanding. Existing plaintext status logic
is unchanged.

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

The Firebase transport's relay access is now behind a narrow store interface,
with Firebase Realtime Database remaining the production adapter. Instrumentation
tests use a failure-injecting store to verify that transient inbox-read failures
leave messages available for the next poll, failed acknowledgements preserve the
relay row for retry, and account changes immediately before upload, read return,
expiry cleanup, or acknowledgement fail closed. They also verify canonical
upload fields, strict rejection of unknown relay fields, and that malformed
unexpired rows are retained rather than destructively guessed away. These tests
exercise transport control flow; Firebase emulator/real-network transport
failure injection remains a separate verification step.

The SAS inbox expiry check refreshes its clock for every relay row after the
asynchronous read, including after any preceding cleanup operation. This prevents
an entry that expires during the network read or a slow earlier deletion from
being returned using a stale timestamp. Clock-advancing transport tests verify
expired rows are neither delivered to the coordinator nor retained in the inbox.

The SAS sender also rechecks transaction expiry immediately before the relay
write, after payload preparation. This avoids submitting an upload whose
transaction expired during local preparation; an advancing-clock instrumentation
test verifies that no relay write occurs at the expiry boundary.


## Firebase SAS transport emulator integration

A dedicated Android instrumentation test now exercises the production
`FirebaseSasVerificationRelayStore` adapter against the local Firebase Auth
and Realtime Database emulators. It creates two independent named Firebase
apps with anonymous emulator accounts, registers a test device for each
account, and drives an actual SAS relay upload, exact retry, recipient read,
and acknowledgement/delete through the Android Firebase SDK.

The integration test also verifies that the database rules reject a changed
payload under an existing delivery ID and prevent the sender from reading or
deleting the recipient's relay row. It is opt-in through the
`firebaseEmulatorHost` instrumentation argument, so normal Android test runs
do not silently depend on emulators being active. The test uses only the
non-production `demo-inchat-rules` project and never deploys rules or contacts
the production Firebase project.

The in-memory transport suite continues to cover deterministic transient read
and acknowledgement failures, account switching, and retry ordering. Real
network interruption/reconnection on physical devices remains a separate
validation step.


## Sender-to-recipient recovery integration coverage

The Android instrumentation test
`OlmSenderRecipientRecoveryIntegrationTest` drives a complete isolated
sender-to-recipient direct-text path with distinct native Olm stores and
separate sender/recipient Room databases. It exercises the outbound delivery
coordinator, Room-backed outbox, production Firebase transport adapter over a
test-only shared `OlmRelayStore`, recipient inbox receiver and durable receipt
ledger, native authenticated decryption, the atomic message/receipt handoff,
and the sender's metadata-only acknowledgement recorder.

Failure injection commits an encrypted inbox write and then loses its completion,
forcing the sender to retry the identical delivery ID and ciphertext. A second
failure commits the recipient acknowledgement and loses its completion, forcing
the recipient to reopen Room, reuse its PENDING receipt, retry the acknowledgement,
and only then remove the inbox record. The test then commits the decrypted message,
redelivers the same envelope to exercise PROCESSED-receipt deduplication, and
verifies that the sender records delivery metadata and removes its outbox row.
The shared relay rejects changed content under an existing delivery ID and the
test checks that plaintext is absent from relay records.

This is an in-process two-endpoint integration test with an in-memory relay and
a permissive test trust verifier. It does not exercise Firebase Realtime Database,
Firebase Auth, the SAS exchange, two physical devices, or the live chat producer,
listeners, and UI. Physical-device/Firebase reconnection validation and live
messaging integration remain separate rollout requirements. The test does not
require the Firebase Auth emulator.

The sender portion also runs the production `OlmOutboundDeliveryPreparer`
rather than injecting a prebuilt ciphertext. Narrow directory/session
interfaces let the instrumentation test provide a controlled directory and an
isolated native sender store while keeping the production preparer unchanged.
The test therefore exercises recipient-device lookup, identity-bound trust
checking, one-time-prekey selection/claim, native outbound-session creation,
authenticated payload encryption, and envelope construction before the Room
outbox handoff. The test directory returns a recipient bundle generated by the
recipient's native identity; it does not exercise Firebase directory signature
fetching or the server-side atomic prekey-claim transaction.

The recovery integration test also injects the sender-side crash window where
the relay acknowledgement is deleted but the client loses the completion before
the metadata-only Room receipt and outbox cleanup. It reopens the sender database,
waits for the persisted retry interval, resends the identical ciphertext, and
verifies that the recipient's already-PROCESSED receipt republishes the
acknowledgement without a second plaintext insertion. The sender then records
the receipt and removes the durable outbox row. This covers the full
ack-delete / retry / duplicate-receive recovery loop without Firebase Auth.

The sender and recipient native accounts now use unique, test-owned
`CryptoStateStore` IDs. `OlmSessionRepository` accepts a narrow local-identity
interface so both endpoint repositories can bind to their isolated native
stores and exercise the production session/prekey validation paths. The test deletes both stores and their Keystore keys afterward;
it does not open, consume, or modify the installed app's real device identity
or its published one-time prekeys.

## Encrypted relay rules lifecycle integration

The Realtime Database rules suite now exercises a complete encrypted delivery
lifecycle against the local Database Emulator: the sender creates the immutable
ciphertext inbox record; only the recipient reads it, writes the exact metadata
acknowledgement, and removes the inbox copy; only the sender reads and removes
the acknowledgement. The test then simulates the sender having consumed the
remote acknowledgement while its local outbox cleanup was interrupted: the
same immutable envelope is resent under the original deterministic delivery
ID, the recipient acknowledges the duplicate again, and the sender consumes
the regenerated acknowledgement.

This suite uses `@firebase/rules-unit-testing` authenticated contexts and only
the Realtime Database Emulator. It does not start or contact the Firebase Auth
emulator, use real Firebase accounts, access production data, deploy rules, or
require a paid Firebase plan. It verifies database authorization and record
lifecycle, not Android SDK connectivity or real-network behavior.


## Android Olm Realtime Database adapter emulator integration

`FirebaseOlmRelayStoreEmulatorTest` now exercises the production
`FirebaseOlmEnvelopeTransport` and `FirebaseOlmRelayStore` through the Android
Firebase SDK against a dedicated `demo-inchat-olm-relay` Database Emulator.
It verifies offline queued-upload recovery after reconnect, ciphertext-only
inbox serialization and parsing, recipient acknowledgement with a resolved
server timestamp, and inbox/ack removal.

This test injects synthetic transport identities and never initializes
FirebaseAuth or the Auth emulator. Its separate emulator configuration loads
a narrowly scoped test rules file for only `olmInbox` and
`olmDeliveryAcks`; that rules file is not referenced by the normal Firebase
configuration. Consequently, this Android adapter test verifies SDK wiring and
transport serialization, not production authorization. The independent
Realtime Database rules suite continues to verify authenticated ownership,
recipient-only inbox access, sender-only acknowledgement access, and immutable
exact retries. Neither suite contacts production Firebase or changes plaintext
chat paths, local account identities, or published prekeys.


## Device-scoped encrypted push registration

FCM token registration retains the existing account-level token for all current
plaintext notification behavior. When an installation already has a usable
local Olm identity, the same token is additionally registered at
`privateUsers/{uid}/olmDeviceFcmTokens/{deviceId}`. The registration worker
only reads an existing identity; it never creates a device ID or native Olm
state. Publishing a verified public device bundle also best-effort refreshes
that device's token mapping, covering the case where login-time token
registration ran before the Olm identity was created. A token refresh or a
later login continues to use the durable registration worker. Logout removes
this installation's device-scoped token together with the legacy account
token. Revoking a published device also removes only that device's private
token mapping after its public directory entry is deleted; the account-wide
legacy token is retained for existing plaintext messaging. The Worker can
therefore target a specific published Olm device instead of assuming one FCM
token represents every device on an account.


## Authenticated direct-conversation routing (payload schema v2)

The authenticated Olm plaintext schema is now version 2. It carries both the
existing direct-chat ID and the other participant's account ID. The codec
requires that the conversation ID exactly match the same lexicographically
ordered `uidA_uidB` convention used by
`ChatRepository.getChatRoomId`. It also requires that a delivery target be
either the conversation peer's account or another device owned by the sender.
This keeps sender-owned multi-device copies attached to the original
conversation instead of incorrectly deriving a different chat from the
per-device recipient account.

Inbound handoff now rejects any cache mapping whose chat ID differs from the
authenticated conversation ID. This makes conversation routing available only
after successful Olm decryption and strict schema validation; Firebase relay
metadata and future push data are not trusted to choose the local chat.

Schema version 1 is intentionally not accepted by the current strict decoder,
because it contains no authenticated conversation route. Existing live chat
sending, receiving, notifications, and the plaintext Cloudflare pipeline remain
unchanged. This protocol work is still isolated and does not enable encrypted
message delivery from the normal composer.


## Isolated inbound decrypt-and-persist processing

OlmPendingReceiptProcessor now advances the ciphertext receipt ledger in bounded batches. It first retries receipts whose Room message/receipt transaction committed but whose native staged-plaintext cleanup did not. The native finalize operation is idempotent; after it succeeds, the receipt moves from PROCESSED to FINALIZED, so process death between native cleanup and the Room status update is recoverable.

New PENDING receipts are validated against the exact envelope sender and recipient device IDs. The explicit first-byte Olm wire discriminator selects pre-key or normal-session processing; the native vodozemac parser remains responsible for validating and decrypting the wire message. Sender device bundles are loaded through DeviceKeyDirectoryRepository, which verifies the Ed25519 identity binding and signed prekeys. The matching device-local SAS trust record is checked before the coordinator advances a receive ratchet or consumes a one-time prekey.

Receipts whose sender device is missing or not yet SAS-trusted move to WAITING_FOR_DEVICE, separately from new messages, so they cannot indefinitely occupy the head of the new-message queue. They are reconsidered on later inbox passes and return to PENDING only after the published identity and local trust are available. Structurally invalid envelopes or unknown wire types move to REJECTED; identical remote redeliveries of rejected receipts are acknowledged and removed without re-entering decryption. Conflicting ciphertext under an existing sender/device/message identity remains unacknowledged.

The handoff creates a `DecryptedOlmMessage` only in process memory, then `OlmInboundMessageCommitter` encrypts its text with a non-exportable Android Keystore AES-GCM key before writing the record and receipt atomically to the dedicated `encrypted_olm_messages` Room table. The persisted entity contains only a random 96-bit nonce and ciphertext for the text; the owner, conversation, message, sender/recipient accounts and devices, and timestamp are authenticated as GCM associated data. The DAO never returns a plaintext text column. The database now advances from schema version 10 to 11, re-encrypting any version-10 text rows in a transactional table rebuild and removing the old plaintext `text` column; existing message metadata and content are preserved. If encrypted rows already exist but the Keystore key is missing, new writes fail closed rather than silently generating a replacement key. The table has its own DAO and is not read by ChatRepository; the existing `cached_messages` table and plaintext messaging path remain untouched. Display-name, notification, tap-routing, and live-chat integration remain separate work.


## Encrypted inbound Room isolation

Decrypted Olm direct-text messages are persisted only in the dedicated encrypted_olm_messages table through EncryptedOlmMessageDao. This table is intentionally separate from cached_messages because ChatRepository observes cached_messages directly and publishes those rows to the existing chat UI. The encrypted handoff transaction encrypts text with Android Keystore AES-GCM, then writes the ciphertext record and receipt atomically without invoking LocalChatDao. A Room migration from version 10 to 11 converts any prior plaintext message rows into authenticated ciphertext records and removes the plaintext column; a migration instrumentation test verifies content and metadata survive. Vault tests cover process-instance restoration, nonce freshness, metadata binding, ciphertext tampering, unknown content versions, and missing-key behavior. Existing encrypted history prevents silent key regeneration. EncryptedOlmMessageRepository is the isolated read boundary: it scopes queries by owner/conversation and decrypts only in memory on an IO dispatcher; it is not connected to the current chat UI. This remains local at-rest protection for the E2EE prototype; device backup, secure display/notification behavior, and live chat integration remain separate rollout requirements.

## Isolated Megolm group-session foundation

The Rust crypto core now includes an isolated vodozemac Megolm primitive. A sender device creates a fresh outbound session for a group epoch, exports the signed Megolm session key, and encrypts messages into authenticated ciphertext. A recipient can create an inbound session only from that signed key. The key contains decryption capability and must be delivered separately to each currently authorized member device inside an authenticated Olm message; it must never be written to a public key directory, Firebase relay, FCM payload, or plaintext log.

The primitive returns the Megolm session ID and message index with every ciphertext. The application layer must bind these to the authenticated group ID, membership epoch, sender account/device, event ID, and recipient context. It must also durably enforce unique `(sessionId, messageIndex)` processing before accepting plaintext, while allowing exact transport retries to be idempotent. The Megolm library authenticates ciphertext signatures and MACs, but it does not replace the application replay ledger or group-membership authorization.

A recipient who receives a session key after the sender has already encrypted earlier messages starts at the key's first known message index and cannot decrypt those earlier messages. Every membership change must create a brand-new outbound Megolm session and a strictly increasing membership epoch. Distribute the new key only to devices in the post-change membership snapshot. Never reuse the previous sender session after either a member addition or removal; in particular, a removed member must not receive the new key. Concurrent membership changes must be serialized against a trusted, versioned group-membership source before a key distribution is considered committed.

The isolated Rust tests cover authorized recipient decryption, out-of-order delivery, late-member history exclusion, session-key and message-signature tampering, sender and recipient pickle restoration, and a fresh session after epoch rotation. Pickle serialization methods are only primitives: this module does not itself persist state, and the returned serialized state must be encrypted with the existing Android Keystore-backed native state store before disk persistence.

This is not yet a working group-chat encryption path. The Megolm module is not exposed through JNI, does not implement the authenticated Olm session-key distribution envelope, has no Room-backed inbound-session or replay ledger, and is not connected to Firebase, group membership repositories, attachments, notifications, or the chat UI. It does not modify or replace plaintext group messaging. The next integration milestones are: define a strict authenticated group-key-distribution payload; add JNI operations that keep Megolm private state in Rust and store pickles only through Keystore; bind group IDs and monotonically increasing membership epochs to the existing trusted group-membership model; persist inbound/outbound sessions and replay indices transactionally in Room; then test concurrent member additions/removals, offline devices, duplicate delivery, process death, and removed-member exclusion before any live-chat integration.

The Kotlin layer now also has a separate schema-version-1 `OlmGroupSessionKeyDistributionPayload`. It uses exact-field canonical JSON and binds a distribution ID, group ID, positive membership epoch, sender/recipient accounts and devices, Megolm session ID, canonical unpadded Base64URL session key, and timestamp. Decode requires the caller to supply the expected receipt identity, current group ID, expected epoch, sender, and local recipient. A membership helper rejects a sender or recipient absent from the supplied current-member snapshot. `OlmSessionRepository.encryptGroupSessionKeyDistributionEnvelope` can encrypt this typed control payload to one recipient device through an already-established Olm session without changing the direct-text schema or plaintext message routes.

This is only the typed control-message boundary, not a complete distributor: there is not yet a group-device fan-out coordinator, an authenticated source-of-truth membership-epoch service, or an inbound Room receipt/replay transaction for group key installation. The helper does not itself establish an Olm session or check the recipient's SAS trust; those must be enforced by the coordinator before it is called. In particular, the current Cloudflare group-management flow owns the member/role changes but does not yet publish a trusted cryptographic membership epoch. A caller must not invent or locally increment an epoch and treat it as authoritative. Android JVM CI must pass the codec tests before this Kotlin control-message milestone is considered verified.
