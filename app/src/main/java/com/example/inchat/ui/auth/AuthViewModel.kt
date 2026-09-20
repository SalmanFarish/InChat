package com.example.inchat.ui.auth

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.repository.PasswordRecoveryRepository
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.data.repository.UsernameAlreadyTakenException
import com.example.inchat.data.security.RecoveryCodeManager
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface AuthUiState {

    data object Loading : AuthUiState

    data object LoggedOut : AuthUiState

    data class LoggedIn(
        val uid: String,
        val username: String
    ) : AuthUiState
}

class AuthViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val auth =
        FirebaseAuth.getInstance()

    private val userRepository =
        UserRepository()

    private val passwordRecoveryRepository =
        PasswordRecoveryRepository()

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
                        uid =
                            restoredUser.uid,

                        email =
                            restoredUser.email
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
            uid =
                firebaseUser.uid,

            email =
                firebaseUser.email
        )
    }

    private fun initializeAuthenticatedSession(
        uid: String,
        email: String?
    ) {

        if (
            uid.isBlank()
        ) {

            _uiState.value =
                AuthUiState.LoggedOut

            return
        }

        val quickUsername =
            extractUsernameFromAuthEmail(
                email
            )

        if (
            quickUsername.isNotBlank()
        ) {

            applyUserProfile(
                uid =
                    uid,

                username =
                    quickUsername
            )

            _uiState.value =
                AuthUiState.LoggedIn(
                    uid =
                        uid,

                    username =
                        quickUsername
                )

            refreshUserProfile(
                uid
            )

            registerFcmToken(
                uid
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

    private fun extractUsernameFromAuthEmail(
        email: String?
    ): String {

        if (
            email.isNullOrBlank()
        ) {

            return ""
        }

        val cleanEmail =
            email.trim()

        val suffix =
            "@inchat.app"

        if (
            !cleanEmail.endsWith(
                suffix,
                ignoreCase = true
            )
        ) {

            return ""
        }

        return cleanEmail
            .substring(
                0,
                cleanEmail.length -
                        suffix.length
            )
            .trim()
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
     *
     * The password is required because Firebase may require
     * recent authentication before deleting the Auth account.
     */
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

        val email =
            firebaseUser.email

        if (
            email.isNullOrBlank()
        ) {

            onResult(
                false,
                "This account cannot be deleted from the app."
            )

            return
        }

        val cleanPassword =
            password.trim()

        if (
            cleanPassword.isBlank()
        ) {

            onResult(
                false,
                "Enter your password."
            )

            return
        }

        viewModelScope.launch {

            try {

                /*
                 * Re-authenticate first.
                 */
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

                /*
                 * Remove account-owned database data.
                 */
                val deleteDataResult =
                    userRepository
                        .deleteAccountData(
                            uid =
                                uid,

                            username =
                                currentUsername
                        )

                if (
                    deleteDataResult.isFailure
                ) {

                    val error =
                        deleteDataResult
                            .exceptionOrNull()

                    Log.e(
                        "AuthViewModel",
                        "Account data deletion failed",
                        error
                    )

                    onResult(
                        false,
                        error?.message
                            ?: "Could not delete your account data."
                    )

                    return@launch
                }

                /*
                 * Delete the Firebase Auth account last.
                 */
                firebaseUser
                    .delete()
                    .await()

                clearLocalAuthState()

                onResult(
                    true,
                    null
                )

            } catch (
                e:
                Exception
            ) {

                Log.e(
                    "AuthViewModel",
                    "Account deletion failed",
                    e
                )

                val message =
                    when {

                        e.message
                            ?.contains(
                                "password",
                                ignoreCase = true
                            ) == true ->
                            "Incorrect password."

                        e.message
                            ?.contains(
                                "recent",
                                ignoreCase = true
                            ) == true ->
                            "Please sign in again and try deleting the account."

                        else ->
                            e.message
                                ?: "Could not delete your account."
                    }

                onResult(
                    false,
                    message
                )
            }
        }
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
        rawUsername: String,
        password: String,
        onResult:
            (String?, List<String>?) -> Unit
    ) {

        viewModelScope.launch {

            val username =
                rawUsername.trim()

            val usernameKey =
                username.lowercase()

            if (
                !username.matches(
                    Regex(
                        "^[a-zA-Z0-9_.]{3,20}$"
                    )
                )
            ) {

                onResult(
                    "Username must be 3-20 characters: letters, numbers, _ or .",
                    null
                )

                return@launch
            }

            if (
                password.length < 6
            ) {

                onResult(
                    "Password must be at least 6 characters",
                    null
                )

                return@launch
            }

            var createdUid:
                    String? =
                null

            var usernameClaimed =
                false

            try {

                val fakeEmail =
                    "$usernameKey@inchat.app"

                val authResult =
                    auth
                        .createUserWithEmailAndPassword(
                            fakeEmail,
                            password
                        )
                        .await()

                val uid =
                    authResult
                        .user
                        ?.uid
                        ?: throw IllegalStateException(
                            "Firebase did not return a user ID"
                        )

                createdUid =
                    uid

                val claimResult =
                    userRepository
                        .claimUsername(
                            username =
                                username,

                            uid =
                                uid
                        )

                if (
                    claimResult.isFailure
                ) {

                    val error =
                        claimResult
                            .exceptionOrNull()

                    if (
                        error is
                                UsernameAlreadyTakenException
                    ) {

                        rollbackRegistration(
                            username =
                                username,

                            uid =
                                uid,

                            usernameClaimed =
                                false
                        )

                        onResult(
                            "That username is already taken",
                            null
                        )

                    } else {

                        rollbackRegistration(
                            username =
                                username,

                            uid =
                                uid,

                            usernameClaimed =
                                false
                        )

                        onResult(
                            "Could not reserve that username. Please try again.",
                            null
                        )
                    }

                    return@launch
                }

                usernameClaimed =
                    true

                val saveResult =
                    userRepository
                        .saveUser(
                            uid =
                                uid,

                            username =
                                username,

                            displayName =
                                username,

                            bio =
                                ""
                        )

                if (
                    saveResult.isFailure
                ) {

                    rollbackRegistration(
                        username =
                            username,

                        uid =
                            uid,

                        usernameClaimed =
                            usernameClaimed
                    )

                    onResult(
                        "Could not finish account creation. Please try again.",
                        null
                    )

                    return@launch
                }

                val recoveryCodeSet =
                    RecoveryCodeManager
                        .generateRecoveryCodeSet()

                val recoverySaveResult =
                    userRepository
                        .saveRecoveryCodes(
                            uid =
                                uid,

                            recoveryCodeSet =
                                recoveryCodeSet
                        )

                if (
                    recoverySaveResult.isFailure
                ) {

                    rollbackRegistration(
                        username =
                            username,

                        uid =
                            uid,

                        usernameClaimed =
                            usernameClaimed
                    )

                    onResult(
                        "Could not secure the new account. Please try again.",
                        null
                    )

                    return@launch
                }

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

                registerFcmToken(
                    uid
                )

                onResult(
                    null,
                    recoveryCodeSet.codes
                )

            } catch (
                e:
                FirebaseAuthUserCollisionException
            ) {

                onResult(
                    "That username is already taken",
                    null
                )

            } catch (
                e:
                FirebaseAuthWeakPasswordException
            ) {

                onResult(
                    "Password is too weak. Use a stronger password.",
                    null
                )

            } catch (
                e:
                Exception
            ) {

                Log.e(
                    "AuthViewModel",
                    "Registration failed",
                    e
                )

                val uid =
                    createdUid

                if (
                    uid != null
                ) {

                    rollbackRegistration(
                        username =
                            username,

                        uid =
                            uid,

                        usernameClaimed =
                            usernameClaimed
                    )
                }

                onResult(
                    "Registration failed. Please try again.",
                    null
                )
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
     * GENERATE / REGENERATE RECOVERY CODES
     * =========================================================
     */
    fun generateRecoveryCodes(
        onResult:
            (Boolean, List<String>?, String?) -> Unit
    ) {

        val uid =
            _currentUserId.value

        if (
            uid.isBlank()
        ) {

            onResult(
                false,
                null,
                "You are not logged in."
            )

            return
        }

        viewModelScope.launch {

            try {

                val recoveryCodeSet =
                    RecoveryCodeManager
                        .generateRecoveryCodeSet()

                val saveResult =
                    userRepository
                        .saveRecoveryCodes(
                            uid =
                                uid,

                            recoveryCodeSet =
                                recoveryCodeSet
                        )

                saveResult
                    .onSuccess {

                        onResult(
                            true,
                            recoveryCodeSet.codes,
                            null
                        )
                    }
                    .onFailure { error ->

                        onResult(
                            false,
                            null,
                            error.message
                                ?: "Could not generate recovery codes"
                        )
                    }

            } catch (
                e:
                Exception
            ) {

                Log.e(
                    "AuthViewModel",
                    "Recovery-code generation failed",
                    e
                )

                onResult(
                    false,
                    null,
                    "Could not generate recovery codes"
                )
            }
        }
    }

    /*
     * =========================================================
     * PASSWORD RECOVERY
     * =========================================================
     */
    fun recoverPassword(
        username: String,
        recoveryCode: String,
        newPassword: String,
        onResult:
            (String?) -> Unit
    ) {

        viewModelScope.launch {

            val cleanUsername =
                username.trim()

            val cleanRecoveryCode =
                recoveryCode.trim()

            if (
                cleanUsername.isBlank()
            ) {

                onResult(
                    "Enter your username."
                )

                return@launch
            }

            if (
                !cleanUsername.matches(
                    Regex(
                        "^[a-zA-Z0-9_.]{3,20}$"
                    )
                )
            ) {

                onResult(
                    "Invalid username."
                )

                return@launch
            }

            if (
                cleanRecoveryCode.isBlank()
            ) {

                onResult(
                    "Enter your recovery code."
                )

                return@launch
            }

            if (
                !cleanRecoveryCode
                    .uppercase()
                    .matches(
                        Regex(
                            "^INCH-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$"
                        )
                    )
            ) {

                onResult(
                    "Invalid recovery code format."
                )

                return@launch
            }

            if (
                newPassword.length < 6
            ) {

                onResult(
                    "New password must be at least 6 characters."
                )

                return@launch
            }

            if (
                newPassword.length > 4096
            ) {

                onResult(
                    "New password is too long."
                )

                return@launch
            }

            passwordRecoveryRepository
                .recoverPassword(
                    username =
                        cleanUsername,

                    recoveryCode =
                        cleanRecoveryCode,

                    newPassword =
                        newPassword
                )
                .onSuccess { message ->

                    onResult(
                        message
                    )
                }
                .onFailure { error ->

                    onResult(
                        error.message
                            ?: "Could not reset your password."
                    )
                }
        }
    }

    /*
     * =========================================================
     * LOGIN
     * =========================================================
     */
    fun login(
        rawUsername: String,
        password: String,
        onResult:
            (String?) -> Unit
    ) {

        viewModelScope.launch {

            val username =
                rawUsername.trim()

            if (
                username.isBlank() ||
                password.isBlank()
            ) {

                onResult(
                    "Enter username and password"
                )

                return@launch
            }

            try {

                val usernameKey =
                    username.lowercase()

                val fakeEmail =
                    "$usernameKey@inchat.app"

                val result =
                    auth
                        .signInWithEmailAndPassword(
                            fakeEmail,
                            password
                        )
                        .await()

                val uid =
                    result
                        .user
                        ?.uid

                if (
                    uid == null
                ) {

                    onResult(
                        "Login failed"
                    )

                    return@launch
                }

                val user =
                    userRepository
                        .getUserById(
                            uid
                        )

                if (
                    user == null ||
                    user.username.isBlank()
                ) {

                    auth.signOut()

                    clearLocalAuthState()

                    onResult(
                        "This account is incomplete. Please contact support."
                    )

                    return@launch
                }

                applyUserProfile(
                    uid =
                        uid,

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
                            uid,

                        username =
                            user.username
                    )

                registerFcmToken(
                    uid
                )

                onResult(
                    null
                )

            } catch (
                e:
                Exception
            ) {

                Log.e(
                    "AuthViewModel",
                    "Login failed",
                    e
                )

                onResult(
                    "Invalid username or password"
                )
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