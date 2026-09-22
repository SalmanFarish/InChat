package com.example.inchat.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UserSearchState {

    data object Idle : UserSearchState

    data object Loading : UserSearchState

    data class Suggestions(
        val users: List<User>
    ) : UserSearchState

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
     * LIVE SEARCH
     * =========================================================
     */
    fun searchUser(
        currentUserId: String,
        rawUsername: String
    ) {

        val username =
            rawUsername
                .trim()
                .removePrefix("@")

        if (
            username.isBlank()
        ) {

            clearSearch()

            return
        }

        searchJob?.cancel()

        searchJob =
            viewModelScope.launch {

                delay(220)

                _searchState.value =
                    UserSearchState.Loading

                try {

                    val users =
                        userRepository
                            .searchUsersByUsernamePrefix(
                                prefix =
                                    username,

                                currentUserId =
                                    currentUserId,

                                limit =
                                    8
                            )

                    if (
                        users.isEmpty()
                    ) {

                        _searchState.value =
                            UserSearchState.NotFound

                    } else {

                        _searchState.value =
                            UserSearchState.Suggestions(
                                users
                            )
                    }

                } catch (
                    _: Exception
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
     * EXACT USER LOOKUP
     * =========================================================
     *
     * Kept for callers that still want a single exact result.
     */
    fun searchExactUser(
        currentUserId: String,
        rawUsername: String
    ) {

        val username =
            rawUsername
                .trim()
                .removePrefix("@")

        if (
            username.isBlank()
        ) {

            clearSearch()

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

                    val discoverable =
                        user?.discoverableByUsername == true

                    when {

                        user == null ||
                                user.uid ==
                                currentUserId ||
                                !discoverable -> {

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
                    _: Exception
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
