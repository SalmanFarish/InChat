# Inbound Olm decryption recovery design

Status: required design gate; not implemented. The isolated Firebase inbox receiver must remain ciphertext-only until this protocol and its crash tests are implemented.

## Why the current sequence is unsafe

The native Olm decrypt functions persist the advanced receive ratchet to the Android Keystore-backed AtomicFile before returning plaintext to Kotlin. Room currently stores the inbound ciphertext receipt separately. If the process dies after the native state write but before Room commits the plaintext message, a retry can no longer safely decrypt the same Olm message: the receive ratchet has already advanced.

The same recovery requirement applies to the first pre-key message. Native inbound-session creation consumes the local one-time key and persists the new session before returning the initial plaintext. It must not be connected to chat persistence until the initial plaintext is recoverable too.

Marking the Room receipt processed before saving the message is not a fix. A crash between those operations would permanently suppress a message that was never stored.

## Required two-phase handoff

Use a stable inbound receipt ID, derived from sender account, sender device, message ID, and local recipient device. Pass that ID into the native inbound operation.

1. **Native prepare:** In one encrypted device-state write, advance/establish the Olm ratchet and save a pending-decryption record containing the stable receipt ID, session ID, and plaintext. For a pre-key message, save one-time-key consumption, the new session, and the pending plaintext in that same write.
2. **Recover:** If native prepare is called again with the same receipt ID, return the already-staged plaintext without decrypting the ciphertext or advancing the ratchet again. Reject a different ciphertext digest for an existing pending receipt.
3. **Room commit:** In one Room transaction, insert/upsert the decrypted message into the local message cache and change the matching inbound receipt from PENDING to PROCESSED. Both operations must succeed or roll back together. Do not expose plaintext to the live UI or existing message listeners during this isolated prototype phase.
4. **Native finalize:** After the Room transaction succeeds, remove the native pending-decryption record and persist the crypto state. If the process dies before finalize, the next retry sees the Room receipt already processed and asks native code to finalize without inserting the message again.
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