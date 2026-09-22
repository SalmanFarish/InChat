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

    private var activeConnectionRef:
            com.google.firebase.database.DatabaseReference? = null

    fun observePresence(
        uid: String
    ): Flow<Presence> = callbackFlow {

        if (uid.isBlank()) {
            trySend(Presence())
            close()
            return@callbackFlow
        }

        var online = false
        var lastSeen = 0L
        var onlineVisible = true
        var lastSeenVisible = true

        fun emitPresence() {
            trySend(
                Presence(
                    online = online && onlineVisible,
                    lastSeen =
                        if (lastSeenVisible) {
                            lastSeen
                        } else {
                            0L
                        },
                    onlineVisible = onlineVisible,
                    lastSeenVisible = lastSeenVisible
                )
            )
        }

        val presenceRef =
            database
                .getReference("presence")
                .child(uid)

        val onlineListener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {
                    online =
                        snapshot
                            .getValue(Boolean::class.java)
                            ?: false
                    emitPresence()
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {
                    /*
                     * A cancelled read normally means the owner has
                     * hidden this field. Treat it as unavailable.
                     */
                    online = false
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

        /*
         * Visibility settings are intentionally read only by the
         * account owner. For other users, successful child reads
         * imply that the corresponding visibility flag is enabled.
         */
        if (
            auth.currentUser?.uid == uid
        ) {
            val visibilityListener =
                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {
                        onlineVisible =
                            snapshot
                                .child("onlineVisible")
                                .getValue(Boolean::class.java)
                                ?: true

                        lastSeenVisible =
                            snapshot
                                .child("lastSeenVisible")
                                .getValue(Boolean::class.java)
                                ?: true

                        emitPresence()
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                        emitPresence()
                    }
                }

            presenceRef.addValueEventListener(
                visibilityListener
            )

            awaitClose {
                presenceRef
                    .child("online")
                    .removeEventListener(
                        onlineListener
                    )

                presenceRef
                    .child("lastSeen")
                    .removeEventListener(
                        lastSeenListener
                    )

                presenceRef.removeEventListener(
                    visibilityListener
                )
            }

        } else {
            presenceRef
                .child("online")
                .addValueEventListener(
                    onlineListener
                )

            presenceRef
                .child("lastSeen")
                .addValueEventListener(
                    lastSeenListener
                )

            awaitClose {
                presenceRef
                    .child("online")
                    .removeEventListener(
                        onlineListener
                    )

                presenceRef
                    .child("lastSeen")
                    .removeEventListener(
                        lastSeenListener
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
            database.getReference(
                ".info/connected"
            )

        val presenceRef =
            database
                .getReference("presence")
                .child(uid)

        val connectionsRef =
            presenceRef
                .child("connections")

        val onlineRef =
            presenceRef
                .child("online")

        val lastSeenRef =
            presenceRef
                .child("lastSeen")

        val listener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {
                    val connected =
                        snapshot.getValue(
                            Boolean::class.java
                        ) ?: false

                    if (!connected) {
                        return
                    }

                    val connectionRef =
                        connectionsRef.push()

                    /*
                     * Register disconnect handlers BEFORE
                     * declaring the connection online.
                     */
                    connectionRef
                        .onDisconnect()
                        .removeValue()

                    /*
                     * Only this connection is owned by this app
                     * instance. The server derives account-wide
                     * online state from all active connections.
                     */
                    connectionRef.setValue(true)

                    activeConnectionRef =
                        connectionRef

                    /*
                     * Do not set online=false or lastSeen on a
                     * single connection's disconnect. Another
                     * device may still be connected.
                     */
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {
                    // Presence failures should not crash the app.
                }
            }

        connectionListener =
            listener

        connectedRef.addValueEventListener(
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

        if (listener != null) {
            database
                .getReference(".info/connected")
                .removeEventListener(
                    listener
                )
        }

        connectionListener = null
        activeConnectionRef = null
        currentUid = null

        if (!uid.isNullOrBlank()) {
            /*
             * Removing only this device's connection lets the
             * backend determine whether the user is still online
             * elsewhere.
             */
            connectionRef?.removeValue()
        }
    }
}
