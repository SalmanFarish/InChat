# Inbound Olm decryption recovery design

Status: isolated inbound handoff coordinator implemented. Room-side atomic message-and-receipt commit, native receipt-keyed pending-plaintext staging/recovery/finalize, and a coordinator for the two-phase handoff are covered by instrumentation tests. The coordinator handles the post-Room-commit/pre-native-finalize recovery path. It is not connected to Firebase inbox polling, live chat listeners, UI, notifications, or message previews.

## Why the current sequence is unsafe

The native Olm decrypt functions persist the advanced receive ratchet to the Android Keystore-backed AtomicFile before returning plaintext to Kotlin. Room currently stores the inbound ciphertext receipt separately. If the process dies after the native state write but before Room commits the plaintext message, a retry can no longer safely decrypt the same Olm message: the receive ratchet has already advanced.

The same recovery requirement applies to the first pre-key message. Native inbound-session creation consumes the local one-time key and persists the new session before returning the initial plaintext. It must not be connected to chat persistence until the initial plaintext is recoverable too.

Marking the Room receipt processed before saving the message is not a fix. A crash between those operations would permanently suppress a message that was never stored.

## Required two-phase handoff

Use the stable 64-character SHA-256 inbound receipt ID already derived by `OlmInboundDeliveryLedger` from sender account, sender device, message ID, and local recipient device. Pass that ID into the native prepare operation.

1. **Native prepare (implemented, isolated):** `nativePrepareOlmMessage` and `nativePrepareInboundOlmSession` stage a receipt-keyed record containing the stable receipt ID, exact ciphertext bytes, session ID, and plaintext in the Android Keystore-encrypted device state. For a pre-key message, one-time-key consumption, the new session, and pending plaintext share one encrypted AtomicFile write.
2. **Recover (implemented, isolated):** A repeated prepare with the same receipt ID and exact ciphertext returns the already-staged plaintext without decrypting or advancing the ratchet again. A different ciphertext or mismatched session ID for that receipt is rejected. Native pending records are bounded to 256; finalize committed receipts to free capacity.
3. **Room commit (implemented, isolated):** `OlmInboundMessageCommitter` uses one Room transaction to insert/upsert the decrypted message into the local message cache and change the matching inbound receipt from PENDING to PROCESSED. Both operations succeed or roll back together. It validates receipt status, message ID, sender ID, and rejects pending-send rows. Instrumentation tests cover success, identity mismatch, and duplicate commit. This class is not wired to the receiver or UI. Do not expose plaintext to the live UI or existing message listeners during this isolated prototype phase.
4. **Native finalize (implemented, isolated):** `nativeFinalizeInboundDecryption` removes the native pending record and persists the crypto state. It is idempotent when the record is already absent. `OlmInboundHandoffCoordinator` checks Room first; when the receipt is already PROCESSED it calls finalize without rebuilding or inserting the message again. A message factory must map trusted protocol metadata and must preserve the exact staged plaintext.
5. **Remote acknowledgement:** Delete the Firebase inbox item only after the local durable handoff is complete. A failed acknowledgement remains retryable and must not cause another decrypt.

## Required validation

- Process death after native prepare but before Room commit recovers the exact same plaintext without a second ratchet advance.
- Process death during Room transaction leaves either both the message and PROCESSED receipt committed, or neither.
- Process death after Room commit but before native finalize does not duplicate the message and safely finalizes the staged plaintext.
- Process death after local commit but before Firebase deletion safely retries only the remote acknowledgement.
- Initial pre-key delivery recovers after one-time-key consumption and process recreation.
- Same receipt ID with a different ciphertext digest is rejected.
- Pending plaintext exists only inside the Android Keystore-encrypted native state and the app's protected local Room database; it is never written to Firebase, logs, analytics, or notifications.

## Scope restrictions

This is an isolated E2EE prototype requirement. Do not connect it to existing plaintext chat listeners, message sending, notifications, previews, background workers, or UI. Keep Firebase Spark and the existing free-only infrastructure constraint. Do not add paid services or card-required storage.

## Coordinator boundary

`OlmInboundHandoffCoordinator` accepts a verified remote device bundle, the expected ciphertext kind, and a message factory. It validates the stored envelope's sender/recipient against the durable receipt, stages or recovers plaintext through the native repository, checks that the resulting cached message has the receipt's sender/message IDs and exact plaintext, commits message plus receipt atomically, then finalizes native staging.

The message factory is deliberately caller-supplied: the current envelope does not define an authenticated message-metadata schema for nickname, timestamp, replies, reactions, or attachments. The coordinator does not invent those values. It remains isolated and must not be connected to live chat until the encrypted payload schema and metadata validation are specified and tested.


Coordinator instrumentation coverage now exercises both PRE_KEY and NORMAL inbound ciphertext. It also verifies that a message-mapping failure leaves the receipt PENDING and staged plaintext recoverable, a retry commits exactly once, duplicate processing does not rebuild the message, and a process-recreation simulation finalizes already-committed native staging.
