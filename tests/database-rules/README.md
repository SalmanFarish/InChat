# InChat Realtime Database Rules Tests

These tests run only against the Firebase Local Emulator Suite. They use the
non-production project ID `demo-inchat-rules` and never connect to the live
InChat database.

## Requirements

- Node.js 24.12 or later
- Firebase CLI (`firebase-tools`)
- Java 11 or later for the Realtime Database Emulator

## Run

From this folder:

```powershell
npm install
npm test
```

The test script starts the Realtime Database Emulator, runs the rules tests,
and stops the emulator when the test process exits.

## Coverage

The current suite checks:

- users cannot write another user's direct-chat preview;
- users can update their own direct-chat inbox entry;
- clients cannot create groups or mutate group membership directly;
- message sender IDs cannot be spoofed;
- recipients cannot delete another user's message;
- senders can delete their own messages;
- profile-photo payload and privacy preference validation;
- direct-chat and group read permissions.

Add a test whenever a security rule or Worker-owned data path changes.
