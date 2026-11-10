# InChat test-data reset

This is an **irreversible, project-wide reset**. It deletes every Firebase Authentication account in the configured Firebase project and clears InChat's Realtime Database data. Run it only when every account and all app data in that project are disposable test data.

The script is deliberately separate from the Android app and defaults to a dry run. Nothing is deleted until you pass both `--execute` and the exact project confirmation flag.

## What it clears

- Every Firebase Authentication account, including email/password and Google/federated sign-in accounts. This deletes the Firebase account records; it does **not** delete the users' actual email or Google accounts.
- All 12 InChat Realtime Database roots: `users`, `usernames`, `privateUsers`, `publicUsernames`, `chats`, `groupMemberships`, `groupReads`, `userChats`, `presence`, `typing`, `blockedUsers`, and `reports`.
- Legacy Firebase Storage objects under `profilePhotos/` when a Storage bucket is configured through `FIREBASE_STORAGE_BUCKET` or the local `app/google-services.json`.

The authoritative username index and its public mirror are both removed, so usernames are available for the new accounts. The app does not keep a separate email-to-username index in Realtime Database; deleting the Firebase Authentication records releases those email addresses for re-registration.

**Not cleared:** Cloudflare R2 chat attachment objects. They may remain as orphaned files after their chat/message database records are removed.

The app currently rejects addresses ending in `@inchat.app` during sign-up and login. Use two normal email addresses for the new test accounts unless you separately change that restriction.

## Before you run it

1. Close InChat on all test devices and stop any test clients. This prevents them from writing new data during the reset.
2. Confirm that the project in the repository's `.firebaserc` is the test project you intend to erase. It is currently `inchat-24b8b`. The script checks the project IDs in the service-account file and, when present, `app/google-services.json`; it also checks the Realtime Database URL.
3. In Firebase Console, open **Project settings → Service accounts** and generate a private key for the same project. Save the downloaded JSON outside this repository, for example `C:\InChat-secrets\inchat-admin-key.json`. Do not commit or share this file.
4. Copy the exact Realtime Database URL from Firebase Console. The script can read it from your local `app/google-services.json` when that file contains `firebase_url`; otherwise, set `FIREBASE_DATABASE_URL` as shown below.
5. Decide whether to remove legacy Firebase Storage profile photos too. When a bucket is present in the local Google services file or `FIREBASE_STORAGE_BUCKET`, the script will delete the `profilePhotos/` prefix in that bucket. Cloudflare R2 is not touched.

## Windows PowerShell

Open PowerShell in your repository root and run:

```powershell
cd tools\firebase-reset
npm install

$env:GOOGLE_APPLICATION_CREDENTIALS = "C:\InChat-secrets\inchat-admin-key.json"

# Set this to the exact URL shown in Firebase Console.
# You can omit it only when app/google-services.json has firebase_url.
$env:FIREBASE_DATABASE_URL = "https://YOUR-REALTIME-DATABASE-URL"

# Optional override if you need to specify the legacy Firebase Storage bucket:
# $env:FIREBASE_STORAGE_BUCKET = "YOUR-PROJECT-STORAGE-BUCKET"

npm run dry-run
```

Review the output carefully. It prints the Firebase project ID, Realtime Database URL, number of Authentication accounts, and top-level record counts for every data branch. **The dry run makes no changes.**

Only after confirming that the project and counts are exactly what you intend to erase, run:

```powershell
node .\reset-test-data.mjs --execute --confirm-project=inchat-24b8b
```

The script deletes Auth accounts first and verifies none remain before clearing the database. If Auth deletion fails, it stops before touching Realtime Database data. If a database cleanup is interrupted after Auth deletion, you can run the same command again; it will see zero Auth users and continue with the database cleanup.

After it reports success, clear the app's local data or uninstall InChat on each test device, then install/open it again and register the two fresh accounts. The current registration flow sends an email-verification message.

## Safety notes

- Never put the service-account JSON in the repository, paste it into chat, or send it to anyone.
- The script uses Firebase Admin privileges and can delete all accounts in the selected project, not just accounts created on one device.
- Firebase Admin's bulk `deleteUsers()` operation does not invoke individual Authentication `onDelete` event handlers. InChat currently has no Auth `onDelete` cleanup function, so the script explicitly clears the app's database data.
