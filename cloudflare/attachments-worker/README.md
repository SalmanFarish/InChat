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

- Android queues the message write in Realtime Database and immediately
  schedules a unique WorkManager event containing the chat and message IDs.
  This keeps the event durable if Android is closed while an offline write is
  waiting to sync.
- WorkManager sends `POST /v1/events/message` when network access is available.
- The Worker verifies the Firebase ID token and persisted message. If the
  message has not reached the server yet, it returns a retryable response so
  WorkManager can try again instead of losing the notification event.
- Direct messages are sent to the recipient's saved FCM token. Blocked
  conversations are not notified.
- Group messages in groups of up to 20 members are sent to each member's
  registered FCM token after the Worker has synchronized the group's private
  device index. Sends are concurrency-limited and successful recipients are
  recorded individually so a retry does not resend to members already reached.
- Larger groups, and groups whose device index is still being synchronized,
  use an opaque, secret-derived FCM topic. Topics remain the fallback for larger
  fanout because Cloudflare Workers Free allows at most 50 external subrequests
  per invocation. Topic names are never returned to the Android app.
- The Worker maintains `groupNotificationDevices/{groupId}/{uid}` from
  authenticated membership and FCM-token synchronization. The index is private
  Worker data; Android clients do not read or write it. Reopening InChat after
  this Worker update lets signed-in accounts refresh their group registrations.
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

## Encrypted Olm push wake-ups

- The isolated Android Olm delivery worker calls
  `POST /v1/olm/notify` only after the exact ciphertext envelope has been
  accepted by `olmInbox/{recipientUid}/{recipientDeviceId}/{deliveryId}`.
- The Worker derives the recipient account from the existing direct-chat
  participants. The caller cannot choose an arbitrary recipient UID or FCM
  registration token. Group chats are rejected by this endpoint.
- Before sending, the Worker checks both block directions, verifies that the
  sender and recipient device IDs still have published `deviceKeys` entries,
  and requires the relay record's sender, device, message, and encrypted
  envelope metadata to match the authenticated request.
- It reads only the recipient device's private
  `privateUsers/{uid}/olmDeviceFcmTokens/{deviceId}` registration. It never
  falls back to the account-wide plaintext-notification token.
- FCM receives a **normal-priority data-only** message containing only the
  event type and deterministic delivery ID. No FCM `notification` object,
  message text, sender display name, group name, or chat ID is included.
  Android uses the validated delivery reference only to wake its existing
  encrypted inbox poller; the encrypted authenticated payload remains the
  source of the conversation route.
- An ETag-guarded `olmPushDispatch` receipt suppresses duplicate sends and
  allows failed sends to be retried. Unregistered device tokens are removed
  only when the stored token still equals the token FCM rejected, protecting a
  concurrently rotated token.
- Publishing a verified Olm device bundle also best-effort refreshes its
  `olmDeviceFcmTokens/{deviceId}` registration. This covers installations
  where login-time token registration happened before the local Olm identity
  was created; token failures do not roll back public-key publication.
- Separate per-account and per-IP Cloudflare Worker rate limits protect this
  endpoint. The normal `/v1/events/message` plaintext/group notification path
  is unchanged and does not call the encrypted endpoint.
- This endpoint is an explicit API call after relay upload; it is not a
  Firebase database trigger. It uses the existing Firebase service account
  and FCM API, not Firebase Cloud Functions, Firebase Cloud Storage, or a
  paid Cloudflare feature.

The encrypted endpoint remains isolated from the normal composer. Do not
enable encrypted sending in the live chat UI until real two-device recovery,
inbox wake-up handling, and delivery-state reconciliation have been validated.

## Attachments

- Attachment bytes are stored as Base64 under
  `attachments/{chatId}/{messageId}` in Firebase Realtime Database.
- Voice attachments are limited to 1 MiB; view-once photos are limited to 5 MiB.
- View-once photos are consumed with a Firebase ETag-conditional delete so
  concurrent downloads cannot both successfully consume the same record.
- Upload size limits are enforced while reading the request stream, not only
  from the client-supplied Content-Length header. Upload creation is also
  ETag-conditional so concurrent requests cannot replace an existing file.
- Downloads verify that the stored attachment's chat, message, sender, and
  type still match the linked message before returning bytes. Corrupt or
  inconsistent records are rejected before a view-once photo is consumed.
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
