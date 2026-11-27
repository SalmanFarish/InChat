package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps foreground sender delivery state responsive without relying on a silent
 * normal-priority FCM ACK hint. The RTDB listener is only a wake-up signal: the
 * existing sender worker validates, reads, persists, and removes real ACKs.
 */
internal object EncryptedOlmForegroundAcknowledgementMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var ownerUserId: String? = null
    private var deviceId: String? = null
    private var foreground = false
    private var reference: DatabaseReference? = null
    private var listener: ChildEventListener? = null
    private var wakeJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectFailureCount = 0

    fun setForeground(context: Context, isForeground: Boolean) {
        scope.launch {
            appContext = context.applicationContext
            foreground = isForeground
            if (foreground) attachIfReady() else detach()
        }
    }

    fun setAccount(context: Context, accountUserId: String?) {
        scope.launch {
            appContext = context.applicationContext
            if (ownerUserId != accountUserId) {
                ownerUserId = accountUserId
                deviceId = null
                detach()
            } else if (foreground) {
                attachIfReady()
            }
        }
    }

    fun setDeviceScope(context: Context, accountUserId: String, localDeviceId: String) {
        scope.launch {
            appContext = context.applicationContext
            if (ownerUserId != accountUserId || deviceId != localDeviceId) {
                ownerUserId = accountUserId
                deviceId = localDeviceId
                detach()
            }
            attachIfReady()
        }
    }

    private fun attachIfReady() {
        if (!foreground || reference != null || reconnectJob?.isActive == true) return
        val context = appContext ?: return
        val uid = ownerUserId ?: return
        val localDeviceId = deviceId ?: return
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) return

        val ackReference = FirebaseDatabase.getInstance().reference
            .child("olmDeliveryAcks")
            .child(uid)
            .child(localDeviceId)
        lateinit var ackListener: ChildEventListener
        ackListener = object : ChildEventListener {
            private fun wakeSender() {
                if (!isForegroundAcknowledgementWakeInScope(
                        isForeground = foreground,
                        activeOwnerUserId = ownerUserId,
                        expectedOwnerUserId = uid,
                        activeDeviceId = deviceId,
                        expectedDeviceId = localDeviceId,
                        authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid,
                    )
                ) return

                reconnectFailureCount = 0
                // Firebase replays existing ACK children on attach; collapse a batch
                // into one sender pass. The durable worker reads every ACK.
                if (wakeJob?.isActive == true) return
                wakeJob = scope.launch {
                    delay(100L)
                    if (isForegroundAcknowledgementWakeInScope(
                            isForeground = foreground,
                            activeOwnerUserId = ownerUserId,
                            expectedOwnerUserId = uid,
                            activeDeviceId = deviceId,
                            expectedDeviceId = localDeviceId,
                            authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid,
                        )
                    ) {
                        EncryptedOlmDeliveryWorker.scheduleImmediate(
                            context = context,
                            ownerUserId = uid,
                            senderDeviceId = localDeviceId,
                        )
                    }
                }
            }

            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) = wakeSender()
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = wakeSender()
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Foreground encrypted ACK listener was cancelled: ${error.code}")
                scope.launch {
                    if (reference !== ackReference || listener !== ackListener) return@launch
                    ackReference.removeEventListener(ackListener)
                    reference = null
                    listener = null
                    wakeJob?.cancel()
                    wakeJob = null
                    scheduleReconnect(uid, localDeviceId)
                }
            }
        }

        reference = ackReference
        listener = ackListener
        ackReference.addChildEventListener(ackListener)
    }

    private fun scheduleReconnect(expectedOwnerUserId: String, expectedDeviceId: String) {
        if (reconnectJob?.isActive == true) return
        val delayMillis = foregroundInboxReconnectDelayMillis(++reconnectFailureCount)
        Log.i(TAG, "Reconnecting foreground encrypted ACK listener in ${delayMillis}ms.")
        reconnectJob = scope.launch {
            delay(delayMillis)
            reconnectJob = null
            if (isForegroundAcknowledgementWakeInScope(
                    isForeground = foreground,
                    activeOwnerUserId = ownerUserId,
                    expectedOwnerUserId = expectedOwnerUserId,
                    activeDeviceId = deviceId,
                    expectedDeviceId = expectedDeviceId,
                    authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid,
                )
            ) {
                attachIfReady()
            }
        }
    }

    private fun detach() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectFailureCount = 0
        wakeJob?.cancel()
        wakeJob = null
        val activeReference = reference
        val activeListener = listener
        if (activeReference != null && activeListener != null) {
            activeReference.removeEventListener(activeListener)
        }
        reference = null
        listener = null
    }

    private const val TAG = "InChatAckMonitor"
}

/** A listener may wake only the exact current foreground account/device session. */
internal fun isForegroundAcknowledgementWakeInScope(
    isForeground: Boolean,
    activeOwnerUserId: String?,
    expectedOwnerUserId: String,
    activeDeviceId: String?,
    expectedDeviceId: String,
    authenticatedUserId: String?,
): Boolean =
    isForeground &&
        activeOwnerUserId == expectedOwnerUserId &&
        activeDeviceId == expectedDeviceId &&
        authenticatedUserId == expectedOwnerUserId
