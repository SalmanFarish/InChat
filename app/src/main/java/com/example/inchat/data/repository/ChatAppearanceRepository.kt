package com.example.inchat.data.repository

import com.example.inchat.ui.chat.ChatTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class ChatAppearanceRepository {

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

    fun observeTheme(
        chatId: String
    ): Flow<String?> =
        callbackFlow {

            val themeRef =
                database
                    .getReference(
                        "chats"
                    )
                    .child(
                        chatId
                    )
                    .child(
                        "appearance"
                    )
                    .child(
                        "themeId"
                    )

            themeRef.keepSynced(
                true
            )

            val listener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot:
                        DataSnapshot
                    ) {

                        val themeId =
                            snapshot.getValue(
                                String::class.java
                            )

                        trySend(
                            themeId
                        )
                    }

                    override fun onCancelled(
                        error:
                        DatabaseError
                    ) {

                        close(
                            error.toException()
                        )
                    }
                }

            themeRef.addValueEventListener(
                listener
            )

            awaitClose {

                themeRef.removeEventListener(
                    listener
                )
            }
        }

    suspend fun setTheme(
        chatId: String,
        currentUserId: String,
        themeId: String
    ): Result<Unit> {

        return try {

            if (
                chatId.isBlank() ||
                currentUserId.isBlank()
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid chat information"
                    )
                )
            }

            if (
                !ChatTheme.isValidId(
                    themeId
                )
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Invalid chat theme"
                    )
                )
            }

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid !=
                currentUserId
            ) {

                return Result.failure(
                    IllegalStateException(
                        "Authentication state is invalid"
                    )
                )
            }

            val themeRef =
                database
                    .getReference("chats")
                    .child(chatId)
                    .child("appearance")
                    .child("themeId")

            try {
                themeRef
                    .setValue(themeId)
                    .await()
            } catch (primaryError: Exception) {
                val legacyId =
                    ChatTheme.legacyIdFor(themeId)

                if (
                    legacyId.isNullOrBlank() ||
                    legacyId == themeId
                ) {
                    throw primaryError
                }

                /*
                 * Keep compatibility with older deployed Firebase rules
                 * that still accept the original theme storage IDs.
                 */
                themeRef
                    .setValue(legacyId)
                    .await()
            }

            Result.success(Unit)

        } catch (
            e: Exception
        ) {

            Result.failure(
                e
            )
        }
    }
}