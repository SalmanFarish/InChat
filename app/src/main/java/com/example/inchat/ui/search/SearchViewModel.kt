package com.example.inchat.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UserSearchState {

    data object Idle : UserSearchState

    data object Loading : UserSearchState

    data class Found(
        val user: User
    ) : UserSearchState

    data object NotFound : UserSearchState

    data class Error(
        val message: String
    ) : UserSearchState
}

class SearchViewModel : ViewModel() {

    private val userRepository =
        UserRepository()

    private val _searchState =
        MutableStateFlow<UserSearchState>(
            UserSearchState.Idle
        )

    val searchState:
            StateFlow<UserSearchState> =
        _searchState.asStateFlow()

    private var searchJob:
            Job? = null

    /*
     * =========================================================
     * SEARCH USER
     * =========================================================
     */
    fun searchUser(
        currentUserId: String,
        rawUsername: String
    ) {

        val username =
            rawUsername.trim()

        if (
            username.isBlank()
        ) {

            _searchState.value =
                UserSearchState.Idle

            return
        }

        searchJob?.cancel()

        searchJob =
            viewModelScope.launch {

                _searchState.value =
                    UserSearchState.Loading

                try {

                    val user =
                        userRepository
                            .getUserByUsername(
                                username
                            )

                    when {

                        user == null -> {

                            _searchState.value =
                                UserSearchState.NotFound
                        }

                        user.uid ==
                                currentUserId -> {

                            _searchState.value =
                                UserSearchState.NotFound
                        }

                        else -> {

                            _searchState.value =
                                UserSearchState.Found(
                                    user
                                )
                        }
                    }

                } catch (
                    e: Exception
                ) {

                    _searchState.value =
                        UserSearchState.Error(
                            "Could not search right now. Please try again."
                        )
                }
            }
    }

    /*
     * =========================================================
     * CLEAR SEARCH
     * =========================================================
     */
    fun clearSearch() {

        searchJob?.cancel()

        searchJob =
            null

        _searchState.value =
            UserSearchState.Idle
    }

    /*
     * =========================================================
     * CLEANUP
     * =========================================================
     */
    override fun onCleared() {

        searchJob?.cancel()

        super.onCleared()
    }
}