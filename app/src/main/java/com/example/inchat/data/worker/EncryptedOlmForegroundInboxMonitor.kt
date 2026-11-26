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
 * Owns exactly one foreground listener for the authenticated account/device.
 * RTDB callbacks only wake the existing durable worker; ciphertext handling,
 * deduplication, decryption, and acknowledgements stay in the worker pipeline.
 */
internal object EncryptedOlmForegroundInboxMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var ownerUserId: String? = null
    private var deviceId: String? = null
    private var foreground = false
    private var reference: DatabaseReference? = null
    private var listener: ChildEventListener? = null
    private var wakeJob: Job? = null

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
            }
        }
    }

    /** Called whenever startup or first-use provisioning makes a device usable. */
    fun setDeviceScope(context: Context, accountUserId: String, localDeviceId: String) {
        scope.launch {
            appContext = context.applicationContext
            if (ownerUserId != accountUserId) {
                ownerUserId = accountUserId
                deviceId = null
                detach()
            }
            deviceId = localDeviceId
            attachIfReady()
        }
    }

    private fun attachIfReady() {
        if (!foreground || reference != null) return
        val context = appContext ?: return
        val uid = ownerUserId ?: return
        val localDeviceId = deviceId ?: return
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) return

        val inboxReference = FirebaseDatabase.getInstance().reference
            .child("olmInbox")
            .child(uid)
            .child(localDeviceId)
        val inboxListener = object : ChildEventListener {
            private fun wakeInbox() {
                if (!foreground ||
                    ownerUserId != uid ||
                    deviceId != localDeviceId ||
                    FirebaseAuth.getInstance().currentUser?.uid != uid
                ) return

                // Firebase replays existing children individually on attach.
                // Coalesce that initial burst and rapid sends into one inbox pass.
                if (wakeJob?.isActive == true) return
                wakeJob = scope.launch {
                    delay(100L)
                    if (foreground &&
                        ownerUserId == uid &&
                        deviceId == localDeviceId &&
                        FirebaseAuth.getInstance().currentUser?.uid == uid
                    ) {
                        EncryptedOlmInboxWorker.wake(context, uid, localDeviceId)
                    }
                }
            }

            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) = wakeInbox()
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = wakeInbox()
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                Log.w("InChatInboxMonitor", "Foreground encrypted inbox listener was cancelled: ${error.code}")
            }
        }

        reference = inboxReference
        listener = inboxListener
        inboxReference.addChildEventListener(inboxListener)
    }

    private fun detach() {
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
}
