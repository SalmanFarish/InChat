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

The ledger is deliberately not connected to Firebase listeners, the existing chat receiver, message persistence, notifications, or the UI. Its markProcessed operation must eventually be coordinated transactionally with local message persistence. Otherwise a process death between marking a receipt and saving the decrypted message could lose the local copy. No live chat behavior has changed.

The isolated inbox receiver now polls the authenticated device inbox, checks that the transport sender-device field matches the sender device embedded in the encrypted envelope, validates each envelope's routing metadata, and registers the ciphertext in the Room receipt ledger on an IO dispatcher. Instrumentation coverage also verifies that mismatched sender metadata is not acknowledged and that a failed remote acknowledgement is retried safely from the already-persisted pending receipt. It deletes the remote item only after the local receipt insert or duplicate check succeeds. A crash after local persistence but before remote deletion is safe: the next poll sees the same pending receipt and retries the acknowledgement. A reused sender/device/message identity with different ciphertext is treated as a conflict and is deliberately not acknowledged. This receiver only stages ciphertext; it does not decrypt, create plaintext messages, or connect to the live chat cache.

The durable sender outbox remains at-least-once. An isolated Firebase Realtime Database transport now uploads immutable ciphertext envelopes to per-user, per-device `olmInbox` paths. Rules require authenticated sender and recipient device registrations, enforce create-once / exact-retry semantics, reject changed payloads, restrict reads and deletes to the recipient, and prevent delivery between mutually blocked accounts. The transport does not use `chats/{chatId}/messages` and has no live listener or WorkManager integration yet. The receiver receipt ledger provides local deduplication, but there is still no end-to-end transport worker or atomic decrypt-and-store transaction.

## Rollout safety

- Existing plaintext message history remains untouched.
- Version 1 encrypted messages must never silently fall back to plaintext.
- Do not connect the isolated prototype to live chats until sender transport, receiver authentication, deduplication, local persistence, notification privacy, and two-device tests are complete.
- Keep all infrastructure within free Firebase Spark capabilities; no card-required or paid storage services.
