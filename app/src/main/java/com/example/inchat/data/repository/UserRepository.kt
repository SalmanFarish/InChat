package com.example.inchat.data.repository

import android.net.Uri
import com.example.inchat.data.model.RecoveryCodeSet
import com.example.inchat.data.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UsernameAlreadyTakenException :
    IllegalStateException(
        "That username is already taken"
    )

class UserRepository {

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

    private val storage =
        FirebaseStorage.getInstance()

    private fun usernameKey(
        username: String
    ): String {

        return username
            .trim()
            .lowercase(
                Locale.ROOT
            )
    }

    /*
     * =========================================================
     * USERNAME AVAILABILITY
     * =========================================================
     */
    suspend fun isUsernameAvailable(
        username: String
    ): Boolean {

        val key =
            usernameKey(
                username
            )

        if (
            key.isBlank()
        ) {

            return false
        }

        val snapshot =
            database
                .getReference(
                    "usernames"
                )
                .child(
                    key
                )
                .get()
                .await()

        return !snapshot.exists()
    }

    /*
     * =========================================================
     * CLAIM USERNAME
     * =========================================================
     */
    suspend fun claimUsername(
        username: String,
        uid: String
    ): Result<Unit> =
        suspendCancellableCoroutine { continuation ->

            val key =
                usernameKey(
                    username
                )

            if (
                key.isBlank()
            ) {

                continuation.resume(
                    Result.failure(
                        IllegalArgumentException(
                            "Username cannot be blank"
                        )
                    )
                )

                return@suspendCancellableCoroutine
            }

            if (
                uid.isBlank()
            ) {

                continuation.resume(
                    Result.failure(
                        IllegalArgumentException(
                            "UID cannot be blank"
                        )
                    )
                )

                return@suspendCancellableCoroutine
            }

            val usernameRef =
                database
                    .getReference(
                        "usernames"
                    )
                    .child(
                        key
                    )

            usernameRef.runTransaction(

                object :
                    Transaction.Handler {

                    override fun doTransaction(
                        currentData:
                        MutableData
                    ): Transaction.Result {

                        if (
                            currentData.value != null
                        ) {

                            val existingUid =
                                currentData.getValue(
                                    String::class.java
                                )

                            return if (
                                existingUid == uid
                            ) {

                                Transaction.success(
                                    currentData
                                )

                            } else {

                                Transaction.abort()
                            }
                        }

                        currentData.value =
                            uid

                        return Transaction.success(
                            currentData
                        )
                    }

                    override fun onComplete(
                        error:
                        DatabaseError?,
                        committed:
                        Boolean,
                        currentData:
                        DataSnapshot?
                    ) {

                        if (
                            error != null
                        ) {

                            continuation.resume(
                                Result.failure(
                                    error.toException()
                                )
                            )

                            return
                        }

                        if (
                            !committed
                        ) {

                            continuation.resume(
                                Result.failure(
                                    UsernameAlreadyTakenException()
                                )
                            )

                            return
                        }

                        continuation.resume(
                            Result.success(
                                Unit
                            )
                        )
                    }
                }
            )
        }

    /*
     * =========================================================
     * RELEASE USERNAME
     * =========================================================
     */
    suspend fun releaseUsername(
        username: String,
        uid: String
    ): Result<Unit> =
        suspendCancellableCoroutine { continuation ->

            val key =
                usernameKey(
                    username
                )

            if (
                key.isBlank() ||
                uid.isBlank()
            ) {

                continuation.resume(
                    Result.success(
                        Unit
                    )
                )

                return@suspendCancellableCoroutine
            }

            val usernameRef =
                database
                    .getReference(
                        "usernames"
                    )
                    .child(
                        key
                    )

            usernameRef.runTransaction(

                object :
                    Transaction.Handler {

                    override fun doTransaction(
                        currentData:
                        MutableData
                    ): Transaction.Result {

                        val existingUid =
                            currentData.getValue(
                                String::class.java
                            )

                        return if (
                            existingUid == uid
                        ) {

                            currentData.value =
                                null

                            Transaction.success(
                                currentData
                            )

                        } else {

                            Transaction.success(
                                currentData
                            )
                        }
                    }

                    override fun onComplete(
                        error:
                        DatabaseError?,
                        committed:
                        Boolean,
                        currentData:
                        DataSnapshot?
                    ) {

                        if (
                            error != null
                        ) {

                            continuation.resume(
                                Result.failure(
                                    error.toException()
                                )
                            )

                            return
                        }

                        continuation.resume(
                            Result.success(
                                Unit
                            )
                        )
                    }
                }
            )
        }

    /*
     * =========================================================
     * SAVE USER
     * =========================================================
     */
    suspend fun saveUser(
        uid: String,
        username: String,
        displayName: String = username,
        bio: String = ""
    ): Result<Unit> {

        if (
            uid.isBlank()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "UID cannot be blank"
                )
            )
        }

        val cleanUsername =
            username.trim()

        val cleanDisplayName =
            displayName.trim()

        val cleanBio =
            bio.trim()

        if (
            cleanUsername.isBlank()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Username cannot be blank"
                )
            )
        }

        if (
            cleanDisplayName.length > 30
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Display name must be 30 characters or less"
                )
            )
        }

        if (
            cleanBio.length > 160
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Bio must be 160 characters or less"
                )
            )
        }

        return try {

            val data =
                mapOf<String, Any>(

                    "uid" to
                            uid,

                    "username" to
                            cleanUsername,

                    "displayName" to
                            cleanDisplayName
                                .ifBlank {
                                    cleanUsername
                                },

                    "bio" to
                            cleanBio
                )

            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )
                .updateChildren(
                    data
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

    /*
     * =========================================================
     * UPDATE PROFILE
     * =========================================================
     */
    suspend fun updateProfile(
        uid: String,
        displayName: String,
        bio: String
    ): Result<Unit> {

        if (
            uid.isBlank()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "UID cannot be blank"
                )
            )
        }

        val cleanDisplayName =
            displayName.trim()

        val cleanBio =
            bio.trim()

        if (
            cleanDisplayName.length > 30
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Display name must be 30 characters or less"
                )
            )
        }

        if (
            cleanBio.length > 160
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Bio must be 160 characters or less"
                )
            )
        }

        return try {

            val profileData =
                mapOf<String, Any>(

                    "displayName" to
                            cleanDisplayName,

                    "bio" to
                            cleanBio
                )

            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )
                .updateChildren(
                    profileData
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

    /*
     * =========================================================
     * UPLOAD PROFILE PHOTO
     * =========================================================
     *
     * The actual image is stored in Firebase Cloud Storage.
     *
     * The user's Realtime Database record receives only:
     *
     * users/{uid}/profilePhotoUrl
     *
     * A unique Storage object is used for every new photo so
     * image-loader caching cannot keep showing an old URL.
     */
    suspend fun uploadProfilePhoto(
        uid: String,
        imageUri: Uri,
        mimeType: String? = null
    ): Result<String> {

        if (
            uid.isBlank()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "UID cannot be blank"
                )
            )
        }

        if (
            imageUri.toString().isBlank()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Invalid image"
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
                    "Authenticated user does not match profile owner"
                )
            )
        }

        val cleanMimeType =
            mimeType
                ?.takeIf {
                    it.startsWith(
                        "image/",
                        ignoreCase = true
                    )
                }
                ?: "image/jpeg"

        return try {

            val userRef =
                database
                    .getReference(
                        "users"
                    )
                    .child(
                        uid
                    )

            val existingSnapshot =
                userRef
                    .child(
                        "profilePhotoUrl"
                    )
                    .get()
                    .await()

            val oldPhotoUrl =
                existingSnapshot
                    .getValue(
                        String::class.java
                    )
                    .orEmpty()

            val photoId =
                UUID
                    .randomUUID()
                    .toString()

            val photoRef =
                storage
                    .reference
                    .child(
                        "profilePhotos"
                    )
                    .child(
                        uid
                    )
                    .child(
                        photoId
                    )

            val metadata =
                StorageMetadata
                    .Builder()
                    .setContentType(
                        cleanMimeType
                    )
                    .build()

            photoRef
                .putFile(
                    imageUri,
                    metadata
                )
                .await()

            val downloadUrl =
                photoRef
                    .downloadUrl
                    .await()
                    .toString()

            userRef
                .updateChildren(
                    mapOf(
                        "profilePhotoUrl" to
                                downloadUrl
                    )
                )
                .await()

            /*
             * Delete the previous photo after the new URL has
             * been successfully saved.
             *
             * A failure here does not invalidate the new photo.
             */
            if (
                oldPhotoUrl.isNotBlank() &&
                oldPhotoUrl != downloadUrl
            ) {

                try {

                    storage
                        .getReferenceFromUrl(
                            oldPhotoUrl
                        )
                        .delete()
                        .await()

                } catch (
                    cleanupError: Exception
                ) {

                    /*
                     * Cleanup failure is intentionally ignored.
                     * The new profile photo is already valid.
                     */
                    cleanupError.printStackTrace()
                }
            }

            Result.success(
                downloadUrl
            )

        } catch (
            e: Exception
        ) {

            Result.failure(
                e
            )
        }
    }

    /*
     * =========================================================
     * DELETE ACCOUNT-OWNED DATA
     * =========================================================
     */
    suspend fun deleteAccountData(
        uid: String,
        username: String
    ): Result<Unit> {

        if (
            uid.isBlank()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "UID cannot be blank"
                )
            )
        }

        return try {

            val releaseResult =
                releaseUsername(
                    username =
                        username,

                    uid =
                        uid
                )

            if (
                releaseResult.isFailure
            ) {

                return Result.failure(
                    releaseResult
                        .exceptionOrNull()
                        ?: IllegalStateException(
                            "Could not release username"
                        )
                )
            }

            val blockedSnapshot =
                database
                    .getReference(
                        "blockedUsers"
                    )
                    .child(
                        uid
                    )
                    .get()
                    .await()

            val userChatsSnapshot =
                database
                    .getReference(
                        "userChats"
                    )
                    .child(
                        uid
                    )
                    .get()
                    .await()

            val updates =
                mutableMapOf<String, Any?>()

            updates[
                "users/$uid"
            ] =
                null

            updates[
                "presence/$uid"
            ] =
                null

            updates[
                "recoveryCodes/$uid"
            ] =
                null

            for (
            child in
            blockedSnapshot.children
            ) {

                val blockedUserId =
                    child.key

                if (
                    !blockedUserId.isNullOrBlank()
                ) {

                    updates[
                        "blockedUsers/$uid/$blockedUserId"
                    ] =
                        null
                }
            }

            for (
            child in
            userChatsSnapshot.children
            ) {

                val chatId =
                    child.key

                if (
                    !chatId.isNullOrBlank()
                ) {

                    updates[
                        "userChats/$uid/$chatId"
                    ] =
                        null
                }
            }

            if (
                updates.isNotEmpty()
            ) {

                database
                    .getReference()
                    .updateChildren(
                        updates
                    )
                    .await()
            }

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

    /*
     * =========================================================
     * SAVE RECOVERY CODES
     * =========================================================
     */
    suspend fun saveRecoveryCodes(
        uid: String,
        recoveryCodeSet: RecoveryCodeSet
    ): Result<Unit> {

        if (
            uid.isBlank() ||
            recoveryCodeSet.saltHex.isBlank() ||
            recoveryCodeSet.codeHashesHex.size != 5
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Invalid recovery-code data"
                )
            )
        }

        return try {

            val codeMap =
                recoveryCodeSet
                    .codeHashesHex
                    .mapIndexed {
                            index,
                            hash ->

                        index.toString() to
                                hash
                    }
                    .toMap()

            val recoveryData =
                mapOf<String, Any>(

                    "salt" to
                            recoveryCodeSet.saltHex,

                    "codes" to
                            codeMap
                )

            database
                .getReference(
                    "recoveryCodes"
                )
                .child(
                    uid
                )
                .setValue(
                    recoveryData
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

    /*
     * =========================================================
     * FCM TOKEN
     * =========================================================
     */
    suspend fun saveFcmToken(
        uid: String,
        token: String
    ): Result<Unit> {

        if (
            uid.isBlank() ||
            token.isBlank()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Invalid FCM token data"
                )
            )
        }

        return try {

            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )
                .updateChildren(
                    mapOf(
                        "fcmToken" to
                                token
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

    /*
     * =========================================================
     * CLEAR FCM TOKEN
     * =========================================================
     */
    suspend fun clearFcmToken(
        uid: String
    ): Result<Unit> {

        if (
            uid.isBlank()
        ) {

            return Result.success(
                Unit
            )
        }

        return try {

            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )
                .child(
                    "fcmToken"
                )
                .removeValue()
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

    /*
     * =========================================================
     * GET USER BY ID
     * =========================================================
     */
    suspend fun getUserById(
        uid: String
    ): User? {

        if (
            uid.isBlank()
        ) {

            return null
        }

        val snapshot =
            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )
                .get()
                .await()

        return snapshot.getValue(
            User::class.java
        )
    }

    /*
     * =========================================================
     * FAST USER LOOKUP
     * =========================================================
     */
    suspend fun getUserByIdFast(
        uid: String
    ): User? {

        if (
            uid.isBlank()
        ) {

            return null
        }

        val userRef =
            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )

        val cachedSnapshot =
            try {

                suspendCancellableCoroutine<DataSnapshot> {
                        continuation ->

                    val listener =
                        object :
                            ValueEventListener {

                            override fun onDataChange(
                                snapshot:
                                DataSnapshot
                            ) {

                                if (
                                    continuation.isActive
                                ) {

                                    continuation.resume(
                                        snapshot
                                    )
                                }
                            }

                            override fun onCancelled(
                                error:
                                DatabaseError
                            ) {

                                if (
                                    continuation.isActive
                                ) {

                                    continuation.resumeWithException(
                                        error.toException()
                                    )
                                }
                            }
                        }

                    userRef
                        .addListenerForSingleValueEvent(
                            listener
                        )

                    continuation.invokeOnCancellation {

                        userRef
                            .removeEventListener(
                                listener
                            )
                    }
                }

            } catch (
                _: Exception
            ) {

                null
            }

        val cachedUser =
            cachedSnapshot
                ?.getValue(
                    User::class.java
                )

        if (
            cachedUser != null &&
            cachedUser.username.isNotBlank()
        ) {

            return cachedUser
        }

        return try {

            userRef
                .get()
                .await()
                .getValue(
                    User::class.java
                )

        } catch (
            _: Exception
        ) {

            null
        }
    }

    /*
     * =========================================================
     * GET USER BY USERNAME
     * =========================================================
     */
    suspend fun getUserByUsername(
        username: String
    ): User? {

        val key =
            usernameKey(
                username
            )

        if (
            key.isBlank()
        ) {

            return null
        }

        val uidSnapshot =
            database
                .getReference(
                    "usernames"
                )
                .child(
                    key
                )
                .get()
                .await()

        val uid =
            uidSnapshot
                .getValue(
                    String::class.java
                )
                ?: return null

        return getUserById(
            uid
        )
    }

    /*
     * =========================================================
     * ALL USERS FLOW
     * =========================================================
     */
    fun getUsersFlow(
        currentUserId: String
    ): Flow<List<User>> =
        callbackFlow {

            val usersRef =
                database
                    .getReference(
                        "users"
                    )

            val listener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot:
                        DataSnapshot
                    ) {

                        val userList =
                            mutableListOf<User>()

                        for (
                        child in
                        snapshot.children
                        ) {

                            val user =
                                child.getValue(
                                    User::class.java
                                )

                            if (
                                user != null &&
                                user.uid !=
                                currentUserId &&
                                user.username.isNotBlank()
                            ) {

                                userList.add(
                                    user
                                )
                            }
                        }

                        trySend(
                            userList
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

            usersRef
                .addValueEventListener(
                    listener
                )

            awaitClose {

                usersRef
                    .removeEventListener(
                        listener
                    )
            }
        }
}