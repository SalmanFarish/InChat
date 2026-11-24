# ADR-002: Move encrypted message relay out of Firebase

- Status: Accepted architecture direction; implementation and rollout are not yet approved
- Date: 2026-11-24
- Branch at audit: `chore/spark-free-worker-migration`
- Audited commit: `f37c52cf2f529f179f1415fd51c72a82bc573e11`

## Context

InChat's requirement is that Firebase Realtime Database must not store chat message bodies or act as the encrypted message relay. The app must still support offline recipients, multi-device delivery, groups, durable retries, and local chat history. Infrastructure must remain on genuinely free tiers, with no paid-plan upgrade, credit-card requirement, or automatic cost exposure.

A server cannot provide offline delivery without retaining something while a recipient is offline. The intended boundary is therefore:

- Message history and plaintext remain on user devices, in the app's protected local database.
- The relay may temporarily retain end-to-end encrypted ciphertext and the minimum routing metadata needed to deliver it.
- After the recipient has durably stored the ciphertext locally and acknowledged it, the relay removes the queued ciphertext.
- Undelivered ciphertext must have a finite, explicit expiry policy. The duration is a product policy and has deliberately not been chosen in this ADR.

This is not a claim that no server ever handles messages. It is a decision that Firebase will no longer be the message store or delivery queue, and that any temporary server copy is ciphertext-only and deleted on acknowledgement or expiry.

## Audit findings at the audited commit

### The encrypted relay currently writes to Firebase

`app/src/main/java/com/example/inchat/crypto/FirebaseOlmEnvelopeTransport.kt` contains the production `FirebaseOlmRelayStore` implementation:

- Envelopes are written below `olmInbox/{recipientUid}/{recipientDeviceId}/{deliveryId}`.
- Recipient-device acknowledgements are written below `olmDeliveryAcks/{senderUid}/{senderDeviceId}/{deliveryId}`.
- The production implementation directly uses `FirebaseDatabase` and `FirebaseAuth`.
- Inbox reads/deletes and acknowledgement reads/deletes are also Realtime Database operations.

The Firebase rules constrain sender/recipient device ownership, exact immutable retries, blocked users, and direct/group conversation scope. Group delivery additionally checks the current group crypto-membership epoch and member roster. These checks must not be lost during migration.

### The client already has the reliability machinery to preserve

- `OlmDeliveryQueue` and `RoomOlmDeliveryQueueStore` provide an account/device-scoped, durable sender outbox, stable delivery IDs, leases, retry state, idempotent inserts, and exact acknowledgement matching.
- `EncryptedOlmDeliveryWorker` runs the sender queue and acknowledgement processor, and is scheduled for immediate recovery plus a 15-minute safety poll.
- `EncryptedOlmInboxWorker` polls the recipient inbox.
- `FirebaseOlmInboxReceiver` stores an envelope in the local inbound receipt ledger before acknowledging and removing the remote copy.
- `OlmInboundDeliveryLedger` deduplicates by recipient account, sender account/device, message ID, and recipient device, and rejects a reused identity with different ciphertext.
- `OlmDeliveryAckProcessor` matches acknowledgements against the immutable local outbox identity before it removes a queue row.

The new transport must implement the existing `OlmInboxTransport` and `OlmOutgoingDeliveryTransport` boundaries (or a renamed equivalent). It must not replace the Room queue, ratchets, receipt ledger, or cryptographic validation.

### Firebase is also still used for the legacy message-history path

`app/src/main/java/com/example/inchat/data/repository/ChatRepository.kt` still:

- Listens to `chats/{chatId}/messages` and persists returned message records into Room.
- Reads remote message history for older-message pagination.
- Looks up individual remote messages during pending-message reconciliation.

The legacy plaintext send helper is disabled, but these read/reconciliation paths remain. Therefore, replacing only `olmInbox` would not satisfy the complete requirement. Direct and group chat history/timeline integration must be migrated to local encrypted records and per-device delivery before the legacy Firebase message reads can be retired.

`GroupChatRepository` continues to use Firebase for group metadata, member indexes, read state, and the authoritative crypto-membership snapshot. Those are control-plane records, not message bodies. They may remain during the first transport migration, provided encrypted message content, message history, and delivery queues no longer depend on them.

### The current Cloudflare Worker is not the encrypted message relay

The Worker currently exposes `POST /v1/olm/notify` and `POST /v1/olm/notify-group` for metadata-only FCM wake-ups. Its notification validation reads the Firebase `olmInbox` record. The Worker also uses Firebase for authentication-related checks, device/token lookup, group membership, previews, and account cleanup.

The current `cloudflare/attachments-worker/wrangler.jsonc` has no Durable Object binding or SQLite migration. The existing Worker therefore cannot yet accept or retain the encrypted envelope independently of Firebase.

## Decision

Use a **Cloudflare Durable Object with the SQLite storage backend** as the temporary encrypted mailbox for each recipient device, behind the existing InChat Cloudflare Worker.

Keep the existing Cloudflare Worker and Firebase Authentication identity for request authentication. During the initial migration, Firebase may remain the metadata/control plane for published public device keys, blocked-user state, group membership, and crypto-membership epochs. These metadata reads must not contain message bodies or ciphertext. The device directory and group authorization checks must be performed server-side before a mailbox write; client-side checks alone are not sufficient.

The Durable Object's SQLite database is the source of truth for pending encrypted deliveries and their acknowledgement state. Use a deterministic mailbox identity derived from the authenticated account UID and validated device ID. A client must never choose another account's mailbox identity.

### Why SQLite-backed Durable Objects

The audited Cloudflare documentation currently lists Durable Objects on Workers Free, with SQLite as the only backend available on Free. SQLite gives a strongly consistent, transactional mailbox boundary and supports alarms for bounded expiry cleanup.

Relevant documented Workers Free / Durable Objects Free allowances as of 2026-11-24:

- Workers: 100,000 requests per day and 10 ms CPU per invocation.
- Durable Objects: 100,000 requests per day and 13,000 GB-seconds of duration per day.
- SQLite-backed Durable Objects: 5 million row reads per day, 100,000 row writes per day, and 5 GB of stored data total on the Free plan.
- The Free plan has storage/write ceilings; operations that exceed a Free limit fail rather than being an entitlement to paid capacity. The app must treat quota exhaustion as a retryable delivery outage and preserve its local outbox.
- The documented Workers Paid plan has a $5/month minimum. It is explicitly out of scope.

These are service ceilings, not a capacity guarantee for a particular user base. Before rollout, load tests and telemetry must show that the expected number of devices, message sizes, retries, reads, writes, acknowledgement deletes, and retained ciphertext fit comfortably inside the Free quotas. Free-plan exhaustion must never trigger an upgrade or a paid fallback.

### Why not the other Cloudflare storage products

- **Workers KV:** its Free allowance is 1,000 writes/day, with a one-write-per-second same-key limit. KV is not the right consistency primitive for a mutable delivery queue with acknowledgement races, exact retries, and immediate removal.
- **Cloudflare Queues:** the Free plan fixes message retention at 24 hours and individual queue messages are limited to 128 KB. The existing encrypted envelope path accepts envelopes up to 2 MiB, and offline retention needs a deliberate product policy rather than an imposed 24-hour queue expiry.
- **D1:** the Free plan is a viable general database, but an individual D1 value/row is limited to 2,000,000 bytes. The current relay envelope bound is up to 2 MiB before record metadata, so that row limit is too close to the existing wire limit. A per-device Durable Object also gives a natural serialization boundary for a mailbox.
- **R2 or another external storage provider:** not selected; no new paid or card-gated storage product is permitted.

No service beyond the existing Cloudflare Worker and a Free-plan SQLite Durable Object is part of this decision.

## Target delivery protocol

The server stores one independently encrypted envelope for one recipient device. Group fan-out remains client-side: the sender's existing group-key distribution and Megolm logic prepares a separate Olm envelope for each target device, and each envelope is placed in that device's mailbox.

The Worker API should expose the following authenticated operations (exact route spelling may be finalized during implementation):

1. **Enqueue:** sender submits a ciphertext envelope, stable `deliveryId`, message ID, sender/recipient account and device identities, and the minimum authenticated conversation scope. The Worker derives the sender account from the verified Firebase ID token; it must not trust a body-supplied sender UID.
2. **Read inbox:** recipient device obtains a bounded page of pending envelopes for its own mailbox. The request must be authenticated and the device must still be published for that account.
3. **Acknowledge:** recipient submits an ACK only after the encrypted envelope has been durably staged in its Room inbound receipt ledger. The Worker validates the ACK against the stored delivery and writes an idempotent receipt to the sender-device mailbox.
4. **Remove received envelope:** after the ACK is durably present in the sender mailbox, the recipient-side envelope is deleted. If this delete fails, a later poll may return the same envelope; the recipient receipt ledger and idempotent ACK operation make that retry safe.
5. **Read acknowledgements:** sender device retrieves a bounded page of ACKs from its own mailbox and validates each one against the local Room outbox.
6. **Remove acknowledgement:** sender removes the exact ACK after its local delivery receipt has been persisted and the matching outbox delivery has been advanced.

The Worker/DO must make each individual mailbox mutation transactional. The ACK handoff crosses sender and recipient mailbox objects, so it is deliberately at-least-once rather than falsely claiming a distributed transaction: write the sender ACK idempotently first, then delete the recipient copy. A retry must converge without losing a delivery or creating a second logical message.

### Mailbox record contract

The relay may store only:

- Stable delivery ID and message ID.
- Sender and recipient account/device routing identifiers.
- The encrypted Olm envelope bytes.
- Minimal direct/group scope needed for authorization and epoch validation.
- Server-created timestamp, expiry timestamp, byte count, and delivery/ACK state.

The relay must never store message plaintext, decrypted previews, media keys, Olm/Megolm private state, private identity keys, or group session keys. It must not decrypt envelopes. Avoid logging request bodies, tokens, envelope content, or full mailbox rows.

The `deliveryId` is the idempotency key. First insert is create-only. An exact retry returns the existing accepted result without replacing data or extending expiry. Reuse of a delivery ID with different immutable fields or ciphertext returns a conflict and is never treated as a successful retry.

Every enqueue must enforce server-side validation of:

- Firebase ID token and authenticated sender UID.
- Published sender and recipient device ownership/current status.
- Both block directions.
- Envelope version, ciphertext encoding/size, and envelope sender/recipient device IDs.
- Canonical direct conversation participants, or current group membership and exact crypto-membership epoch.
- Per-device/account queue count and byte budgets, request rate limits, and maximum envelope size.

Inbox and ACK reads must be paginated and bounded. The current Firebase implementation reads the full device inbox in one operation; the replacement must not repeat that unbounded-read behavior.

### Expiry and cleanup

Each accepted delivery must have a server-controlled `createdAt` and `expiresAt`. Use Durable Object alarms to sweep expired rows in bounded batches and keep the next alarm at the earliest remaining expiry. Cleanup must not depend on an Android client polling.

The exact maximum offline-retention duration is intentionally **not decided in this ADR**. Before production rollout, define it as an explicit product policy and configure it centrally. Expiry must be visible to the sender as a terminal/expired delivery state; do not silently delete an undelivered envelope and report it as delivered.

Enforce mailbox limits before accepting a new envelope. If the mailbox is full, the request fails with a retryable capacity response and the sender's Room outbox row remains queued. Do not discard another message to make room.

## Notifications

FCM remains a best-effort wake-up channel, not the message transport or source of truth. After the Durable Object commits an envelope, the Worker may send the existing data-only FCM wake-up containing only the opaque delivery ID. The recipient always fetches the authoritative ciphertext from its mailbox.

The notification path must be updated to validate that the delivery exists in the Durable Object, not by reading `olmInbox` from Firebase. A failed FCM send must not roll back or delete a committed mailbox delivery; periodic inbox polling remains the recovery path. Keep notification dispatch idempotency and never put message text, sender display names, group names, or plaintext previews in FCM payloads.

## Migration boundaries

This decision is intentionally staged:

1. Implement and test the Durable Object mailbox and Worker API without changing Android production routing.
2. Add an HTTP client adapter implementing the existing Kotlin relay interfaces, using the authenticated Firebase ID token and the active device identity. Keep the current Firebase adapter available only for migration/rollback until the new path is verified.
3. Move direct-message encrypted envelope delivery and inbox/ACK polling to the Worker mailbox.
4. Move group-message envelopes and group-key distribution envelopes through the same per-device mailbox, preserving current group epoch and trust checks.
5. Move encrypted notification validation to the Worker mailbox.
6. Connect locally decrypted, authenticated Room message records to the chat timeline and delivery/read-status UI; remove Firebase message-history, per-message lookup, and legacy reconciliation dependencies from the live path.
7. Migrate any remaining message receipts/previews that would otherwise reveal message content or require a Firebase message record.
8. After every send/read/history path is proven to use local Room plus the mailbox, deny new writes to legacy Firebase message nodes. Existing historical Firebase records are not automatically deleted by this ADR; any one-time historical-data deletion requires a separate reviewed operation and must not risk users' local history.

Firebase Authentication, public device-key metadata, account/profile data, group metadata, and non-message settings are not removed by this decision. Removing those control-plane dependencies is a separate architecture change and must not be implied by the phrase "Firebase-independent message transport."

## Required tests before routing real users

- Worker unit tests for authenticated ownership, device revocation, direct/group scope, block state, malformed/oversized envelopes, and exact retry/conflict behavior.
- Durable Object tests for transactional inserts, duplicate requests, mailbox quotas, paginated reads, ACK write-before-delete recovery, sender ACK cleanup, alarm scheduling, expiry, and concurrent enqueue/ACK/read races.
- Quota tests that fail closed at Worker, DO request, SQLite read/write, and storage ceilings without losing Room outbox records or switching to paid services.
- Kotlin fake-transport and instrumentation tests proving that Room receipt persistence precedes ACK, retries remain idempotent, and account/device switching cannot redirect a worker.
- Two independent physical Android devices for online delivery, offline recipient delivery, process death, reconnect, multiple recipient devices, group membership changes, and duplicate wake-ups.
- Confirm that no message body, decrypted preview, media key, or private crypto state is written to Firebase, the Worker database, logs, or FCM.

## Billing and deployment gate

Only the existing Workers Free plan and SQLite-backed Durable Objects Free allowances described above may be used. Do not select Workers Paid, D1 paid capacity, R2, Queues requiring different retention, or any service that asks for card/billing activation. Do not add a payment method, upgrade a plan, provision a binding, or deploy a migration as part of this ADR.

Cloudflare's public pricing documentation establishes Free-plan availability and the quotas above, but it does not establish whether the user's existing account UI may request a payment method for this exact account operation. That account-specific, no-card prerequisite remains a hard pre-deployment check. If enabling the SQLite Durable Object on the existing account requires a paid plan or payment method, stop; do not proceed with this design or suggest adding a card.

## References

- Cloudflare Workers pricing: https://developers.cloudflare.com/workers/platform/pricing/
- Cloudflare Workers limits: https://developers.cloudflare.com/workers/platform/limits/
- Durable Objects overview: https://developers.cloudflare.com/durable-objects/
- Durable Objects pricing: https://developers.cloudflare.com/durable-objects/platform/pricing/
- Durable Objects limits: https://developers.cloudflare.com/durable-objects/platform/limits/
- Cloudflare Queues limits: https://developers.cloudflare.com/queues/platform/limits/
- Cloudflare Workers KV limits: https://developers.cloudflare.com/kv/platform/limits/
- D1 limits: https://developers.cloudflare.com/d1/platform/limits/
