package com.example.inchat.ui.auth

import android.app.Application
import android.util.Log
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.data.repository.UsernameAlreadyTakenException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

sealed interface AuthUiState {

    data object Loading : AuthUiState

    data object LoggedOut : AuthUiState

    data class LoggedIn(
        val uid: String,
        val username: String
    ) : AuthUiState

    data class NeedsUsernameSetup(
        val uid: String,
        val email: String,
        val suggestedUsername: String
    ) : AuthUiState

    data class NeedsEmailVerification(
        val uid: String,
        val email: String
    ) : AuthUiState
}

class AuthViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val auth =
        FirebaseAuth.getInstance()

    private val userRepository =
        UserRepository()

    private val messaging =
        FirebaseMessaging.getInstance()

    private val _uiState =
        MutableStateFlow<AuthUiState>(
            AuthUiState.Loading
        )

    val uiState:
            StateFlow<AuthUiState> =
        _uiState.asStateFlow()

    private val _username =
        MutableStateFlow("")

    val username:
            StateFlow<String> =
        _username.asStateFlow()

    private val _displayName =
        MutableStateFlow("")

    val displayName:
            StateFlow<String> =
        _displayName.asStateFlow()

    private val _bio =
        MutableStateFlow("")

    val bio:
            StateFlow<String> =
        _bio.asStateFlow()

    private val _currentUserId =
        MutableStateFlow("")

    val currentUserId:
            StateFlow<String> =
        _currentUserId.asStateFlow()

    init {
        bootstrap()
    }

    /*
     * =========================================================
     * PROFILE STATE
     * =========================================================
     */
    private fun applyUserProfile(
        uid: String,
        username: String,
        displayName: String,
        bio: String
    ) {

        _currentUserId.value =
            uid

        _username.value =
            username

        _displayName.value =
            displayName.ifBlank {
                username
            }

        _bio.value =
            bio
    }

    private fun applyUserProfile(
        uid: String,
        username: String
    ) {

        applyUserProfile(
            uid =
                uid,

            username =
                username,

            displayName =
                username,

            bio =
                ""
        )
    }

    /*
     * =========================================================
     * AUTHENTICATION BOOTSTRAP
     * =========================================================
     */
    private fun bootstrap() {

        val firebaseUser =
            auth.currentUser

        if (
            firebaseUser == null
        ) {

            viewModelScope.launch {

                try {

                    kotlinx.coroutines
                        .yield()

                    val restoredUser =
                        auth.currentUser

                    if (
                        restoredUser == null
                    ) {

                        _uiState.value =
                            AuthUiState.LoggedOut

                        return@launch
                    }

                    initializeAuthenticatedSession(
                        restoredUser
                    )

                } catch (e: Exception) {

                    Log.e(
                        "AuthViewModel",
                        "Authentication bootstrap failed",
                        e
                    )

                    _uiState.value =
                        AuthUiState.LoggedOut
                }
            }

            return
        }

        initializeAuthenticatedSession(
            firebaseUser
        )
    }

    private fun initializeAuthenticatedSession(
        firebaseUser: FirebaseUser
    ) {
        val uid =
            firebaseUser.uid

        val email =
            firebaseUser.email

        if (
            uid.isBlank()
        ) {

            _uiState.value =
                AuthUiState.LoggedOut

            return
        }

        if (
            !isGoogleUser(firebaseUser) &&
            firebaseUser.email?.endsWith("@inchat.app", ignoreCase = true) == true
        ) {
            auth.signOut()
            clearLocalAuthState()
            return
        }

        if (
            !isGoogleUser(firebaseUser) &&
            !firebaseUser.isEmailVerified
        ) {
            _currentUserId.value = uid
            _uiState.value =
                AuthUiState.NeedsEmailVerification(
                    uid = uid,
                    email = email.orEmpty()
                )
            return
        }

        viewModelScope.launch {

            try {

                val user =
                    userRepository
                        .getUserByIdFast(
                            uid
                        )

                if (
                    user != null &&
                    user.username.isNotBlank()
                ) {

                    applyUserProfile(
                        uid =
                            user.uid,

                        username =
                            user.username,

                        displayName =
                            user.displayName,

                        bio =
                            user.bio
                    )

                    _uiState.value =
                        AuthUiState.LoggedIn(
                            uid =
                                user.uid,

                            username =
                                user.username
                        )

                    registerFcmToken(
                        user.uid
                    )

                    refreshUserProfile(
                        user.uid
                    )

                } else if (
                    isGoogleUser(firebaseUser)
                ) {

                    _currentUserId.value =
                        uid

                    _uiState.value =
                        AuthUiState.NeedsUsernameSetup(
                            uid =
                                uid,

                            email =
                                email.orEmpty(),

                            suggestedUsername =
                                suggestedGoogleUsername(
                                    firebaseUser
                                )
                        )

                } else {

                    _uiState.value =
                        AuthUiState.LoggedOut
                }

            } catch (e: Exception) {

                Log.e(
                    "AuthViewModel",
                    "Fallback profile loading failed",
                    e
                )

                _uiState.value =
                    AuthUiState.LoggedOut
            }
        }
    }

    private fun isGoogleUser(
        firebaseUser: FirebaseUser
    ): Boolean {

        return firebaseUser.providerData.any {
            it.providerId ==
                    GoogleAuthProvider.PROVIDER_ID
        }
    }

    private fun suggestedGoogleUsername(
        firebaseUser: FirebaseUser
    ): String {

        val source =
            firebaseUser.email
                ?.substringBefore("@")
                ?.lowercase()
                ?: firebaseUser.displayName
                    ?.lowercase()
                    .orEmpty()

        val candidate =
            source
                .replace(
                    Regex("[^a-z0-9_.]"),
                    "_"
                )
                .trim(
                    '_',
                    '.'
                )
                .take(20)

        return if (
            candidate.length >= 3 &&
            candidate.matches(
                Regex("^[a-z0-9_.]{3,20}$")
            )
        ) {
            candidate
        } else {
            "inchatuser"
        }
    }

    /*
     * =========================================================
     * BACKGROUND PROFILE REFRESH
     * =========================================================
     */
    private fun refreshUserProfile(
        uid: String
    ) {

        if (
            uid.isBlank()
        ) {

            return
        }

        viewModelScope.launch {

            try {

                val cachedUser =
                    userRepository
                        .getUserByIdFast(
                            uid
                        )

                if (
                    cachedUser != null &&
                    cachedUser.username.isNotBlank()
                ) {

                    applyUserProfile(
                        uid =
                            cachedUser.uid,

                        username =
                            cachedUser.username,

                        displayName =
                            cachedUser.displayName,

                        bio =
                            cachedUser.bio
                    )

                    _uiState.value =
                        AuthUiState.LoggedIn(
                            uid =
                                cachedUser.uid,

                            username =
                                cachedUser.username
                        )
                }

                try {

                    val serverUser =
                        userRepository
                            .getUserById(
                                uid
                            )

                    if (
                        serverUser == null ||
                        serverUser.username.isBlank()
                    ) {

                        Log.w(
                            "AuthViewModel",
                            "Authenticated account has no valid user profile"
                        )

                        auth.signOut()

                        clearLocalAuthState()

                        return@launch
                    }

                    applyUserProfile(
                        uid =
                            serverUser.uid,

                        username =
                            serverUser.username,

                        displayName =
                            serverUser.displayName,

                        bio =
                            serverUser.bio
                    )

                    _uiState.value =
                        AuthUiState.LoggedIn(
                            uid =
                                serverUser.uid,

                            username =
                                serverUser.username
                        )

                } catch (e: Exception) {

                    Log.w(
                        "AuthViewModel",
                        "Server profile refresh failed; keeping existing session",
                        e
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    "AuthViewModel",
                    "Background profile refresh failed",
                    e
                )
            }
        }
    }

    /*
     * =========================================================
     * SYNC SAVED PROFILE STATE
     * =========================================================
     */
    fun applySavedProfileState(
        displayName: String,
        bio: String
    ) {

        _displayName.value =
            displayName
                .trim()
                .ifBlank {
                    _username.value
                }

        _bio.value =
            bio.trim()
    }

    /*
     * =========================================================
     * UPDATE DISPLAY NAME + BIO
     * =========================================================
     */
    fun updateProfile(
        rawDisplayName: String,
        rawBio: String,
        onResult:
            (Boolean, String?) -> Unit
    ) {

        val uid =
            _currentUserId.value

        if (
            uid.isBlank()
        ) {

            onResult(
                false,
                "You are not logged in."
            )

            return
        }

        val cleanDisplayName =
            rawDisplayName.trim()

        val cleanBio =
            rawBio.trim()

        if (
            cleanDisplayName.length > 30
        ) {

            onResult(
                false,
                "Display name must be 30 characters or less."
            )

            return
        }

        if (
            cleanBio.length > 160
        ) {

            onResult(
                false,
                "Bio must be 160 characters or less."
            )

            return
        }

        viewModelScope.launch {

            userRepository
                .updateProfile(
                    uid =
                        uid,

                    displayName =
                        cleanDisplayName,

                    bio =
                        cleanBio
                )
                .onSuccess {

                    _displayName.value =
                        cleanDisplayName
                            .ifBlank {
                                _username.value
                            }

                    _bio.value =
                        cleanBio

                    onResult(
                        true,
                        null
                    )
                }
                .onFailure { error ->

                    Log.e(
                        "AuthViewModel",
                        "Profile update failed",
                        error
                    )

                    onResult(
                        false,
                        error.message
                            ?: "Could not update your profile."
                    )
                }
        }
    }

    /*
     * =========================================================
     * DELETE ACCOUNT
     * =========================================================
     */
    fun usesGoogleSignIn(): Boolean {

        return auth.currentUser
            ?.providerData
            ?.any {
                it.providerId ==
                        GoogleAuthProvider.PROVIDER_ID
            } == true
    }

    fun resendEmailVerification(
        onResult: (Boolean, String?) -> Unit
    ) {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null) {
            onResult(false, "Your sign-in session has expired. Please log in again.")
            return
        }

        viewModelScope.launch {
            try {
                firebaseUser.sendEmailVerification().await()
                onResult(true, "A new verification email has been sent.")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Email verification resend failed", e)
                onResult(false, "Could not resend the verification email. Please try again later.")
            }
        }
    }

    fun checkEmailVerification(
        onResult: (Boolean, String?) -> Unit
    ) {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null) {
            onResult(false, "Your sign-in session has expired. Please log in again.")
            return
        }

        viewModelScope.launch {
            try {
                firebaseUser.reload().await()
                val refreshedUser = auth.currentUser ?: firebaseUser

                if (refreshedUser.isEmailVerified) {
                    initializeAuthenticatedSession(refreshedUser)
                    onResult(true, null)
                } else {
                    onResult(false, "The email is not verified yet. Open the link in your inbox, then check again.")
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Email verification check failed", e)
                onResult(false, "Could not check verification status. Please try again.")
            }
        }
    }

    fun sendPasswordResetEmail(
        rawEmail: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val cleanEmail = rawEmail.trim().lowercase(Locale.ROOT)
        if (
            cleanEmail.length > 254 ||
            !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()
        ) {
            onResult(false, "Enter a valid email address.")
            return
        }

        viewModelScope.launch {
            try {
                auth.sendPasswordResetEmail(cleanEmail).await()
                onResult(
                    true,
                    "If an InChat account uses that email address, a password-reset link has been sent. Check your inbox and spam folder."
                )
            } catch (e: Exception) {
                val errorText = e.message.orEmpty().lowercase(Locale.ROOT)
                if (
                    errorText.contains("user-not-found") ||
                    errorText.contains("no user record") ||
                    errorText.contains("no user found")
                ) {
                    // Keep the response indistinguishable from a successful request.
                    onResult(
                        true,
                        "If an InChat account uses that email address, a password-reset link has been sent. Check your inbox and spam folder."
                    )
                } else {
                    Log.e("AuthViewModel", "Password reset email request failed", e)
                    onResult(false, "Could not send the reset email. Please try again later.")
                }
            }
        }
    }

    fun deleteAccount(
        password: String,
        onResult:
            (Boolean, String?) -> Unit
    ) {

        val firebaseUser =
            auth.currentUser

        val uid =
            _currentUserId.value

        val currentUsername =
            _username.value

        if (
            firebaseUser == null ||
            uid.isBlank() ||
            currentUsername.isBlank()
        ) {

            onResult(
                false,
                "You are not logged in."
            )

            return
        }

        if (usesGoogleSignIn()) {
            onResult(
                false,
                "Confirm account deletion with Google."
            )
            return
        }

        val email =
            firebaseUser.email

        if (email.isNullOrBlank()) {
            onResult(
                false,
                "This account cannot be deleted from the app."
            )
            return
        }

        val cleanPassword =
            password.trim()

        if (cleanPassword.isBlank()) {
            onResult(
                false,
                "Enter your password."
            )
            return
        }

        viewModelScope.launch {

            try {

                val credential =
                    EmailAuthProvider
                        .getCredential(
                            email,
                            cleanPassword
                        )

                firebaseUser
                    .reauthenticate(
                        credential
                    )
                    .await()

                deleteAccountDataAndAuth(
                    firebaseUser =
                        firebaseUser,
                    uid =
                        uid,
                    username =
                        currentUsername
                )

                onResult(
                    true,
                    null
                )

            } catch (e: Exception) {

                reportAccountDeletionFailure(
                    e,
                    onResult
                )
            }
        }
    }

    fun deleteAccountWithGoogle(
        idToken: String,
        onResult:
            (Boolean, String?) -> Unit
    ) {

        val firebaseUser =
            auth.currentUser

        val uid =
            _currentUserId.value

        val currentUsername =
            _username.value

        if (
            firebaseUser == null ||
            uid.isBlank() ||
            currentUsername.isBlank()
        ) {
            onResult(
                false,
                "You are not logged in."
            )
            return
        }

        if (!usesGoogleSignIn()) {
            onResult(
                false,
                "This account does not use Google sign-in."
            )
            return
        }

        if (idToken.isBlank()) {
            onResult(
                false,
                "Google did not return a valid sign-in token."
            )
            return
        }

        viewModelScope.launch {

            try {

                val credential =
                    GoogleAuthProvider
                        .getCredential(
                            idToken,
                            null
                        )

                firebaseUser
                    .reauthenticate(
                        credential
                    )
                    .await()

                deleteAccountDataAndAuth(
                    firebaseUser =
                        firebaseUser,
                    uid =
                        uid,
                    username =
                        currentUsername
                )

                onResult(
                    true,
                    null
                )

            } catch (e: Exception) {

                reportAccountDeletionFailure(
                    e,
                    onResult
                )
            }
        }
    }

    private suspend fun deleteAccountDataAndAuth(
        firebaseUser: FirebaseUser,
        uid: String,
        username: String
    ) {

        val deleteDataResult =
            userRepository
                .deleteAccountData(
                    uid =
                        uid,
                    username =
                        username
                )

        if (deleteDataResult.isFailure) {
            throw deleteDataResult.exceptionOrNull()
                ?: IllegalStateException(
                    "Could not delete your account data."
                )
        }

        firebaseUser
            .delete()
            .await()

        clearLocalAuthState()
    }

    private fun reportAccountDeletionFailure(
        error: Exception,
        onResult: (Boolean, String?) -> Unit
    ) {

        Log.e(
            "AuthViewModel",
            "Account deletion failed",
            error
        )

        val message =
            when {
                error.message
                    ?.contains(
                        "password",
                        ignoreCase = true
                    ) == true ->
                    "Incorrect password."

                error.message
                    ?.contains(
                        "recent",
                        ignoreCase = true
                    ) == true ->
                    "Please sign in again and try deleting the account."

                else ->
                    error.message
                        ?: "Could not delete your account."
            }

        onResult(
            false,
            message
        )
    }

    /*
     * =========================================================
     * GOOGLE SIGN-IN
     * =========================================================
     */
    fun signInWithGoogle(
        idToken: String,
        onResult: (String?) -> Unit
    ) {

        if (idToken.isBlank()) {
            onResult(
                "Google did not return a valid sign-in token."
            )
            return
        }

        viewModelScope.launch {

            try {

                val credential =
                    GoogleAuthProvider
                        .getCredential(
                            idToken,
                            null
                        )

                val authResult =
                    auth
                        .signInWithCredential(
                            credential
                        )
                        .await()

                val firebaseUser =
                    authResult.user
                        ?: auth.currentUser
                        ?: throw IllegalStateException(
                            "Firebase did not return a signed-in user."
                        )

                val savedUser =
                    userRepository
                        .getUserById(
                            firebaseUser.uid
                        )

                if (
                    savedUser != null &&
                    savedUser.username.isNotBlank()
                ) {

                    applyUserProfile(
                        uid =
                            savedUser.uid,
                        username =
                            savedUser.username,
                        displayName =
                            savedUser.displayName,
                        bio =
                            savedUser.bio
                    )

                    _uiState.value =
                        AuthUiState.LoggedIn(
                            uid =
                                savedUser.uid,
                            username =
                                savedUser.username
                        )

                    registerFcmToken(
                        savedUser.uid
                    )

                } else {

                    _currentUserId.value =
                        firebaseUser.uid

                    _username.value =
                        ""

                    _displayName.value =
                        ""

                    _bio.value =
                        ""

                    _uiState.value =
                        AuthUiState.NeedsUsernameSetup(
                            uid =
                                firebaseUser.uid,
                            email =
                                firebaseUser.email.orEmpty(),
                            suggestedUsername =
                                suggestedGoogleUsername(
                                    firebaseUser
                                )
                        )
                }

                onResult(null)

            } catch (e: Exception) {

                Log.e(
                    "AuthViewModel",
                    "Google sign-in failed",
                    e
                )

                onResult(
                    e.message
                        ?: "Google sign-in failed. Please try again."
                )
            }
        }
    }

    fun completeGoogleRegistration(
        rawUsername: String,
        onResult: (String?) -> Unit
    ) {

        val pending =
            _uiState.value as?
                    AuthUiState.NeedsUsernameSetup

        if (pending == null) {
            onResult(
                "No Google account is waiting for username setup."
            )
            return
        }

        val firebaseUser =
            auth.currentUser

        if (
            firebaseUser == null ||
            firebaseUser.uid != pending.uid
        ) {
            onResult(
                "Your Google session has expired. Please sign in again."
            )
            return
        }

        viewModelScope.launch {

            val username =
                rawUsername.trim()

            if (
                !username.matches(
                    Regex("^[a-zA-Z0-9_.]{3,20}$")
                )
            ) {
                onResult(
                    "Username must be 3-20 characters: letters, numbers, _ or ."
                )
                return@launch
            }

            try {

                val claimResult =
                    userRepository
                        .claimUsername(
                            username =
                                username,
                            uid =
                                pending.uid
                        )

                if (claimResult.isFailure) {
                    val claimError =
                        claimResult.exceptionOrNull()

                    onResult(
                        if (claimError is UsernameAlreadyTakenException) {
                            "That username is already taken."
                        } else {
                            claimError?.message
                                ?: "Could not reserve that username. Please try again."
                        }
                    )
                    return@launch
                }

                val saveResult =
                    userRepository
                        .saveUser(
                            uid =
                                pending.uid,
                            username =
                                username,
                            displayName =
                                firebaseUser.displayName
                                    ?.trim()
                                    .orEmpty()
                                    .ifBlank {
                                        username
                                    },
                            bio =
                                ""
                        )

                if (saveResult.isFailure) {

                    val persistedUser =
                        runCatching {
                            userRepository.getUserById(
                                pending.uid
                            )
                        }.getOrNull()

                    if (
                        persistedUser == null ||
                        persistedUser.username.isBlank()
                    ) {
                        userRepository.releaseUsername(
                            username,
                            pending.uid
                        )
                    }

                    onResult(
                        saveResult.exceptionOrNull()?.message
                            ?: "Could not finish your InChat profile. Please try again."
                    )
                    return@launch
                }

                val displayName =
                    firebaseUser.displayName
                        ?.trim()
                        .orEmpty()
                        .ifBlank {
                            username
                        }

                applyUserProfile(
                    uid =
                        pending.uid,
                    username =
                        username,
                    displayName =
                        displayName,
                    bio =
                        ""
                )

                _uiState.value =
                    AuthUiState.LoggedIn(
                        uid =
                            pending.uid,
                        username =
                            username
                    )

                registerFcmToken(
                    pending.uid
                )

                onResult(null)

            } catch (e: Exception) {

                Log.e(
                    "AuthViewModel",
                    "Google profile setup failed",
                    e
                )

                onResult(
                    e.message
                        ?: "Could not create your InChat profile. Please try again."
                )
            }
        }
    }

    fun cancelGoogleRegistration() {

        auth.signOut()

        clearLocalAuthState()
    }

    /*
     * =========================================================
     * FCM TOKEN
     * =========================================================
     */
    private fun registerFcmToken(
        uid: String
    ) {

        if (
            uid.isBlank()
        ) {

            return
        }

        viewModelScope.launch {

            try {

                val token =
                    messaging
                        .token
                        .await()

                userRepository
                    .saveFcmToken(
                        uid =
                            uid,

                        token =
                            token
                    )
                    .onFailure { error ->

                        Log.e(
                            "AuthViewModel",
                            "Failed to save FCM token",
                            error
                        )
                    }

            } catch (e: Exception) {

                Log.e(
                    "AuthViewModel",
                    "Could not obtain FCM token",
                    e
                )
            }
        }
    }

    /*
     * =========================================================
     * REGISTRATION
     * =========================================================
     */
    fun register(
        rawEmail: String,
        rawUsername: String,
        password: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            val email = rawEmail.trim().lowercase(Locale.ROOT)
            val username = rawUsername.trim()

            if (
                email.length > 254 ||
                !Patterns.EMAIL_ADDRESS.matcher(email).matches()
            ) {
                onResult("Enter a valid email address.")
                return@launch
            }

            if (
                !username.matches(
                    Regex("^[a-zA-Z0-9_.]{3,20}$")
                )
            ) {
                onResult("Username must be 3-20 characters: letters, numbers, _ or .")
                return@launch
            }

            if (password.length < 6) {
                onResult("Password must be at least 6 characters.")
                return@launch
            }

            var createdUid: String? = null
            var usernameClaimed = false

            try {
                val authResult =
                    auth.createUserWithEmailAndPassword(
                        email,
                        password
                    ).await()

                val firebaseUser =
                    authResult.user
                        ?: throw IllegalStateException(
                            "Firebase did not return a user."
                        )

                val uid = firebaseUser.uid
                createdUid = uid

                val claimResult =
                    userRepository.claimUsername(
                        username = username,
                        uid = uid
                    )

                if (claimResult.isFailure) {
                    val error = claimResult.exceptionOrNull()

                    rollbackRegistration(
                        username = username,
                        uid = uid,
                        usernameClaimed = false
                    )

                    onResult(
                        if (error is UsernameAlreadyTakenException) {
                            "That username is already taken."
                        } else {
                            "Could not reserve that username. Please try again."
                        }
                    )
                    return@launch
                }

                usernameClaimed = true

                val saveResult =
                    userRepository.saveUser(
                        uid = uid,
                        username = username,
                        displayName = username,
                        bio = ""
                    )

                if (saveResult.isFailure) {
                    rollbackRegistration(
                        username = username,
                        uid = uid,
                        usernameClaimed = usernameClaimed
                    )
                    onResult("Could not finish account creation. Please try again.")
                    return@launch
                }

                applyUserProfile(
                    uid = uid,
                    username = username,
                    displayName = username,
                    bio = ""
                )

                _currentUserId.value = uid
                _uiState.value =
                    AuthUiState.NeedsEmailVerification(
                        uid = uid,
                        email = email
                    )

                try {
                    firebaseUser.sendEmailVerification().await()
                } catch (emailError: Exception) {
                    Log.e(
                        "AuthViewModel",
                        "Initial email verification message could not be sent",
                        emailError
                    )
                    onResult(
                        "Your account was created, but the verification email could not be sent. Use Resend on the next screen."
                    )
                    return@launch
                }

                onResult(null)
            } catch (e: FirebaseAuthUserCollisionException) {
                onResult("An account with this email address already exists.")
            } catch (e: FirebaseAuthWeakPasswordException) {
                onResult("Password is too weak. Use a stronger password.")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Registration failed", e)

                val uid = createdUid
                if (uid != null) {
                    rollbackRegistration(
                        username = username,
                        uid = uid,
                        usernameClaimed = usernameClaimed
                    )
                }

                onResult("Registration failed. Please try again.")
            }
        }
    }

    /*
     * =========================================================
     * COMPLETE REGISTRATION
     * =========================================================
     */
    fun completeRegistration() {

        val uid =
            _currentUserId.value

        val currentUsername =
            _username.value

        if (
            uid.isBlank() ||
            currentUsername.isBlank()
        ) {

            return
        }

        _uiState.value =
            AuthUiState.LoggedIn(
                uid =
                    uid,

                username =
                    currentUsername
            )
    }

        /*
     * =========================================================
     * LOGIN
     * =========================================================
     */
    fun login(
        rawEmail: String,
        password: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            val email = rawEmail.trim().lowercase(Locale.ROOT)

            if (email.isBlank() || password.isBlank()) {
                onResult("Enter your email address and password.")
                return@launch
            }

            if (
                email.length > 254 ||
                !Patterns.EMAIL_ADDRESS.matcher(email).matches()
            ) {
                onResult("Enter a valid email address.")
                return@launch
            }

            if (email.endsWith("@inchat.app", ignoreCase = true)) {
                onResult("That is an old InChat test account. Create a new account with a real email address.")
                return@launch
            }

            try {
                val result =
                    auth.signInWithEmailAndPassword(
                        email,
                        password
                    ).await()

                val firebaseUser =
                    result.user
                        ?: auth.currentUser
                        ?: throw IllegalStateException(
                            "Firebase did not return a signed-in user."
                        )

                if (
                    !isGoogleUser(firebaseUser) &&
                    !firebaseUser.isEmailVerified
                ) {
                    _currentUserId.value = firebaseUser.uid
                    _uiState.value =
                        AuthUiState.NeedsEmailVerification(
                            uid = firebaseUser.uid,
                            email = firebaseUser.email.orEmpty()
                        )
                    onResult(null)
                    return@launch
                }

                val uid = firebaseUser.uid
                val user =
                    userRepository.getUserById(uid)

                if (user == null || user.username.isBlank()) {
                    auth.signOut()
                    clearLocalAuthState()
                    onResult(
                        "This account is incomplete. Please create a new account or contact support."
                    )
                    return@launch
                }

                applyUserProfile(
                    uid = uid,
                    username = user.username,
                    displayName = user.displayName,
                    bio = user.bio
                )

                _uiState.value =
                    AuthUiState.LoggedIn(
                        uid = uid,
                        username = user.username
                    )

                registerFcmToken(uid)
                onResult(null)
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Login failed", e)
                onResult("Invalid email address or password.")
            }
        }
    }

    /*
     * =========================================================
     * LOGOUT
     * =========================================================
     */
    fun logout() {

        val uid =
            _currentUserId.value

        if (
            uid.isNotBlank()
        ) {

            viewModelScope.launch {

                userRepository
                    .clearFcmToken(
                        uid
                    )
            }
        }

        auth.signOut()

        clearLocalAuthState()
    }

    /*
     * =========================================================
     * CLEAR LOCAL STATE
     * =========================================================
     */
    private fun clearLocalAuthState() {

        _username.value =
            ""

        _displayName.value =
            ""

        _bio.value =
            ""

        _currentUserId.value =
            ""

        _uiState.value =
            AuthUiState.LoggedOut
    }

    /*
     * =========================================================
     * ROLLBACK REGISTRATION
     * =========================================================
     */
    private suspend fun rollbackRegistration(
        username: String,
        uid: String,
        usernameClaimed: Boolean
    ) {

        if (
            usernameClaimed
        ) {

            userRepository
                .releaseUsername(
                    username,
                    uid
                )
        }

        try {

            auth.currentUser
                ?.delete()
                ?.await()

        } catch (
            e:
            Exception
        ) {

            Log.e(
                "AuthViewModel",
                "Failed to delete incomplete Firebase account",
                e
            )

            auth.signOut()
        }
    }
}