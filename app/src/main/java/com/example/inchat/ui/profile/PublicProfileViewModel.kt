package com.example.inchat.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.model.Presence
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.PresenceRepository
import com.example.inchat.data.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PublicProfileUiState {

    data object Loading : PublicProfileUiState

    data class Success(
        val user: User
    ) : PublicProfileUiState

    data object NotFound : PublicProfileUiState

    data class Error(
        val message: String
    ) : PublicProfileUiState
}

class PublicProfileViewModel : ViewModel() {

    private val userRepository =
        UserRepository()

    private val _uiState =
        MutableStateFlow<PublicProfileUiState>(
            PublicProfileUiState.Loading
        )

    val uiState:
            StateFlow<PublicProfileUiState> =
        _uiState.asStateFlow()

    private val _presence =
        MutableStateFlow(
            Presence()
        )

    val presence:
            StateFlow<Presence> =
        _presence.asStateFlow()

    private var loadedProfileKey:
            String? = null

    private var presenceJob:
            Job? = null

    fun loadProfile(
        username: String,
        userId: String = ""
    ) {

        val normalizedUsername =
            username
                .trim()
                .removePrefix("@")

        val normalizedUserId =
            userId.trim()

        if (
            normalizedUsername.isBlank() &&
            normalizedUserId.isBlank()
        ) {

            _uiState.value =
                PublicProfileUiState.NotFound

            return
        }

        val normalizedKey =
            if (
                normalizedUserId.isNotBlank()
            ) {
                "id:" + normalizedUserId
            } else {
                "username:" + normalizedUsername.lowercase()
            }

        if (
            loadedProfileKey ==
            normalizedKey
        ) {

            return
        }

        loadedProfileKey =
            normalizedKey

        presenceJob?.cancel()
        presenceJob = null

        viewModelScope.launch {

            /*
             * Never clear an already-visible profile while refreshing.
             * This avoids a loading flash when a profile is revisited.
             */
            if (
                _uiState.value !is PublicProfileUiState.Success
            ) {
                _uiState.value =
                    PublicProfileUiState.Loading
            }

            try {

                /*
                 * Search results already load users through the shared
                 * UserRepository cache. When navigation provides the UID,
                 * read that cached user first so the profile can render
                 * immediately instead of waiting on the username index.
                 */
                val cachedUser =
                    if (
                        normalizedUserId.isNotBlank()
                    ) {
                        userRepository
                            .getUserByIdFast(
                                normalizedUserId
                            )
                    } else {
                        null
                    }

                val user =
                    cachedUser
                        ?: userRepository
                            .getUserByUsername(
                                normalizedUsername
                            )

                if (
                    user == null ||
                    user.username.isBlank()
                ) {

                    _uiState.value =
                        PublicProfileUiState.NotFound

                    return@launch
                }

                _uiState.value =
                    PublicProfileUiState
                        .Success(
                            user
                        )

                presenceJob =
                    launch {

                        PresenceRepository
                            .observePresence(
                                user.uid
                            )
                            .collect { presenceValue ->

                                _presence.value =
                                    presenceValue
                            }
                    }

            } catch (e: Exception) {

                _uiState.value =
                    PublicProfileUiState.Error(
                        "Could not load this profile. Please try again."
                    )
            }
        }
    }

    override fun onCleared() {

        presenceJob?.cancel()
        presenceJob = null
    }
}
