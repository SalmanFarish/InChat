# InChat Attachments Worker

This Worker is the remote temporary attachment layer for InChat voice messages and small files.

## Storage model

- Firebase Realtime Database stores only attachment metadata.
- Cloudflare R2 stores the binary object.
- Android keeps the downloaded file in the app's local attachment directory.
- After a successful recipient download, Android can request DELETE for the remote object.
- No R2 access key is shipped inside the Android APK.

## Limits

- Voice: 1 MiB maximum per recording.
- Other files: 5 MiB maximum.
- R2 object key: `attachments/{chatId}/{messageId}`.

## Cloudflare setup

1. Create an R2 bucket named `inchat-attachments`.
2. From this directory install dependencies:
   `npm install`
3. Set the Firebase Web API key as a Worker secret:
   `npx wrangler secret put FIREBASE_WEB_API_KEY`
4. Deploy:
   `npm run deploy`
5. Copy the deployed Worker URL into
   `CloudflareAttachmentRepository.BASE_URL` in the Android project, keeping the
   `/v1/attachments` suffix.

The Worker authenticates the Firebase ID token, verifies chat membership through
Realtime Database security rules, and verifies that the uploader owns the
message before accepting an object.

Do not put an R2 access key or secret in the Android app.

## Why R2

Firebase Cloud Storage now requires the Firebase Blaze plan. InChat is staying
on Spark, so Firebase Storage is not used for attachments.
