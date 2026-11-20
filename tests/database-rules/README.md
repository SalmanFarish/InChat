# InChat Realtime Database Rules Tests

These tests run only against the Firebase Local Emulator Suite. They use the
non-production project ID `demo-inchat-rules` and never connect to the live
InChat database.

## Requirements

- Node.js 24.12 or later
- Firebase CLI (`firebase-tools`)
- Java 21 or later for the current Firebase CLI and emulator runtime

## Run

From this folder:

```powershell
npm install
npm test
```

The `npm test` script starts only the Realtime Database Emulator, runs the rules tests,
and stops the emulator when the test process exits. The test suite uses
`authenticatedContext()` from `@firebase/rules-unit-testing`; it does not start or
contact the Firebase Auth emulator, create real Firebase accounts, deploy rules, or
connect to the production InChat Firebase project.

To exercise the production Olm Firebase Realtime Database adapter through the
Android SDK **without starting Firebase Auth**, use the separate demo-project
configuration. It loads a test-only rules file that grants access solely below
`olmInbox` and `olmDeliveryAcks`; it is not referenced by the normal
`firebase.json` and must never be deployed:

```powershell
cd tests/database-rules
npm run olm-relay-emulator
```

In another terminal at the repository root, run:

```powershell
.\gradlew :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.example.inchat.crypto.FirebaseOlmRelayStoreEmulatorTest" `
  "-Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorHost=10.0.2.2" `
  --console=plain
```

The test injects synthetic account/device IDs into the isolated transport and
does not initialize FirebaseAuth, create Firebase accounts, load the installed
InChat identity, or use the default Firebase project. It verifies that the real
Android Realtime Database SDK can upload an opaque envelope while offline,
complete the write after reconnecting, read the envelope from the recipient
device path, resolve the server acknowledgement timestamp, and remove the
inbox/ack records. The separate Node rules suite remains responsible for
authorization and immutable-retry policy.

For a physical Android device connected over USB, run
`adb reverse tcp:9000 tcp:9000` and pass
`-Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorHost=127.0.0.1`.

To run the Android SAS transport integration test against both local emulators,
keep the emulators running in a separate terminal:

```powershell
npm run emulators
```

Then, from the repository root in another terminal, run the focused test:

```powershell
.\gradlew :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.inchat.crypto.FirebaseSasVerificationTransportEmulatorTest `
  -Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorHost=10.0.2.2
```

`10.0.2.2` is the host address from an Android Studio emulator. For a physical
device connected over USB, first run `adb reverse tcp:9000 tcp:9000` and
`adb reverse tcp:9099 tcp:9099`, then pass
`-Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorHost=127.0.0.1`.
The test is skipped during ordinary instrumentation runs unless this host
argument is supplied. The test creates two anonymous accounts in the local Auth
emulator and uses only the `demo-inchat-rules` project; it does not access the
production InChat Firebase project.

## Coverage

The current suite checks:

- users cannot write another user's direct-chat preview;
- users can update their own direct-chat inbox entry;
- clients cannot create groups or mutate group membership directly;
- message sender IDs cannot be spoofed;
- recipients cannot delete another user's message;
- senders can delete their own messages;
- profile-photo payload and privacy preference validation;
- direct-chat and group read permissions;
- atomic one-time prekey claims, including concurrent claim attempts, repeat claims, and unauthenticated access;
- prekey-claim cleanup only by the owning account after its device directory entry has been revoked.
- the complete encrypted `olmInbox` / `olmDeliveryAcks` sender-recipient lifecycle, including a same-ID exact resend after the sender consumes an acknowledgement.

Add a test whenever a security rule or Worker-owned data path changes.
