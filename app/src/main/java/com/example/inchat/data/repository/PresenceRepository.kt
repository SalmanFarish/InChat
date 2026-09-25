package com.example.inchat.data.repository

import com.example.inchat.data.model.Presence
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    /*
     * Android Realtime Database can close an otherwise idle
     * connection after a period of inactivity. Keeping a real
     * data listener open prevents the presence connection from
     * being considered idle while the app is active.
     */
    private var activeKeepAliveListener:
            ValueEventListener? = null

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

        fun emitPresence() {
            trySend(
                Presence(
                    online =
                        hasActiveConnection &&
                                onlineVisible,

                    lastSeen =
                        if (lastSeenVisible) {
                            lastSeen
                        } else {
                            0L
                        },

                    onlineVisible =
                        onlineVisible,

                    lastSeenVisible =
                        lastSeenVisible
                )
            )
        }

        val presenceRef =
            database
                .getReference("presence")
                .child(uid)

        /*
         * The connections collection is the authoritative online
         * state. Any remaining connection node means the account
         * has at least one active realtime connection.
         */
        val connectionsListener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {
                    hasActiveConnection =
                        snapshot
                            .children
                            .any {
                                it.getValue(
                                    Boolean::class.java
                                ) == true
                            }

                    emitPresence()
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {
                    /*
                     * A denied read here normally means the owner
                     * has hidden online status from this viewer.
                     */
                    hasActiveConnection = false
                    onlineVisible = false
                    emitPresence()
                }
            }

        val lastSeenListener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {
                    lastSeen =
                        snapshot
                            .getValue(Long::class.java)
                            ?: 0L

                    emitPresence()
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {
                    lastSeen = 0L
                    lastSeenVisible = false
                    emitPresence()
                }
            }

        presenceRef
            .child("connections")
            .addValueEventListener(
                connectionsListener
            )

        presenceRef
            .child("lastSeen")
            .addValueEventListener(
                lastSeenListener
            )

        var onlineVisibilityListener:
                ValueEventListener? = null

        var lastSeenVisibilityListener:
                ValueEventListener? = null

        if (
            auth.currentUser?.uid == uid
        ) {
            onlineVisibilityListener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {
                        onlineVisible =
                            snapshot
                                .getValue(
                                    Boolean::class.java
                                )
                                ?: true

                        emitPresence()
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                        emitPresence()
                    }
                }

            lastSeenVisibilityListener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {
                        lastSeenVisible =
                            snapshot
                                .getValue(
                                    Boolean::class.java
                                )
                                ?: true

                        emitPresence()
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                        emitPresence()
                    }
                }

            presenceRef
                .child("onlineVisible")
                .addValueEventListener(
                    onlineVisibilityListener
                )

            presenceRef
                .child("lastSeenVisible")
                .addValueEventListener(
                    lastSeenVisibilityListener
                )
        }

        awaitClose {
            presenceRef
                .child("connections")
                .removeEventListener(
                    connectionsListener
                )

            presenceRef
                .child("lastSeen")
                .removeEventListener(
                    lastSeenListener
                )

            onlineVisibilityListener?.let {
                presenceRef
                    .child("onlineVisible")
                    .removeEventListener(
                        it
                    )
            }

            lastSeenVisibilityListener?.let {
                presenceRef
                    .child("lastSeenVisible")
                    .removeEventListener(
                        it
                    )
            }
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
        if (uid.isBlank()) {
            return
        }

        if (
            currentUid == uid &&
            connectionListener != null
        ) {
            return
        }

        stopPresence(
            markOffline = false
        )

        currentUid = uid

        val connectedRef =
            database
                .getReference(
                    ".info/connected"
                )

        val presenceRef =
            database
                .getReference("presence")
                .child(uid)

        val connectionsRef =
            presenceRef
                .child("connections")

        val lastSeenRef =
            presenceRef
                .child("lastSeen")

        val listener =
            object :
                ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {
                    val connected =
                        snapshot.getValue(
                            Boolean::class.java
                        ) ?: false

                    if (!connected) {
                        /*
                         * Firebase removes the previous connection
                         * node server-side through onDisconnect().
                         * Clear our local reference so the next
                         * connected event creates a fresh node.
                         */
                        activeConnectionRef = null
                        return
                    }

                    /*
                     * A single Firebase connection can trigger the
                     * .info/connected listener more than once. Do
                     * not create duplicate connection nodes.
                     */
                    if (
                        activeConnectionRef != null
                    ) {
                        return
                    }

                    val connectionRef =
                        connectionsRef.push()

                    /*
                     * Firebase's documented presence pattern is:
                     * 1. create a unique connection node
                     * 2. queue its disconnect removal
                     * 3. queue lastSeen timestamp on disconnect
                     * 4. mark this connection active
                     *
                     * Both disconnect operations are registered
                     * before the connection node is written.
                     */
                    connectionRef
                        .onDisconnect()
                        .removeValue()
                        .addOnCompleteListener { removeTask ->

                            if (
                                !removeTask.isSuccessful
                            ) {
                                activeConnectionRef = null
                                return@addOnCompleteListener
                            }

                            lastSeenRef
                                .onDisconnect()
                                .setValue(
                                    ServerValue.TIMESTAMP
                                )
                                .addOnCompleteListener { lastSeenTask ->

                                    if (
                                        !lastSeenTask.isSuccessful
                                    ) {
                                        activeConnectionRef = null
                                        return@addOnCompleteListener
                                    }

                                    connectionRef
                                        .setValue(
                                            true
                                        )
                                        .addOnSuccessListener {
                                            activeConnectionRef =
                                                connectionRef
                                        }
                                        .addOnFailureListener {
                                            activeConnectionRef = null
                                        }
                                }
                        }
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {
                    activeConnectionRef = null
                }
            }

        connectionListener =
            listener

        connectedRef
            .addValueEventListener(
                listener
            )
    }

    fun stopPresence(
        markOffline: Boolean = true
    ) {
        val uid =
            currentUid

        val listener =
            connectionListener

        val connectionRef =
            activeConnectionRef

        val keepAliveListener =
            activeKeepAliveListener

        if (
            listener != null
        ) {
            database
                .getReference(
                    ".info/connected"
                )
                .removeEventListener(
                    listener
                )
        }

        connectionListener = null
        activeConnectionRef = null
        activeKeepAliveListener = null
        currentUid = null

        if (
            uid.isNullOrBlank()
        ) {
            return
        }

        /*
         * Remove only this device's connection. Other signed-in
         * devices keep their own connection nodes alive, so the
         * account remains online until the final connection ends.
         */
        if (
            connectionRef != null
        ) {
            keepAliveListener?.let {
                connectionRef
                    .removeEventListener(
                        it
                    )
            }

            connectionRef
                .onDisconnect()
                .cancel()

            connectionRef
                .removeValue()
        }

        /*
         * On a deliberate logout, record the current time. This
         * does not determine online state; the connection collection
         * does. The final disconnect handler also updates lastSeen.
         */
        if (
            markOffline
        ) {
            database
                .getReference("presence")
                .child(uid)
                .child("lastSeen")
                .setValue(
                    ServerValue.TIMESTAMP
                )
        }
    }
}
