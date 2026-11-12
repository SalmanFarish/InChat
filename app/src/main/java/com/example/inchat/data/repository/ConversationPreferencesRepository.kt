package com.example.inchat.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class ConversationPreference(
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    val clearedAt: Long = 0L
)

/**
 * Stores per-user inbox choices separately from the shared chat record.
 * Pinning or hiding a conversation must never change it for the other
 * participant or remove a user's group membership.
 */
class ConversationPreferencesRepository {

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun observePreferences(
        currentUserId: String
    ): Flow<Map<String, ConversationPreference>> = callbackFlow {

        if (
            currentUserId.isBlank() ||
            auth.currentUser?.uid != currentUserId
        ) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }

        val preferencesRef = database
            .getReference("conversationPreferences")
            .child(currentUserId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val preferences = snapshot.children.mapNotNull { child ->
                    val chatId = child.key
                        ?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null

                    chatId to ConversationPreference(
                        isPinned = child.child("pinned")
                            .getValue(Boolean::class.java) ?: false,
                        isHidden = child.child("hidden")
                            .getValue(Boolean::class.java) ?: false,
                        clearedAt = child.child("clearedAt")
                            .getValue(Long::class.java) ?: 0L
                    )
                }.toMap()

                trySend(preferences)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }

        preferencesRef.addValueEventListener(listener)

        awaitClose {
            preferencesRef.removeEventListener(listener)
        }
    }

    suspend fun setPinned(
        currentUserId: String,
        chatId: String,
        pinned: Boolean
    ): Result<Unit> = setPreference(
        currentUserId = currentUserId,
        chatId = chatId,
        key = "pinned",
        value = pinned
    )

    suspend fun setHidden(
        currentUserId: String,
        chatId: String,
        hidden: Boolean
    ): Result<Unit> = setPreference(
        currentUserId = currentUserId,
        chatId = chatId,
        key = "hidden",
        value = hidden
    )

    private suspend fun setPreference(
        currentUserId: String,
        chatId: String,
        key: String,
        value: Boolean
    ): Result<Unit> {
        return try {
            if (
                currentUserId.isBlank() ||
                chatId.isBlank() ||
                auth.currentUser?.uid != currentUserId
            ) {
                throw IllegalStateException(
                    "Your account could not be verified for this action."
                )
            }

            database.getReference("conversationPreferences")
                .child(currentUserId)
                .child(chatId)
                .child(key)
                .setValue(value)
                .await()

            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
