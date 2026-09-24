package com.example.inchat.data.repository

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.inchat.data.model.RecoveryCodeSet
import com.example.inchat.data.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

class UsernameAlreadyTakenException :
    IllegalStateException(
        "That username is already taken"
    )

class UserRepository {

    companion object {

        private const val PROFILE_PHOTO_MAX_BYTES =
            200 * 1024

        private const val PROFILE_PHOTO_PREFIX =
            "data:image/jpeg;base64,"

        private const val PROFILE_PHOTO_MAX_DIMENSION =
            512

        private const val PROFILE_PHOTO_INITIAL_QUALITY =
            80

        private const val PROFILE_PHOTO_MIN_QUALITY =
            45

        /*
         * Short-lived in-memory profile cache used by Home, Chat and
         * search. Firebase disk persistence remains the offline source
         * of truth; this cache prevents repeated identical reads while
         * several Compose screens are alive.
         */
        private const val USER_CACHE_TTL_MS =
            60_000L

        private val userCache =
            ConcurrentHashMap<String, CachedUser>()
    }

    private val database =
        FirebaseDatabase.getInstance()

    private val auth =
        FirebaseAuth.getInstance()

    private data class CachedUser(
        val user: User,
        val cachedAt: Long
    )


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
    ): Result<Unit> {

        val key =
            usernameKey(
                username
            )

        if (
            key.isBlank() ||
            uid.isBlank()
        ) {
            return Result.success(
                Unit
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
                    "Authenticated user does not match username owner."
                )
            )
        }

        return try {

            val usernameRef =
                database
                    .getReference(
                        "usernames"
                    )
                    .child(
                        key
                    )

            val snapshot =
                usernameRef
                    .get()
                    .await()

            val existingUid =
                snapshot.getValue(
                    String::class.java
                )

            if (
                existingUid == uid
            ) {

                usernameRef
                    .removeValue()
                    .await()
            }

            /*
             * The public search index is cleaned separately during
             * account deletion and whenever discoverability changes.
             */
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
     * READ RECEIPT VISIBILITY
     * =========================================================
     */

    fun observeReadReceiptsVisible(
        uid: String
    ): Flow<Boolean> =
        callbackFlow {

            if (
                uid.isBlank()
            ) {

                trySend(true)

                close()

                return@callbackFlow
            }

            val readReceiptRef =
                database
                    .getReference(
                        "users"
                    )
                    .child(
                        uid
                    )
                    .child(
                        "readReceiptsVisible"
                    )

            readReceiptRef.keepSynced(
                true
            )

            val listener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot:
                        DataSnapshot
                    ) {

                        trySend(
                            snapshot.getValue(
                                Boolean::class.java
                            ) ?: true
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

            readReceiptRef
                .addValueEventListener(
                    listener
                )

            awaitClose {

                readReceiptRef
                    .removeEventListener(
                        listener
                    )
            }
        }

    suspend fun getReadReceiptsVisible(
        uid: String
    ): Boolean {

        if (
            uid.isBlank()
        ) {

            return true
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
                    "readReceiptsVisible"
                )
                .get()
                .await()
                .getValue(
                    Boolean::class.java
                ) ?: true

        } catch (
            e: Exception
        ) {

            true
        }
    }

    suspend fun updateReadReceiptsVisible(
        uid: String,
        visible: Boolean
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

        val firebaseUser =
            auth.currentUser

        if (
            firebaseUser == null ||
            firebaseUser.uid != uid
        ) {

            return Result.failure(
                IllegalStateException(
                    "Authenticated user does not match preference owner."
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
                .child(
                    "readReceiptsVisible"
                )
                .setValue(
                    visible
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
     * TYPING INDICATOR VISIBILITY
     * =========================================================
     */

    fun observeTypingIndicatorVisible(
        uid: String
    ): Flow<Boolean> =
        callbackFlow {

            if (
                uid.isBlank()
            ) {

                trySend(true)

                close()

                return@callbackFlow
            }

            val typingVisibilityRef =
                database
                    .getReference(
                        "users"
                    )
                    .child(
                        uid
                    )
                    .child(
                        "typingIndicatorVisible"
                    )

            typingVisibilityRef.keepSynced(
                true
            )

            val listener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot:
                        DataSnapshot
                    ) {

                        trySend(
                            snapshot.getValue(
                                Boolean::class.java
                            ) ?: true
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

            typingVisibilityRef
                .addValueEventListener(
                    listener
                )

            awaitClose {

                typingVisibilityRef
                    .removeEventListener(
                        listener
                    )
            }
        }

    suspend fun getTypingIndicatorVisible(
        uid: String
    ): Boolean {

        if (
            uid.isBlank()
        ) {

            return true
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
                    "typingIndicatorVisible"
                )
                .get()
                .await()
                .getValue(
                    Boolean::class.java
                ) ?: true

        } catch (
            e: Exception
        ) {

            true
        }
    }

    suspend fun updateTypingIndicatorVisible(
        uid: String,
        visible: Boolean
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

        val firebaseUser =
            auth.currentUser

        if (
            firebaseUser == null ||
            firebaseUser.uid != uid
        ) {

            return Result.failure(
                IllegalStateException(
                    "Authenticated user does not match preference owner."
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
                .child(
                    "typingIndicatorVisible"
                )
                .setValue(
                    visible
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
     * USERNAME DISCOVERY VISIBILITY
     * =========================================================
     */

    fun observeDiscoverableByUsername(
        uid: String
    ): Flow<Boolean> =
        callbackFlow {

            if (
                uid.isBlank()
            ) {

                trySend(true)
                close()
                return@callbackFlow
            }

            val discoverableRef =
                database
                    .getReference(
                        "users"
                    )
                    .child(
                        uid
                    )
                    .child(
                        "discoverableByUsername"
                    )

            discoverableRef.keepSynced(
                true
            )

            val listener =
                object :
                    ValueEventListener {

                    override fun onDataChange(
                        snapshot:
                        DataSnapshot
                    ) {

                        trySend(
                            snapshot.getValue(
                                Boolean::class.java
                            ) ?: true
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

            discoverableRef
                .addValueEventListener(
                    listener
                )

            awaitClose {

                discoverableRef
                    .removeEventListener(
                        listener
                    )
            }
        }

    suspend fun getDiscoverableByUsername(
        uid: String
    ): Boolean {

        if (
            uid.isBlank()
        ) {

            return true
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
                    "discoverableByUsername"
                )
                .get()
                .await()
                .getValue(
                    Boolean::class.java
                ) ?: true

        } catch (
            _: Exception
        ) {

            true
        }
    }

    suspend fun updateDiscoverableByUsername(
        uid: String,
        discoverable: Boolean
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

        val firebaseUser =
            auth.currentUser

        if (
            firebaseUser == null ||
            firebaseUser.uid != uid
        ) {

            return Result.failure(
                IllegalStateException(
                    "Authenticated user does not match preference owner."
                )
            )
        }

        return try {

            val userSnapshot =
                database
                    .getReference(
                        "users"
                    )
                    .child(
                        uid
                    )
                    .get()
                    .await()

            val username =
                userSnapshot
                    .child(
                        "username"
                    )
                    .getValue(
                        String::class.java
                    )
                    ?.trim()
                    .orEmpty()

            if (
                username.isBlank()
            ) {
                return Result.failure(
                    IllegalStateException(
                        "User profile is missing a username."
                    )
                )
            }

            database.reference
                .updateChildren(
                    mapOf(
                        "users/$uid/discoverableByUsername" to
                                discoverable
                    )
                )
                .await()

            setPublicUsernameIndex(
                username =
                    username,

                uid =
                    uid,

                discoverable =
                    discoverable
            )

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

            setPublicUsernameIndex(
                username =
                    cleanUsername,

                uid =
                    uid,

                discoverable =
                    true
            )

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
     * SAVE PROFILE PHOTO — NEW EDITOR FLOW
     * =========================================================
     *
     * The ProfilePhotoScreen performs the crop and gives us
     * compressed JPEG bytes.
     *
     * We convert those bytes to Base64 and store them in:
     *
     * users/{uid}/profilePhotoData
     *
     * Firebase Cloud Storage is NOT used.
     */

    suspend fun uploadProfilePhoto(
        uid: String,
        photoBytes: ByteArray
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
            photoBytes.isEmpty()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Profile photo is empty"
                )
            )
        }

        if (
            photoBytes.size >
            PROFILE_PHOTO_MAX_BYTES
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Profile photo is too large after compression. Please choose another image."
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

        return try {

            val base64Data =
                Base64.encodeToString(
                    photoBytes,
                    Base64.NO_WRAP
                )

            val profilePhotoData =
                PROFILE_PHOTO_PREFIX +
                        base64Data

            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )
                .updateChildren(
                    mapOf(
                        "profilePhotoData" to
                                profilePhotoData
                    )
                )
                .await()

            Result.success(
                profilePhotoData
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
     * LEGACY PICKER COMPATIBILITY
     * =========================================================
     *
     * This keeps the existing ProfileScreen compiling until
     * we replace it with the new editor navigation.
     *
     * It also automatically compresses the selected image.
     */

    suspend fun uploadProfilePhoto(
        uid: String,
        imageUri: Uri,
        contentResolver: ContentResolver,
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
            !imageUri.toString().isNotBlank()
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Invalid image"
                )
            )
        }

        if (
            mimeType != null &&
            !mimeType.startsWith(
                "image/",
                ignoreCase = true
            )
        ) {

            return Result.failure(
                IllegalArgumentException(
                    "Please select an image."
                )
            )
        }

        return try {

            val bitmap =
                decodeProfileBitmap(
                    contentResolver =
                        contentResolver,

                    imageUri =
                        imageUri
                )
                    ?: return Result.failure(
                        IllegalArgumentException(
                            "Could not read the selected image."
                        )
                    )

            val compressedBytes =
                compressProfileBitmap(
                    bitmap
                )

            bitmap.recycle()

            if (
                compressedBytes == null
            ) {

                return Result.failure(
                    IllegalArgumentException(
                        "Could not compress the profile photo."
                    )
                )
            }

            uploadProfilePhoto(
                uid =
                    uid,

                photoBytes =
                    compressedBytes
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
     * REMOVE PROFILE PHOTO
     * =========================================================
     */

    suspend fun removeProfilePhoto(
        uid: String
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

        return try {

            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )
                .child(
                    "profilePhotoData"
                )
                .removeValue()
                .await()

            /*
             * Do not remove profilePhotoUrl here.
             *
             * It may belong to an older account version and
             * is intentionally retained for compatibility.
             */

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
     * DECODE PROFILE IMAGE
     * =========================================================
     */

    private fun decodeProfileBitmap(
        contentResolver: ContentResolver,
        imageUri: Uri
    ): Bitmap? {

        val bounds =
            BitmapFactory.Options().apply {

                inJustDecodeBounds =
                    true
            }

        try {

            contentResolver
                .openInputStream(
                    imageUri
                )
                ?.use { inputStream ->

                    BitmapFactory.decodeStream(
                        inputStream,
                        null,
                        bounds
                    )
                }

        } catch (
            _: Exception
        ) {

            return null
        }

        if (
            bounds.outWidth <= 0 ||
            bounds.outHeight <= 0
        ) {

            return null
        }

        var sampleSize =
            1

        while (
            bounds.outWidth /
            sampleSize >
            2048 ||
            bounds.outHeight /
            sampleSize >
            2048
        ) {

            sampleSize *=
                2
        }

        val options =
            BitmapFactory.Options().apply {

                inSampleSize =
                    sampleSize

                inPreferredConfig =
                    Bitmap.Config.ARGB_8888
            }

        return try {

            contentResolver
                .openInputStream(
                    imageUri
                )
                ?.use { inputStream ->

                    BitmapFactory.decodeStream(
                        inputStream,
                        null,
                        options
                    )
                }

        } catch (
            _: Exception
        ) {

            null
        }
    }

    /*
     * =========================================================
     * COMPRESS PROFILE BITMAP
     * =========================================================
     */

    private fun compressProfileBitmap(
        bitmap: Bitmap
    ): ByteArray? {

        val maxDimension =
            max(
                bitmap.width,
                bitmap.height
            )

        val outputBitmap =
            if (
                maxDimension >
                PROFILE_PHOTO_MAX_DIMENSION
            ) {

                val scale =
                    PROFILE_PHOTO_MAX_DIMENSION /
                            maxDimension.toFloat()

                Bitmap.createScaledBitmap(

                    bitmap,

                    (
                            bitmap.width *
                                    scale
                            )
                        .toInt()
                        .coerceAtLeast(
                            1
                        ),

                    (
                            bitmap.height *
                                    scale
                            )
                        .toInt()
                        .coerceAtLeast(
                            1
                        ),

                    true
                )

            } else {

                bitmap
            }

        var quality =
            PROFILE_PHOTO_INITIAL_QUALITY

        var result =
            compressJpeg(
                outputBitmap,
                quality
            )

        while (
            result != null &&
            result.size >
            PROFILE_PHOTO_MAX_BYTES &&
            quality >
            PROFILE_PHOTO_MIN_QUALITY
        ) {

            quality -=
                5

            result =
                compressJpeg(
                    outputBitmap,
                    quality
                )
        }

        if (
            outputBitmap !== bitmap
        ) {

            outputBitmap.recycle()
        }

        return if (
            result != null &&
            result.size <=
            PROFILE_PHOTO_MAX_BYTES
        ) {

            result

        } else {

            null
        }
    }

    /*
     * =========================================================
     * BITMAP → JPEG
     * =========================================================
     */

    private fun compressJpeg(
        bitmap: Bitmap,
        quality: Int
    ): ByteArray? {

        return try {

            ByteArrayOutputStream()
                .use { outputStream ->

                    val success =
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            quality,
                            outputStream
                        )

                    if (
                        !success
                    ) {

                        null

                    } else {

                        outputStream.toByteArray()
                    }
                }

        } catch (
            _: Exception
        ) {

            null
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

        val firebaseUser =
            auth.currentUser

        if (
            firebaseUser == null ||
            firebaseUser.uid != uid
        ) {
            return Result.failure(
                IllegalStateException(
                    "Authenticated user does not match account owner."
                )
            )
        }

        return try {

            val usernameKey =
                usernameKey(
                    username
                )

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
                "privateUsers/$uid"
            ] =
                null

            updates[
                "recoveryCodes/$uid"
            ] =
                null

            if (
                usernameKey.isNotBlank()
            ) {
                updates[
                    "usernames/$usernameKey"
                ] =
                    null

                updates[
                    "publicUsernames/$usernameKey"
                ] =
                    null
            }

            for (
            child in blockedSnapshot.children
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
            child in userChatsSnapshot.children
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

            /*
             * Delete all account-owned database data in one
             * multi-location update. This prevents the old
             * "release username first" partial-delete state.
             */
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

            userCache.remove(
                uid
            )

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
                    "privateUsers"
                )
                .child(
                    uid
                )
                .child(
                    "fcmToken"
                )
                .setValue(
                    token
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
                    "privateUsers"
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

        val userRef =
            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )

        userRef.keepSynced(
            true
        )

        val snapshot =
            userRef
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

    fun getCachedUser(
        uid: String
    ): User? {

        if (
            uid.isBlank()
        ) {
            return null
        }

        val cached =
            userCache[uid]
                ?: return null

        val now =
            System.currentTimeMillis()

        if (
            now - cached.cachedAt >=
            USER_CACHE_TTL_MS
        ) {
            userCache.remove(
                uid,
                cached
            )
            return null
        }

        return cached.user
    }

    suspend fun getUserByIdFast(
        uid: String
    ): User? {

        if (
            uid.isBlank()
        ) {
            return null
        }

        getCachedUser(uid)?.let { cached ->
            return cached
        }

        val userRef =
            database
                .getReference(
                    "users"
                )
                .child(
                    uid
                )

        /*
         * A single-value listener is used instead of get().await().
         *
         * Realtime Database can satisfy this listener from the local
         * persisted snapshot when available, while the Firebase
         * persistence layer continues to refresh the value in the
         * background. This keeps Home/Profile reads from waiting on a
         * server round trip when the device already knows the user.
         */
        return try {

            val user =
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
                    .getValue(
                        User::class.java
                    )

            if (
                user != null
            ) {

                userCache[uid] =
                    CachedUser(
                        user = user,
                        cachedAt =
                            System.currentTimeMillis()
                    )
            }

            user

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

        val usernameRef =
            database
                .getReference(
                    "usernames"
                )
                .child(
                    key
                )

        usernameRef.keepSynced(
            true
        )

        val uidSnapshot =
            usernameRef
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
     * LIVE USERNAME PREFIX SEARCH
     * =========================================================
     *
     * Usernames are indexed in the separate "usernames" node
     * using lowercase keys. Querying that node by key gives us
     * efficient case-insensitive prefix suggestions without
     * loading the entire users collection into the device.
     */

    suspend fun searchUsersByUsernamePrefix(
        prefix: String,
        currentUserId: String,
        limit: Int = 8
    ): List<User> {

        val key =
            usernameKey(
                prefix
                    .trim()
                    .removePrefix("@")
            )

        if (
            key.isBlank() ||
            currentUserId.isBlank()
        ) {
            return emptyList()
        }

        val safeLimit =
            limit
                .coerceIn(
                    1,
                    20
                )

        return try {

            /*
             * The "usernames" node is the authoritative username index
             * created when an account claims its username. Use it for
             * prefix search so existing accounts remain discoverable
             * even if the newer publicUsernames mirror was never created.
             */
            val snapshot =
                database
                    .getReference(
                        "usernames"
                    )
                    .orderByKey()
                    .startAt(
                        key
                    )
                    .endAt(
                        key + ""
                    )
                    .limitToFirst(
                        safeLimit
                    )
                    .get()
                    .await()

            val candidateIds =
                snapshot.children
                    .mapNotNull { child ->
                        child.getValue(
                            String::class.java
                        )
                    }
                    .filter {
                        it.isNotBlank() &&
                                it != currentUserId
                    }

            val candidateUsers =
                coroutineScope {
                    candidateIds
                        .map { uid ->
                            async {
                                runCatching {
                                    getUserByIdFast(
                                        uid
                                    )
                                }.getOrNull()
                            }
                        }
                        .awaitAll()
                }

            candidateUsers
                .filter { user ->
                    user != null &&
                            user.discoverableByUsername &&
                            user.username.isNotBlank() &&
                            user.username
                                .trim()
                                .lowercase(
                                    Locale.ROOT
                                )
                                .startsWith(
                                    key
                                )
                }
                .map { user ->
                    user!!
                }
                .sortedBy {
                    it.username.lowercase(
                        Locale.ROOT
                    )
                }

        } catch (
            e: Exception
        ) {

            throw e
        }
    }

    /*
     * =========================================================
     * PUBLIC USERNAME SEARCH INDEX
     * =========================================================
     *
     * Only accounts that opt in to username discovery are
     * represented in this public index.
     */
    private suspend fun setPublicUsernameIndex(
        username: String,
        uid: String,
        discoverable: Boolean
    ) {

        val key =
            usernameKey(
                username
            )

        if (
            key.isBlank() ||
            uid.isBlank()
        ) {
            return
        }

        val publicRef =
            database
                .getReference(
                    "publicUsernames"
                )
                .child(
                    key
                )

        if (
            discoverable
        ) {

            publicRef
                .setValue(
                    uid
                )
                .await()

        } else {

            publicRef
                .removeValue()
                .await()
        }
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

            usersRef.keepSynced(
                true
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
                        child in snapshot.children
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