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

    fun observePresence(
        uid: String
    ): Flow<Presence> = callbackFlow {

        if (uid.isBlank()) {
            trySend(Presence())
            close()
            return@callbackFlow
        }

        val presenceRef =
            database
                .getReference("presence")
                .child(uid)

        val listener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {
                    val online =
                        snapshot
                            .child("online")
                            .getValue(Boolean::class.java)
                            ?: false

                    val lastSeen =
                        snapshot
                            .child("lastSeen")
                            .getValue(Long::class.java)
                            ?: 0L

                    val onlineVisible =
                        snapshot
                            .child("onlineVisible")
                            .getValue(Boolean::class.java)
                            ?: true

                    val lastSeenVisible =
                        snapshot
                            .child("lastSeenVisible")
                            .getValue(Boolean::class.java)
                            ?: true

                    trySend(
                        Presence(
                            online =
                                online &&
                                        onlineVisible,

                            lastSeen =
                                if (
                                    lastSeenVisible
                                ) {
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

                override fun onCancelled(
                    error: DatabaseError
                ) {
                    close(error.toException())
                }
            }

        presenceRef.addValueEventListener(
            listener
        )

        awaitClose {
            presenceRef.removeEventListener(
                listener
            )
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

                    onlineRef
                        .onDisconnect()
                        .setValue(false)

                    lastSeenRef
                        .onDisconnect()
                        .setValue(
                            ServerValue.TIMESTAMP
                        )

                    /*
                     * Mark this connection active.
                     */
                    connectionRef.setValue(true)

                    /*
                     * Mark the user online.
                     */
                    onlineRef.setValue(true)
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

        if (listener != null) {
            database
                .getReference(".info/connected")
                .removeEventListener(
                    listener
                )
        }

        connectionListener = null
        currentUid = null

        if (
            markOffline &&
            !uid.isNullOrBlank()
        ) {
            val presenceRef =
                database
                    .getReference("presence")
                    .child(uid)

            presenceRef
                .child("online")
                .setValue(false)

            presenceRef
                .child("lastSeen")
                .setValue(
                    ServerValue.TIMESTAMP
                )
        }
    }
}
