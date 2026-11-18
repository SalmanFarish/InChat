package com.example.inchat.data.repository

import android.util.Log
import com.example.inchat.data.model.Presence
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object PresenceRepository {

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

    private var connectionListener:
            ValueEventListener? = null

    private var currentUid: String? = null

    /*
     * This is the connection node owned by this app process.
     *
     * Presence is derived from the existence of one or more
     * active connection nodes instead of a single shared boolean.
     * That keeps presence correct when the same account is signed
     * in on multiple devices.
     */
    private var activeConnectionRef:
            com.google.firebase.database.DatabaseReference? = null

    private var sessionGeneration = 0L
    private var connectionAttemptGeneration = 0L
    private var connectionSetupInFlight = false

    /*
     * The /.info/connected listener below intentionally stays
     * registered for the lifetime of the signed-in session.
     * Firebase also receives a real long-lived listener from the
     * server-clock component, so the process has an active
     * realtime listener while the user is signed in.
     */

    fun observePresence(
        uid: String
    ): Flow<Presence> = callbackFlow {
        if (uid.isBlank()) {
            trySend(Presence())
            close()
            return@callbackFlow
        }

        var hasActiveConnection = false
        var lastSeen = 0L
        var onlineVisible = true
        var lastSeenVisible = true
        val isOwner = auth.currentUser?.uid == uid

        val presenceRef = database.getReference("presence").child(uid)
        val connectionsRef = presenceRef.child("connections")
        val lastSeenRef = presenceRef.child("lastSeen")

        var connectionsListener: ValueEventListener? = null
        var lastSeenListener: ValueEventListener? = null
        var onlineVisibilityListener: ValueEventListener? = null
        var lastSeenVisibilityListener: ValueEventListener? = null
        var connectionsRetryJob: Job? = null
        var lastSeenRetryJob: Job? = null
        lateinit var attachConnectionsListener: () -> Unit
        lateinit var attachLastSeenListener: () -> Unit

        fun emitPresence() {
            trySend(
                Presence(
                    online = hasActiveConnection && onlineVisible,
                    lastSeen = if (lastSeenVisible) lastSeen else 0L,
                    onlineVisible = onlineVisible,
                    lastSeenVisible = lastSeenVisible
                )
            )
        }

        fun scheduleConnectionsRetry() {
            if (connectionsRetryJob?.isActive == true) return
            connectionsRetryJob = launch {
                delay(30_000L)
                connectionsRetryJob = null
                attachConnectionsListener()
            }
        }

        fun scheduleLastSeenRetry() {
            if (lastSeenRetryJob?.isActive == true) return
            lastSeenRetryJob = launch {
                delay(30_000L)
                lastSeenRetryJob = null
                attachLastSeenListener()
            }
        }

        attachConnectionsListener = {
            if (connectionsListener == null) {
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!isOwner) onlineVisible = true
                        hasActiveConnection = snapshot.children.any {
                            it.getValue(Boolean::class.java) == true
                        }
                        emitPresence()
                    }

                    override fun onCancelled(error: DatabaseError) {
                        connectionsListener = null
                        hasActiveConnection = false
                        if (!isOwner) onlineVisible = false
                        emitPresence()
                        scheduleConnectionsRetry()
                    }
                }
                connectionsListener = listener
                connectionsRef.addValueEventListener(listener)
            }
        }

        attachLastSeenListener = {
            if (lastSeenListener == null) {
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!isOwner) lastSeenVisible = true
                        lastSeen = snapshot.getValue(Long::class.java) ?: 0L
                        emitPresence()
                    }

                    override fun onCancelled(error: DatabaseError) {
                        lastSeenListener = null
                        lastSeen = 0L
                        if (!isOwner) lastSeenVisible = false
                        emitPresence()
                        scheduleLastSeenRetry()
                    }
                }
                lastSeenListener = listener
                lastSeenRef.addValueEventListener(listener)
            }
        }

        attachConnectionsListener()
        attachLastSeenListener()

        if (isOwner) {
            onlineVisibilityListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    onlineVisible = snapshot.getValue(Boolean::class.java) ?: true
                    emitPresence()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w("PresenceRepository", "Could not observe own online visibility", error.toException())
                }
            }
            lastSeenVisibilityListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    lastSeenVisible = snapshot.getValue(Boolean::class.java) ?: true
                    emitPresence()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w("PresenceRepository", "Could not observe own last-seen visibility", error.toException())
                }
            }
            presenceRef.child("onlineVisible").addValueEventListener(onlineVisibilityListener!!)
            presenceRef.child("lastSeenVisible").addValueEventListener(lastSeenVisibilityListener!!)
        }

        awaitClose {
            connectionsRetryJob?.cancel()
            lastSeenRetryJob?.cancel()
            connectionsListener?.let { connectionsRef.removeEventListener(it) }
            lastSeenListener?.let { lastSeenRef.removeEventListener(it) }
            onlineVisibilityListener?.let { presenceRef.child("onlineVisible").removeEventListener(it) }
            lastSeenVisibilityListener?.let { presenceRef.child("lastSeenVisible").removeEventListener(it) }
        }
    }

    suspend fun updateVisibility(
        uid: String,
        onlineVisible: Boolean,
        lastSeenVisible: Boolean
    ): Result<Unit> {

        if (uid.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "UID cannot be blank"
                )
            )
        }

        val firebaseUser =
            auth.currentUser

        if (
            firebaseUser == null ||
            firebaseUser.uid != uid
        ) {
            return Result.failure(
                IllegalStateException(
                    "Authenticated user does not match presence owner"
                )
            )
        }

        return try {

            database
                .getReference("presence")
                .child(uid)
                .updateChildren(
                    mapOf(
                        "onlineVisible" to
                                onlineVisible,

                        "lastSeenVisible" to
                                lastSeenVisible
                    )
                )
                .await()

            Result.success(
                Unit
            )

        } catch (
            e: Exception
        ) {

            Result.failure(
                e
            )
        }
    }

    fun startPresence(
        uid: String
    ) {
        if (uid.isBlank()) return
        if (currentUid == uid && connectionListener != null) return

        stopPresence(markOffline = false)
        val sessionId = ++sessionGeneration
        currentUid = uid

        val connectedRef = database.getReference(".info/connected")
        val presenceRef = database.getReference("presence").child(uid)
        val connectionsRef = presenceRef.child("connections")
        val lastSeenRef = presenceRef.child("lastSeen")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (sessionGeneration != sessionId || currentUid != uid) return
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (!connected) {
                    connectionAttemptGeneration += 1L
                    connectionSetupInFlight = false
                    activeConnectionRef = null
                    return
                }
                if (activeConnectionRef != null || connectionSetupInFlight) return

                connectionSetupInFlight = true
                val attemptId = ++connectionAttemptGeneration
                val connectionRef = connectionsRef.push()

                fun isCurrentAttempt(): Boolean =
                    sessionGeneration == sessionId &&
                        currentUid == uid &&
                        connectionAttemptGeneration == attemptId

                connectionRef.onDisconnect().removeValue()
                    .addOnCompleteListener { removeTask ->
                        if (!isCurrentAttempt()) return@addOnCompleteListener
                        if (!removeTask.isSuccessful) {
                            connectionSetupInFlight = false
                            Log.w("PresenceRepository", "Could not register connection cleanup for $uid", removeTask.exception)
                            return@addOnCompleteListener
                        }

                        lastSeenRef.onDisconnect().setValue(ServerValue.TIMESTAMP)
                            .addOnCompleteListener { lastSeenTask ->
                                if (!isCurrentAttempt()) return@addOnCompleteListener
                                if (!lastSeenTask.isSuccessful) {
                                    connectionSetupInFlight = false
                                    Log.w("PresenceRepository", "Could not register last-seen cleanup for $uid", lastSeenTask.exception)
                                    return@addOnCompleteListener
                                }

                                activeConnectionRef = connectionRef
                                connectionSetupInFlight = false
                                connectionRef.setValue(true)
                                    .addOnSuccessListener {
                                        if (!isCurrentAttempt()) connectionRef.removeValue()
                                    }
                                    .addOnFailureListener { error ->
                                        if (isCurrentAttempt()) {
                                            activeConnectionRef = null
                                            connectionSetupInFlight = false
                                        }
                                        Log.w("PresenceRepository", "Could not publish presence connection for $uid", error)
                                    }
                            }
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                if (sessionGeneration == sessionId && currentUid == uid) {
                    connectionAttemptGeneration += 1L
                    connectionSetupInFlight = false
                    activeConnectionRef = null
                }
                Log.w("PresenceRepository", "Presence connection listener cancelled for $uid", error.toException())
            }
        }

        connectionListener = listener
        connectedRef.addValueEventListener(listener)
    }

    fun stopPresence(
        markOffline: Boolean = true
    ) {
        sessionGeneration += 1L
        connectionAttemptGeneration += 1L
        connectionSetupInFlight = false
        val stoppedSession = sessionGeneration

        val uid = currentUid
        val listener = connectionListener
        val connectionRef = activeConnectionRef

        if (listener != null) {
            database.getReference(".info/connected").removeEventListener(listener)
        }

        connectionListener = null
        activeConnectionRef = null
        currentUid = null
        if (uid.isNullOrBlank()) return

        val lastSeenRef = database.getReference("presence").child(uid).child("lastSeen")
        fun cancelLastSeenDisconnectIfStillStopped() {
            if (sessionGeneration == stoppedSession && currentUid == null) {
                lastSeenRef.onDisconnect().cancel()
                    .addOnFailureListener { error ->
                        Log.w("PresenceRepository", "Could not cancel last-seen disconnect update for $uid", error)
                    }
            }
        }

        if (markOffline) {
            val updates = mutableMapOf<String, Any?>(
                "presence/$uid/lastSeen" to ServerValue.TIMESTAMP
            )
            connectionRef?.key?.let { connectionId ->
                updates["presence/$uid/connections/$connectionId"] = null
            }
            database.reference.updateChildren(updates)
                .addOnSuccessListener { cancelLastSeenDisconnectIfStillStopped() }
                .addOnFailureListener { error ->
                    // Keep the onDisconnect fallback armed when foreground cleanup fails.
                    Log.w("PresenceRepository", "Could not atomically stop presence for $uid", error)
                }
        } else if (connectionRef != null) {
            connectionRef.removeValue()
                .addOnSuccessListener { cancelLastSeenDisconnectIfStillStopped() }
                .addOnFailureListener { error ->
                    Log.w("PresenceRepository", "Could not remove this session's presence node for $uid", error)
                }
        }
    }

}
