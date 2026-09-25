package com.example.inchat.data.repository

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Keeps client-side time calculations aligned with Firebase's
 * estimated server clock.
 *
 * Firebase messages and presence timestamps are written with
 * ServerValue.TIMESTAMP, so relative UI calculations should use
 * the same clock rather than the device clock alone.
 */
object FirebaseServerClock {

    private val offsetRef =
        FirebaseDatabase
            .getInstance()
            .getReference(".info/serverTimeOffset")

    @Volatile
    private var serverTimeOffsetMillis = 0L

    private var offsetListener: ValueEventListener? = null

    @Synchronized
    fun start() {
        if (offsetListener != null) {
            return
        }

        val listener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {
                    serverTimeOffsetMillis =
                        snapshot
                            .getValue(Double::class.java)
                            ?.toLong()
                            ?: 0L
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {
                    // Keep the current offset; the local clock remains
                    // a safe fallback until Firebase restores the listener.
                }
            }

        offsetListener = listener

        offsetRef.addValueEventListener(
            listener
        )
    }

    @Synchronized
    fun stop() {
        offsetListener?.let { listener ->
            offsetRef.removeEventListener(
                listener
            )
        }

        offsetListener = null
        serverTimeOffsetMillis = 0L
    }

    fun now(): Long =
        System.currentTimeMillis() +
                serverTimeOffsetMillis
}
