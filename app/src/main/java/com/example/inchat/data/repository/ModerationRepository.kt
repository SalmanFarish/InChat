package com.example.inchat.data.repository

import android.util.Log

import com.google.firebase.auth.FirebaseAuth
import com.example.inchat.data.model.BlockedUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ModerationRepository {

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

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
                    Log.e(
                        "ModerationRepository",
                        "Blocked users listener cancelled: ${error.message}"
                    )
                    trySend(emptyList())
                    close()
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
        reason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val firebaseUser = auth.currentUser
                ?: return@withContext Result.failure(
                    IllegalStateException("You must be signed in.")
                )

            if (firebaseUser.uid != reporterId || reporterId.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Authenticated user does not match report owner.")
                )
            }
            if (reportedUserId.isBlank() || reportedUserId == reporterId) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid report target.")
                )
            }

            val cleanReason = reason.trim()
            if (cleanReason.isBlank() || cleanReason.length > 500) {
                return@withContext Result.failure(
                    IllegalArgumentException("Report details are invalid.")
                )
            }
            if (!CloudflareWorkerConfig.isConfigured) {
                return@withContext Result.failure(
                    IllegalStateException("The report service is not configured.")
                )
            }

            val idToken = firebaseUser.getIdToken(false).await().token
                ?: throw IllegalStateException("Could not obtain Firebase ID token.")
            val connection = URL(
                CloudflareWorkerConfig.API_BASE_URL + "/reports"
            ).openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output ->
                    output.write(
                        JSONObject()
                            .put("reportedUserId", reportedUserId)
                            .put("reason", cleanReason)
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val responseText = runCatching {
                        (connection.errorStream ?: connection.inputStream)
                            .bufferedReader()
                            .use { it.readText() }
                    }.getOrDefault("")
                    val serverMessage = runCatching {
                        JSONObject(responseText).optString("error")
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                    return@withContext Result.failure(
                        IllegalStateException(
                            serverMessage ?: "Report submission failed ($responseCode)."
                        )
                    )
                }

                Result.success(Unit)
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}