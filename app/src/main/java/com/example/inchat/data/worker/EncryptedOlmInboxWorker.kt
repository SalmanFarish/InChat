package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.inchat.crypto.DecryptedOlmMessage
import com.example.inchat.crypto.DefaultOlmInboundGroupMessageHandoff
import com.example.inchat.crypto.DefaultOlmInboundGroupSessionKeyHandoff
import com.example.inchat.crypto.EncryptedGroupMessageRepository
import com.example.inchat.crypto.MegolmSessionRepository
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.DeviceKeyDirectoryRepository
import com.example.inchat.crypto.OlmCommittedReceiptFinalizer
import com.example.inchat.crypto.OlmInboundHandoffCoordinator
import com.example.inchat.crypto.OlmInboundReceiptHandoff
import com.example.inchat.crypto.OlmPendingReceiptProcessor
import com.example.inchat.crypto.OlmSessionRepository
import com.example.inchat.crypto.FirebaseOlmEnvelopeTransport
import com.example.inchat.crypto.CloudflareOlmEnvelopeTransport
import com.example.inchat.crypto.PartitionedOlmEnvelopeTransport
import com.example.inchat.crypto.OlmInboxTransport
import com.example.inchat.crypto.FirebaseOlmInboxReceiver
import com.example.inchat.crypto.OlmInboundDeliveryLedger
import com.example.inchat.crypto.OlmRelayOrigin
import com.example.inchat.crypto.TrustedDeviceRepository
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.repository.GroupChatRepository
import com.example.inchat.data.repository.CloudflareWorkerConfig
import com.example.inchat.notification.EncryptedMessageNotificationPublisher
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Polls this installation's ciphertext-only Olm inbox, durably stages deliveries,
 * then advances only automatically pinned, unchanged device identities through authenticated Olm decryption
 * and atomic local message/receipt persistence.
 *
 * The decrypted text is encrypted with the device Keystore key before its
 * record is committed to encrypted_olm_messages. It never enters cached_messages,
 * which feeds the existing chat UI, or the plaintext listeners and notifications.
 */
class EncryptedOlmInboxWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID).orEmpty()
        val recipientDeviceId = inputData.getString(KEY_RECIPIENT_DEVICE_ID).orEmpty()
        if (!ownerUserId.matches(USER_ID_PATTERN) || !recipientDeviceId.matches(DEVICE_ID_PATTERN)) {
            Log.e(TAG, "Encrypted inbox work is missing valid account/device identifiers.")
            return Result.failure()
        }

        when (evaluateEncryptedOlmAuthScope(
            scheduledOwnerUserId = ownerUserId,
            authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid,
        )) {
            EncryptedOlmAuthScopeDecision.WAIT_FOR_AUTH -> return Result.success()
            EncryptedOlmAuthScopeDecision.RETIRE_STALE_SCOPE -> {
                Log.i(TAG, "Retiring encrypted inbox polling after the signed-in account changed.")
                cancel(applicationContext, ownerUserId, recipientDeviceId)
                return Result.success()
            }
            EncryptedOlmAuthScopeDecision.CHECK_DEVICE -> Unit
        }

        val identityLookup: kotlin.Result<String?> = try {
            kotlin.Result.success(
                DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            kotlin.Result.failure(error)
        }

        val deviceScopeDecision = evaluateEncryptedOlmDeviceScope(
            scheduledSenderDeviceId = recipientDeviceId,
            activeDeviceId = identityLookup.getOrNull(),
            lookupFailed = identityLookup.isFailure,
        )
        when (encryptedOlmDeviceScopeAction(deviceScopeDecision)) {
            EncryptedOlmDeviceScopeAction.RETRY -> {
                // Keep the one-time inbox wake alive when local storage/Keystore
                // access fails transiently; otherwise this message waits for polling.
                Log.w(
                    TAG,
                    "Encrypted inbox is waiting for a temporary local device-identity lookup failure.",
                    identityLookup.exceptionOrNull(),
                )
                return Result.retry()
            }
            EncryptedOlmDeviceScopeAction.RETIRE -> {
                Log.i(TAG, "Retiring encrypted inbox polling for a missing or inactive device identity.")
                cancel(applicationContext, ownerUserId, recipientDeviceId)
                return Result.success()
            }
            EncryptedOlmDeviceScopeAction.CONTINUE -> Unit
        }

        // A stale worker must never redirect itself to a newly signed-in account.
        if (FirebaseAuth.getInstance().currentUser?.uid != ownerUserId) {
            return Result.success()
        }

        return try {
            val database = LocalChatDatabase.getInstance(applicationContext)
            val trustRepository = TrustedDeviceRepository(applicationContext)
            val firebaseTransport = FirebaseOlmEnvelopeTransport(
                trustVerifier = trustRepository,
                expectedAuthenticatedUserId = ownerUserId,
            )
            val cloudflareTransport = CloudflareOlmEnvelopeTransport(
                trustVerifier = trustRepository,
                expectedAuthenticatedUserId = ownerUserId,
            )
            val inboxTransport: OlmInboxTransport = PartitionedOlmEnvelopeTransport(
                firebase = firebaseTransport,
                cloudflare = cloudflareTransport,
                cloudflareDirectEnabled = CloudflareWorkerConfig.isDirectOlmMailboxEnabled,
                cloudflareGroupEnabled = CloudflareWorkerConfig.isGroupOlmMailboxEnabled,
            )
            val receiver = FirebaseOlmInboxReceiver(
                transport = inboxTransport,
                ledger = OlmInboundDeliveryLedger(
                    dao = database.olmInboundReceiptDao(),
                    recipientUserId = ownerUserId,
                    localDeviceId = recipientDeviceId,
                ),
                deviceId = recipientDeviceId,
                onAcknowledgementStored = { item ->
                    // Queue sender notification separately so an HTTP round trip
                    // never delays this device's inbox decryption and Room commit.
                    // Cloudflare-mailbox acknowledgements use a different relay path.
                    if (item.relayOrigin != OlmRelayOrigin.CLOUDFLARE) {
                        EncryptedOlmAcknowledgementWakeWorker.schedule(
                            context = applicationContext,
                            acknowledgedByUserId = ownerUserId,
                            acknowledgingDeviceId = recipientDeviceId,
                            senderUserId = item.senderUid,
                            senderDeviceId = item.senderDeviceId,
                            deliveryId = item.deliveryId,
                            messageId = item.messageId,
                        )
                    }
                },
            )
            val result = receiver.receivePending()
            // Inbox polling can suspend while Firebase auth changes. Do not build
            // a receipt processor under a different account than this worker owns.
            if (FirebaseAuth.getInstance().currentUser?.uid != ownerUserId) {
                return Result.success()
            }

            // Decryption remains isolated from plaintext Firebase listeners and UI.
            // Only automatically pinned, unchanged, signature-verified bundles reach the handoff.
            val olmSessionRepository = OlmSessionRepository(applicationContext)
            val megolmSessionRepository = MegolmSessionRepository(applicationContext) {
                FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            }
            val groupRepository = GroupChatRepository()
            val encryptedNotifications = EncryptedMessageNotificationPublisher(applicationContext)
            val groupMessageHandoff = DefaultOlmInboundGroupMessageHandoff(
                membershipForGroup = groupRepository::getGroupCryptoMembershipSnapshot,
                sessionRepository = megolmSessionRepository,
                messageRepository = EncryptedGroupMessageRepository(
                    dao = database.encryptedGroupMessageDao(),
                ),
                authenticatedLocalUserId = {
                    FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                },
                trustVerifier = trustRepository,
                onMessageCommitted = { message ->
                    encryptedNotifications.notifyGroupMessage(
                        groupId = message.groupId,
                        senderUserId = message.senderUserId,
                    )
                },
            )
            val groupSessionKeyHandoff = DefaultOlmInboundGroupSessionKeyHandoff(
                membershipForGroup = groupRepository::getGroupCryptoMembershipSnapshot,
                keyImporter = megolmSessionRepository,
                authenticatedLocalUserId = {
                    FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                },
                trustVerifier = trustRepository,
            )
            val handoffCoordinator = OlmInboundHandoffCoordinator(
                database = database,
                receiptDao = database.olmInboundReceiptDao(),
                sessionRepository = olmSessionRepository,
                authenticatedLocalUserId = {
                    FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                },
                trustVerifier = trustRepository,
                encryptedMessageDao = database.encryptedOlmMessageDao(),
                groupMessageHandoff = groupMessageHandoff,
                groupSessionKeyHandoff = groupSessionKeyHandoff,
                megolmSessionRepository = megolmSessionRepository,
                onDirectMessageCommitted = { message ->
                    encryptedNotifications.notifyDirectMessage(
                        conversationId = message.conversationId,
                        senderUserId = message.senderUserId,
                    )
                },
            )
            val deviceDirectory = DeviceKeyDirectoryRepository()
            val processor = OlmPendingReceiptProcessor(
                receiptDao = database.olmInboundReceiptDao(),
                remoteDevicesForUser = deviceDirectory::getUserDevices,
                trustVerifier = trustRepository,
                authenticatedLocalUserId = {
                    FirebaseAuth.getInstance().currentUser?.uid
                },
                expectedLocalUserId = ownerUserId,
                handoff = OlmInboundReceiptHandoff { receiptId, remoteDevice, senderUid, kind, localUid ->
                    handoffCoordinator.process(
                        receiptId = receiptId,
                        remoteDevice = remoteDevice,
                        remoteDeviceUserId = senderUid,
                        ciphertextKind = kind,
                        localUserId = localUid,
                    ) { payload ->
                        DecryptedOlmMessage(
                            ownerUserId = payload.recipientUserId,
                            conversationId = payload.conversationId,
                            messageId = payload.messageId,
                            conversationPeerUserId = payload.conversationPeerUserId,
                            senderUserId = payload.senderUserId,
                            senderDeviceId = payload.senderDeviceId,
                            recipientUserId = payload.recipientUserId,
                            recipientDeviceId = payload.recipientDeviceId,
                            sentAtMillis = payload.sentAtMillis,
                            text = payload.text,
                        )
                    }
                },
                finalizer = OlmCommittedReceiptFinalizer { receiptId, localUid ->
                    handoffCoordinator.finalizePreviouslyCommitted(receiptId, localUid)
                },
            )
            val handoffResult = processor.processPending()
            Log.i(
                TAG,
                "Encrypted inbox pass completed: stored=" + result.stored +
                    ", pendingRetries=" + result.alreadyPending +
                    ", processedDuplicates=" + result.alreadyProcessed +
                    ", conflicts=" + result.conflicts +
                    ", rejectedDuplicates=" + result.rejected,
            )
            Log.i(
                TAG,
                "Encrypted inbound handoff completed: pending=" + handoffResult.pendingScanned +
                    ", deferred=" + handoffResult.deferredScanned +
                    ", committed=" + handoffResult.committed +
                    ", alreadyCommitted=" + handoffResult.alreadyCommitted +
                    ", finalized=" + handoffResult.finalized +
                    ", waitingForTrust=" + handoffResult.waitingForTrust +
                    ", missingDevices=" + handoffResult.missingRemoteDevice +
                    ", rejected=" + handoffResult.rejected +
                    ", failures=" + handoffResult.failed,
            )
            // Each pass is bounded to 50 receipts. If untouched rows remain in
            // Room, enqueue a follow-up now instead of waiting for another push or
            // the 15-minute fallback poll. Attempt timestamps stop failed/deferred
            // rows from creating an endless continuation loop.
            if (database.olmInboundReceiptDao().hasUnattemptedReceipts(ownerUserId)) {
                EncryptedOlmInboxWorker.wake(
                    context = applicationContext,
                    ownerUserId = ownerUserId,
                    recipientDeviceId = recipientDeviceId,
                )
                Log.i(TAG, "Queued another encrypted inbox pass for untouched durable receipts.")
            }
            // Native/Room handoff failures retain the existing bounded retry policy.
            if (handoffResult.failed > 0) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted inbox polling will be retried.", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EncryptedOlmInbox"
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_RECIPIENT_DEVICE_ID = "recipient_device_id"
        private const val PERIODIC_INTERVAL_MINUTES = 15L
        private const val WORK_TAG = "encrypted_olm_inbox"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /**
         * Enqueue an immediate account/device-scoped inbox pass. Foreground RTDB
         * events and FCM both use this same durable path, preserving the Room
         * receipt ledger, Olm authentication, and acknowledgement ordering.
         */
        fun wake(context: Context, ownerUserId: String, recipientDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(recipientDeviceId.matches(DEVICE_ID_PATTERN)) { "Recipient device ID has an invalid format" }

            val request = OneTimeWorkRequestBuilder<EncryptedOlmInboxWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_RECIPIENT_DEVICE_ID to recipientDeviceId,
                    ),
                )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                immediateWorkName(ownerUserId, recipientDeviceId),
                // Preserve a wake-up that arrives after the active worker's inbox snapshot.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        /** Schedule the startup poll and the durable, network-constrained recovery poll. */
        fun schedule(context: Context, ownerUserId: String, recipientDeviceId: String) {
            EncryptedOlmForegroundInboxMonitor.setDeviceScope(
                context = context,
                accountUserId = ownerUserId,
                localDeviceId = recipientDeviceId,
            )
            wake(context, ownerUserId, recipientDeviceId)
            val periodic = PeriodicWorkRequestBuilder<EncryptedOlmInboxWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_RECIPIENT_DEVICE_ID to recipientDeviceId,
                    ),
                )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                periodicWorkName(ownerUserId, recipientDeviceId),
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }

        /** Cancel this account/device inbox schedule without deleting staged receipts. */
        fun cancel(context: Context, ownerUserId: String, recipientDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(recipientDeviceId.matches(DEVICE_ID_PATTERN)) { "Recipient device ID has an invalid format" }
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(immediateWorkName(ownerUserId, recipientDeviceId))
            workManager.cancelUniqueWork(periodicWorkName(ownerUserId, recipientDeviceId))
        }

        /** Cancel only encrypted inbox jobs when the Firebase auth scope changes. */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
        }

        private fun immediateWorkName(ownerUserId: String, recipientDeviceId: String) =
            "encrypted-olm-inbox-now-$ownerUserId-$recipientDeviceId"

        private fun periodicWorkName(ownerUserId: String, recipientDeviceId: String) =
            "encrypted-olm-inbox-periodic-$ownerUserId-$recipientDeviceId"
    }
}
