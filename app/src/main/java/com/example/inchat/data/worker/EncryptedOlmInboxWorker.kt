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
import com.example.inchat.crypto.FirebaseOlmInboxReceiver
import com.example.inchat.crypto.OlmInboundDeliveryLedger
import com.example.inchat.crypto.TrustedDeviceRepository
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.repository.GroupChatRepository
import com.example.inchat.notification.EncryptedMessageNotificationPublisher
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Polls this installation's ciphertext-only Olm inbox, durably stages deliveries,
 * then advances only SAS-trusted receipts through authenticated Olm decryption
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

        when (evaluateEncryptedOlmDeviceScope(
            scheduledSenderDeviceId = recipientDeviceId,
            activeDeviceId = identityLookup.getOrNull(),
            lookupFailed = identityLookup.isFailure,
        )) {
            EncryptedOlmDeviceScopeDecision.DEFER_LOOKUP -> {
                Log.w(TAG, "Encrypted inbox polling is paused because the local device identity lookup failed.",
                    identityLookup.exceptionOrNull())
                return Result.success()
            }
            EncryptedOlmDeviceScopeDecision.RETIRE_STALE_SCOPE -> {
                Log.i(TAG, "Retiring encrypted inbox polling for a missing or inactive device identity.")
                cancel(applicationContext, ownerUserId, recipientDeviceId)
                return Result.success()
            }
            EncryptedOlmDeviceScopeDecision.KEEP_ACTIVE -> Unit
        }

        // A stale worker must never redirect itself to a newly signed-in account.
        if (FirebaseAuth.getInstance().currentUser?.uid != ownerUserId) {
            return Result.success()
        }

        return try {
            val database = LocalChatDatabase.getInstance(applicationContext)
            val trustRepository = TrustedDeviceRepository(applicationContext)
            val receiver = FirebaseOlmInboxReceiver(
                transport = FirebaseOlmEnvelopeTransport(
                    trustVerifier = trustRepository,
                    expectedAuthenticatedUserId = ownerUserId,
                ),
                ledger = OlmInboundDeliveryLedger(
                    dao = database.olmInboundReceiptDao(),
                    recipientUserId = ownerUserId,
                    localDeviceId = recipientDeviceId,
                ),
                deviceId = recipientDeviceId,
            )
            val result = receiver.receivePending()
            // Inbox polling can suspend while Firebase auth changes. Do not build
            // a receipt processor under a different account than this worker owns.
            if (FirebaseAuth.getInstance().currentUser?.uid != ownerUserId) {
                return Result.success()
            }

            // Decryption remains isolated from plaintext Firebase listeners and UI.
            // Only SAS-trusted, signature-verified device bundles reach the handoff.
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
            // Retry promptly when native/Room handoff failed. Trust-deferred and
            // missing-device receipts remain durable and are revisited by the next
            // wake-up or periodic pass without keeping this work request in retry.
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

        /** Schedule an immediate inbox poll and a network-constrained recovery poll. */
        fun schedule(context: Context, ownerUserId: String, recipientDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(recipientDeviceId.matches(DEVICE_ID_PATTERN)) { "Recipient device ID has an invalid format" }

            val appContext = context.applicationContext
            val data = workDataOf(
                KEY_OWNER_USER_ID to ownerUserId,
                KEY_RECIPIENT_DEVICE_ID to recipientDeviceId,
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediate = OneTimeWorkRequestBuilder<EncryptedOlmInboxWorker>()
                .setInputData(data)
                .addTag(WORK_TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            val periodic = PeriodicWorkRequestBuilder<EncryptedOlmInboxWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setInputData(data)
                .addTag(WORK_TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()

            val workManager = WorkManager.getInstance(appContext)
            workManager.enqueueUniqueWork(
                immediateWorkName(ownerUserId, recipientDeviceId),
                ExistingWorkPolicy.KEEP,
                immediate,
            )
            workManager.enqueueUniquePeriodicWork(
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
