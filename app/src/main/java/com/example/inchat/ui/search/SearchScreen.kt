package com.example.inchat.ui.search

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SEARCH_HISTORY_PREFS = "inchat_search_history"
private const val SEARCH_HISTORY_KEY = "recent_user_ids"
private const val SEARCH_HISTORY_ORDERED_KEY = "recent_user_ids_ordered"
private const val MAX_RECENT_SEARCHES = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    currentUserId: String,
    searchViewModel: SearchViewModel,
    onUserClick: (User) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val userRepository = remember { UserRepository() }

    val historyPreferences = remember {
        context.getSharedPreferences(
            SEARCH_HISTORY_PREFS,
            Context.MODE_PRIVATE
        )
    }

    var usernameInput by rememberSaveable { mutableStateOf("") }

    var recentUserIds by remember {
        mutableStateOf(
            readRecentUserIds(
                historyPreferences
            )
        )
    }

    var recentUsers by remember {
        mutableStateOf<List<User>>(emptyList())
    }

    val searchState by searchViewModel.searchState.collectAsState()

    LaunchedEffect(recentUserIds) {
        val loadedUsers = withContext(Dispatchers.IO) {
            recentUserIds.mapNotNull { uid ->
                userRepository.getUserByIdFast(uid)
            }
        }

        recentUsers = loadedUsers.filter {
            it.uid != currentUserId
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun saveRecentUser(user: User) {
        val updatedIds =
            listOf(user.uid) +
                    recentUserIds.filter {
                        it != user.uid
                    }

        recentUserIds =
            updatedIds.take(MAX_RECENT_SEARCHES)

        writeRecentUserIds(
            preferences =
                historyPreferences,

            userIds =
                recentUserIds
        )
    }

    fun openUser(user: User) {
        saveRecentUser(user)
        keyboardController?.hide()
        onUserClick(user)
    }

    fun clearRecentSearches() {
        recentUserIds = emptyList()
        recentUsers = emptyList()

        historyPreferences
            .edit()
            .remove(
                SEARCH_HISTORY_KEY
            )
            .remove(
                SEARCH_HISTORY_ORDERED_KEY
            )
            .apply()
    }

    fun removeRecentUser(
        userId: String
    ) {
        recentUserIds =
            recentUserIds.filter {
                it != userId
            }

        recentUsers =
            recentUsers.filter {
                it.uid != userId
            }

        writeRecentUserIds(
            preferences =
                historyPreferences,

            userIds =
                recentUserIds
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
        ) {

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            top = 10.dp,
                            end = 16.dp,
                            bottom = 8.dp
                        ),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(
                            modifier = Modifier.width(9.dp)
                        )

                        BasicTextField(
                            value = usernameInput,
                            onValueChange = { newValue ->
                                usernameInput = newValue

                                searchViewModel.searchUser(
                                    currentUserId = currentUserId,
                                    rawUsername = newValue
                                )
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle =
                                MaterialTheme.typography.bodyLarge.copy(
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurface
                                ),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (usernameInput.isBlank()) {
                                        Text(
                                            text = "Search",
                                            color =
                                                MaterialTheme
                                                    .colorScheme
                                                    .onSurfaceVariant
                                        )
                                    }

                                    innerTextField()
                                }
                            }
                        )

                        if (usernameInput.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    usernameInput = ""
                                    searchViewModel.clearSearch()
                                    focusRequester.requestFocus()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }

            when (val state = searchState) {

                UserSearchState.Idle -> {
                    RecentSearches(
                        users =
                            recentUsers,

                        onUserClick =
                            ::openUser,

                        onRemoveUser =
                            ::removeRecentUser,

                        onClearAll =
                            ::clearRecentSearches
                    )
                }

                UserSearchState.Loading -> {
                    SearchLoading()
                }

                is UserSearchState.Suggestions -> {
                    SearchResults(
                        users = state.users,
                        onUserClick = ::openUser
                    )
                }

                is UserSearchState.Found -> {
                    SearchResults(
                        users = listOf(state.user),
                        onUserClick = ::openUser
                    )
                }

                UserSearchState.NotFound -> {
                    SearchNotFound(query = usernameInput)
                }

                is UserSearchState.Error -> {
                    SearchNotFound(
                        query = usernameInput,
                        message = state.message
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(
    users: List<User>,
    onUserClick: (User) -> Unit,
    onRemoveUser: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (users.isEmpty()) {
        SearchWelcome()
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 20.dp,
                        top = 14.dp,
                        end = 12.dp,
                        bottom = 6.dp
                    ),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = "Recent",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = onClearAll
            ) {
                Text("Clear all")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(
                items = users,
                key = { it.uid }
            ) { user ->
                RecentSearchRow(
                    user =
                        user,

                    onClick = {
                        onUserClick(
                            user
                        )
                    },

                    onRemove = {
                        onRemoveUser(
                            user.uid
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun RecentSearchRow(
    user: User,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick =
                        onClick
                )
                .padding(
                    start = 20.dp,
                    top = 10.dp,
                    end = 12.dp,
                    bottom = 10.dp
                ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        InChatProfileAvatar(
            profilePhotoUrl =
                user.profilePhotoData.ifBlank {
                    user.profilePhotoUrl
                },

            modifier =
                Modifier.size(
                    52.dp
                ),

            iconSize =
                29.dp,

            contentDescription =
                "Profile picture"
        )

        Spacer(
            modifier =
                Modifier.width(
                    13.dp
                )
        )

        Column(
            modifier =
                Modifier.weight(
                    1f
                )
        ) {

            Text(
                text =
                    "@${user.username}",

                fontSize =
                    16.sp,

                fontWeight =
                    FontWeight.SemiBold
            )

            val secondaryText =
                user.displayName
                    .takeIf {
                        it.isNotBlank() &&
                                it != user.username
                    }
                    ?: "InChat user"

            Text(
                text =
                    secondaryText,

                style =
                    MaterialTheme.typography.bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        IconButton(
            onClick =
                onRemove
        ) {

            Icon(
                imageVector =
                    Icons.Default.Clear,

                contentDescription =
                    "Remove recent search"
            )
        }
    }
}

@Composable
private fun SearchResults(
    users: List<User>,
    onUserClick: (User) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {

        Text(
            text = "People",
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 20.dp,
                        top = 14.dp,
                        end = 20.dp,
                        bottom = 8.dp
                    ),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(
                items = users,
                key = { it.uid }
            ) { user ->
                SearchUserRow(
                    user = user,
                    onClick = {
                        onUserClick(user)
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchUserRow(
    user: User,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    horizontal = 20.dp,
                    vertical = 10.dp
                ),
        verticalAlignment = Alignment.CenterVertically
    ) {

        InChatProfileAvatar(
            profilePhotoUrl =
                user.profilePhotoData.ifBlank {
                    user.profilePhotoUrl
                },
            modifier = Modifier.size(52.dp),
            iconSize = 29.dp,
            contentDescription = "Profile picture"
        )

        Spacer(
            modifier = Modifier.width(13.dp)
        )

        Column(
            modifier = Modifier.weight(1f)
        ) {

            Text(
                text = "@${user.username}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            val secondaryText =
                user.displayName
                    .takeIf {
                        it.isNotBlank() &&
                                it != user.username
                    }
                    ?: "InChat user"

            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchLoading() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun SearchNotFound(
    query: String,
    message: String = "No matching users found."
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 28.dp,
                    vertical = 48.dp
                ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (query.isNotBlank()) {
            Spacer(
                modifier = Modifier.height(5.dp)
            )

            Text(
                text = "Try a different username.",
                style = MaterialTheme.typography.bodyMedium,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchWelcome() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 28.dp,
                    vertical = 64.dp
                ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Text(
            text = "Find people on InChat",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(6.dp)
        )

        Text(
            text = "Start typing a username to see matching people.",
            style = MaterialTheme.typography.bodyMedium,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}


private fun readRecentUserIds(
    preferences:
        android.content.SharedPreferences
): List<String> {

    val ordered =
        preferences
            .getString(
                SEARCH_HISTORY_ORDERED_KEY,
                null
            )

    if (
        !ordered.isNullOrBlank()
    ) {

        return try {

            val array =
                JSONArray(
                    ordered
                )

            List(
                array.length()
            ) { index ->
                array.optString(
                    index
                )
            }
                .filter {
                    it.isNotBlank()
                }

        } catch (
            _: Exception
        ) {

            emptyList()
        }
    }

    return preferences
        .getStringSet(
            SEARCH_HISTORY_KEY,
            emptySet()
        )
        .orEmpty()
        .toList()
}

private fun writeRecentUserIds(
    preferences:
        android.content.SharedPreferences,

    userIds:
        List<String>
) {

    val array =
        JSONArray()

    userIds
        .take(
            MAX_RECENT_SEARCHES
        )
        .forEach { userId ->

            if (
                userId.isNotBlank()
            ) {

                array.put(
                    userId
                )
            }
        }

    preferences
        .edit()
        .putString(
            SEARCH_HISTORY_ORDERED_KEY,
            array.toString()
        )
        .remove(
            SEARCH_HISTORY_KEY
        )
        .apply()
}
