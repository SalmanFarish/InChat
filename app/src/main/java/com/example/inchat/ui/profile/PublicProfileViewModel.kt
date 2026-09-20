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

    private var loadedUsername:
            String? = null

    private var presenceJob:
            Job? = null

    fun loadProfile(
        username: String
    ) {

        val normalizedUsername =
            username.trim()

        if (
            normalizedUsername.isBlank()
        ) {

            _uiState.value =
                PublicProfileUiState.NotFound

            return
        }

        val normalizedKey =
            normalizedUsername.lowercase()

        if (
            loadedUsername ==
            normalizedKey
        ) {

            return
        }

        loadedUsername =
            normalizedKey

        presenceJob?.cancel()
        presenceJob = null

        viewModelScope.launch {

            _uiState.value =
                PublicProfileUiState.Loading

            try {

                val user =
                    userRepository
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