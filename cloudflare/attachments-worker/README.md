# InChat Cloudflare Worker

This Worker is the free-plan server layer for InChat. It handles private R2
attachments, direct and group push notifications, group inbox previews, and
server-managed FCM topic membership.

## Services used

- Firebase Authentication verifies app users.
- Firebase Realtime Database remains the chat and user-data database.
- Firebase Cloud Messaging delivers push notifications.
- Cloudflare Workers runs the trusted API.
- Cloudflare R2 stores attachment bytes.

InChat does not deploy Firebase Cloud Functions or use Firebase Cloud Storage.
The Android app never contains a Firebase service-account key or FCM server
credential.

## Worker setup

1. Create an R2 bucket named `inchat-attachments`.
2. Install dependencies from this directory:

   ```sh
   npm install
   npx wrangler login
   ```

3. Set the Firebase Web API key as a Worker secret. It is present in the
   Android `google-services.json`, but the Worker reads it from its secret
   store:

   ```sh
   npx wrangler secret put FIREBASE_WEB_API_KEY
   ```

4. Create a Google Cloud service account in Firebase project
   `inchat-24b8b`. Grant it these roles:

   - Firebase Realtime Database Admin
     (`roles/firebasedatabase.admin`) for server-side database reads/writes.
   - Firebase Cloud Messaging API Admin
     (`roles/firebasecloudmessaging.admin`) for sending messages and managing
     topic subscriptions.

   Enable the **Firebase Cloud Messaging API** for the project. Do not enable
   Cloud Build or Artifact Registry for this Worker.

5. Create a private secret for the opaque FCM topic names. In PowerShell,
   generate 32 random bytes:

   ```powershell
   $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   $bytes = New-Object byte[] 32
   $random.GetBytes($bytes)
   $topicSecret = [Convert]::ToBase64String($bytes)
   $topicSecret
   ```

   Copy the generated value, then run:

   ```sh
   npx wrangler secret put FCM_TOPIC_SECRET
   ```

   Paste the value at Wrangler's prompt. Keep this secret stable. It prevents
   a person who only knows a group ID from guessing the group's FCM topic.

6. Create a JSON key for that service account. From this directory, run:

   ```sh
   npx wrangler secret put FIREBASE_SERVICE_ACCOUNT_JSON
   ```

   Paste the complete contents of the service-account JSON file when Wrangler
   prompts for the value. Do not commit this JSON file, paste it into chat, or
   put it in the Android project.

7. Deploy the Worker:

   ```sh
   npm run deploy
   ```

8. Copy the deployed `workers.dev` URL into
   `CloudflareWorkerConfig.BASE_URL` in the Android project. Use the URL only,
   with no `/v1` suffix. Both notifications and attachment requests use this
   shared setting.

## Notification flow

- Android writes a message to Realtime Database first.
- After the database confirms the write, Android calls
  `POST /v1/events/message` with the chat and message IDs.
- The Worker verifies the Firebase ID token and the persisted message.
- Direct messages are sent to the recipient's saved FCM token. Blocked
  conversations are not notified.
- Group messages are sent to an opaque, secret-derived FCM topic. The Worker
  manages topic membership from verified group membership and excludes the
  sender from their own group notifications. The topic name is never returned
  to the Android app.
- Group inbox previews and unread counts are updated by the Worker. Group
  activity entries update previews but do not send push notifications.
- Message edits and deletions call `POST /v1/events/preview` to rebuild the
  latest preview without changing unread counts.

The app does not choose an FCM recipient or subscribe itself to private group
topics. The Worker checks membership before changing topic subscriptions.

## Attachments

- Firebase Realtime Database stores attachment metadata only.
- Cloudflare R2 stores the binary object.
- Voice attachments are limited to 1 MiB.
- View-once photos are limited to 5 MiB and are deleted after a recipient
  downloads them.
- Only the message sender can upload or delete its remote attachment.
- A Firebase ID token and group/chat membership are required for attachment
  access.

## Free-plan notes

The Firebase project can remain on Spark because this architecture does not
deploy Firebase Cloud Functions or use Firebase Cloud Storage. Cloudflare
Workers and R2 remain subject to their own free-plan quotas and limits.

Do not run `firebase deploy --only functions`. Deploy Firebase Realtime
Database rules separately when they change:

```sh
firebase deploy --only database
```
