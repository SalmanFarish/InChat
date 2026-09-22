package com.example.inchat.data.repository

import com.example.inchat.data.model.BlockedUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class ModerationRepository {

    private val database =
        FirebaseDatabase.getInstance()

    /*
     * ---------------------------------------------------------
     * BLOCKED USERS
     * ---------------------------------------------------------
     *
     * Database structure:
     *
     * blockedUsers/{currentUserId}/{blockedUserId}
     *     uid
     *     username
     *     blockedAt
     */

    fun getBlockedUsersFlow(
        currentUserId: String
    ): Flow<List<BlockedUser>> = callbackFlow {

        if (currentUserId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val blockedRef =
            database
                .getReference("blockedUsers")
                .child(currentUserId)

        blockedRef.keepSynced(true)

        val listener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {
                    val blockedUsers =
                        mutableListOf<BlockedUser>()

                    for (
                    child in snapshot.children
                    ) {

                        val blockedUser =
                            child.getValue(
                                BlockedUser::class.java
                            )

                        if (
                            blockedUser != null &&
                            blockedUser.uid.isNotBlank()
                        ) {
                            blockedUsers.add(
                                blockedUser
                            )
                        }
                    }

                    trySend(
                        blockedUsers.sortedByDescending {
                            it.blockedAt
                        }
                    )
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {
                    close(
                        error.toException()
                    )
                }
            }

        blockedRef.addValueEventListener(
            listener
        )

        awaitClose {
            blockedRef.removeEventListener(
                listener
            )
        }
    }

    suspend fun isUserBlocked(
        currentUserId: String,
        otherUserId: String
    ): Boolean {

        if (
            currentUserId.isBlank() ||
            otherUserId.isBlank()
        ) {
            return false
        }

        val snapshot =
            database
                .getReference("blockedUsers")
                .child(currentUserId)
                .child(otherUserId)
                .get()
                .await()

        return snapshot.exists()
    }

    suspend fun blockUser(
        currentUserId: String,
        userIdToBlock: String,
        usernameToBlock: String
    ): Result<Unit> {

        return try {

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match block owner."
                    )
                )
            }

            if (
                currentUserId.isBlank() ||
                userIdToBlock.isBlank()
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid block request"
                    )
                )
            }

            if (
                currentUserId ==
                userIdToBlock
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "You cannot block yourself"
                    )
                )
            }

            val blockedUser =
                mapOf(
                    "uid" to userIdToBlock,
                    "username" to
                            usernameToBlock,
                    "blockedAt" to
                            ServerValue.TIMESTAMP
                )

            database
                .getReference("blockedUsers")
                .child(currentUserId)
                .child(userIdToBlock)
                .setValue(blockedUser)
                .await()

            Result.success(Unit)

        } catch (e: Exception) {

            Result.failure(e)
        }
    }

    suspend fun unblockUser(
        currentUserId: String,
        userIdToUnblock: String
    ): Result<Unit> {

        return try {

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != currentUserId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match unblock owner."
                    )
                )
            }

            if (
                currentUserId.isBlank() ||
                userIdToUnblock.isBlank()
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid unblock request"
                    )
                )
            }

            database
                .getReference("blockedUsers")
                .child(currentUserId)
                .child(userIdToUnblock)
                .removeValue()
                .await()

            Result.success(Unit)

        } catch (e: Exception) {

            Result.failure(e)
        }
    }

    /*
     * ---------------------------------------------------------
     * REPORTS
     * ---------------------------------------------------------
     *
     * Database structure:
     *
     * reports/{reportId}
     *     reporterId
     *     reportedUserId
     *     reportedUsername
     *     reason
     *     timestamp
     */

    suspend fun reportUser(
        reporterId: String,
        reportedUserId: String,
        reportedUsername: String,
        reason: String
    ): Result<Unit> {

        return try {

            val firebaseUser =
                auth.currentUser

            if (
                firebaseUser == null ||
                firebaseUser.uid != reporterId
            ) {
                return Result.failure(
                    IllegalStateException(
                        "Authenticated user does not match report owner."
                    )
                )
            }

            if (
                reporterId.isBlank() ||
                reportedUserId.isBlank()
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "Invalid report request"
                    )
                )
            }

            if (
                reporterId ==
                reportedUserId
            ) {
                return Result.failure(
                    IllegalArgumentException(
                        "You cannot report yourself"
                    )
                )
            }

            val cleanReason =
                reason.trim()

            if (cleanReason.isBlank()) {
                return Result.failure(
                    IllegalArgumentException(
                        "Report reason cannot be empty"
                    )
                )
            }

            if (cleanReason.length > 500) {
                return Result.failure(
                    IllegalArgumentException(
                        "Report reason is too long"
                    )
                )
            }

            val reportRef =
                database
                    .getReference("reports")
                    .push()

            val reportId =
                reportRef.key
                    ?: return Result.failure(
                        IllegalStateException(
                            "Could not create report ID"
                        )
                    )

            val reportData =
                mapOf(
                    "reporterId" to reporterId,
                    "reportedUserId" to
                            reportedUserId,
                    "reportedUsername" to
                            reportedUsername,
                    "reason" to cleanReason,
                    "timestamp" to
                            ServerValue.TIMESTAMP
                )

            reportRef
                .setValue(reportData)
                .await()

            Result.success(Unit)

        } catch (e: Exception) {

            Result.failure(e)
        }
    }
}