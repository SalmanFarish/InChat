# InChat Cloudflare Worker

This Worker is the free-plan server layer for InChat. It handles private chat
attachments in Firebase Realtime Database, direct and group push notifications,
group inbox previews, and server-managed FCM topic membership.

## Services used

- Firebase Authentication verifies app users.
- Firebase Realtime Database remains the chat and user-data database.
- Firebase Cloud Messaging delivers push notifications.
- Cloudflare Workers runs the trusted API.
- Firebase Realtime Database stores chat data and attachment bytes.

InChat does not deploy Firebase Cloud Functions or use Firebase Cloud Storage.
The Android app never contains a Firebase service-account key or FCM server
credential.

## Worker setup

1. No R2 bucket is required. Attachment bytes are stored in the existing Firebase Realtime Database.
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

4. Configure the service-account values as Worker variables/secrets. This
   Worker accepts either a single `FIREBASE_SERVICE_ACCOUNT_JSON` secret or
   the split variables used by the current deployment:

   - Variable: `FIREBASE_CLIENT_EMAIL`
   - Secret: `FIREBASE_PRIVATE_KEY` (the PKCS#8 PEM private key, with normal
     PEM newlines or escaped `\\n` newlines)
   - Variable: `FIREBASE_PROJECT_ID`

   Keep the private key secret; never put it in Android or commit it.

5. Create a Google Cloud service account in Firebase project
   `inchat-24b8b`. Grant it these roles:

   - Firebase Realtime Database Admin
     (`roles/firebasedatabase.admin`) for server-side database reads/writes.
   - Firebase Cloud Messaging API Admin
     (`roles/firebasecloudmessaging.admin`) for sending messages and managing
     topic subscriptions.
   - Firebase Authentication Admin (`roles/firebaseauth.admin`) for the
     optional recovery-code endpoint to update account passwords.

   Enable the **Firebase Cloud Messaging API** for the project. Do not enable
   Cloud Build or Artifact Registry for this Worker.

6. Create a private secret for the opaque FCM topic names. In PowerShell,
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

7. (Optional alternative to the split values in step 4.) Create a JSON key
   for that service account and save it outside the InChat project (for
   example, in your Downloads folder). From PowerShell, pipe the file directly
   to Wrangler:

   ```powershell
   Get-Content -Raw "$HOME\Downloads\inchat-service-account.json" |
     npx wrangler secret put FIREBASE_SERVICE_ACCOUNT_JSON
   ```

   Change the example path to the file you saved. Do not commit this JSON
   file, paste it into chat, or put it in the Android project.

8. Deploy the Worker:

   ```sh
   npm run deploy
   ```

9. The Android project already has the deployed Worker URL configured in
   `CloudflareWorkerConfig.BASE_URL`. Keep the URL only, with no `/v1`
   suffix. Both notifications and attachment requests use this shared setting.

10. Password recovery is available at `POST /recover` (also
    `POST /v1/recover`). It uses the same Firebase service-account secret.
    The service account therefore also needs Firebase Authentication Admin
    (`roles/firebaseauth.admin`) permission to update account passwords.
    The Wrangler config applies separate approximate per-minute limits by
    source IP and normalized username. Cloudflare's rate limits are regional
    and eventually consistent, not strict global lockouts.

## Group membership security

- Group creation and member-management actions use
  `POST /v1/groups/manage-members`.
- The Worker checks the authenticated user, admin/member permissions, target
  accounts, and the 50-member limit before changing the group.
- Member updates use Firebase Realtime Database ETags so concurrent add
  requests re-check the current member list rather than exceeding the limit.
- Client-side writes to `chats/{groupId}/members` and direct client group
  creation are denied by `database.rules.json`. Deploy the Worker before
  deploying the updated database rules and Android app.

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
- Direct-chat unread increments are compared with the recipient's private
  `chatReadState/{uid}/{chatId}` timestamp. The cursor is not exposed as a
  read receipt, so unread badges remain accurate even when read receipts are
  disabled. An ETag-guarded repair handles a read arriving during message
  processing.
- Group message edits and deletions call `POST /v1/events/preview` to rebuild
  the latest preview without changing unread counts. Direct-chat preview
  updates are maintained by the Worker when messages are sent.

The app does not choose an FCM recipient or subscribe itself to private group
topics. The Worker checks membership before changing topic subscriptions.

## Attachments

- Attachment bytes are stored as Base64 under
  `attachments/{chatId}/{messageId}` in Firebase Realtime Database.
- Voice attachments are limited to 1 MiB; view-once photos are limited to 5 MiB.
- View-once photos are consumed with a Firebase ETag-conditional delete so
  concurrent downloads cannot both successfully consume the same record.
- Only the message sender can upload or delete its remote attachment.
- A Firebase ID token and group/chat membership are required for attachment
  access.
- Base64 increases stored/downloaded size by roughly one third. The Spark
  Realtime Database quota is shared with chat data: 1 GB stored and 10 GB of
  downloads per month. Monitor usage and clean up old attachments.

## Free-plan notes

The Firebase project can remain on Spark because this architecture does not
deploy Firebase Cloud Functions or use Firebase Cloud Storage. Attachments
share the Spark Realtime Database allowance (1 GB stored and 10 GB downloaded
per month). Cloudflare Workers and rate limits remain subject to their own
free-plan quotas and limits. No R2 bucket or payment method is required.

Do not run `firebase deploy --only functions`. Deploy Firebase Realtime
Database rules separately when they change:

```sh
firebase deploy --only database
```
