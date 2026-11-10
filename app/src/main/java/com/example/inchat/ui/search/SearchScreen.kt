package com.example.inchat.ui.search

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.User
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.profile.InChatProfileAvatar
import com.example.inchat.ui.theme.JetBrainsMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val SEARCH_HISTORY_PREFS = "inchat_search_history"
private const val SEARCH_HISTORY_KEY = "recent_user_ids"
private const val SEARCH_HISTORY_ORDERED_KEY = "recent_user_ids_ordered"
private const val MAX_RECENT_SEARCHES = 8

@Composable
fun SearchScreen(
    currentUserId: String,
    searchViewModel: SearchViewModel,
    searchFocusRequest: Int = 0,
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
        mutableStateOf(readRecentUserIds(historyPreferences))
    }
    var recentUsers by remember { mutableStateOf<List<User>>(emptyList()) }

    val searchState by searchViewModel.searchState.collectAsState()

    LaunchedEffect(recentUserIds, currentUserId) {
        val loadedUsers = withContext(Dispatchers.IO) {
            recentUserIds.mapNotNull { uid ->
                userRepository.getUserByIdFast(uid)
            }
        }

        recentUsers = loadedUsers.filter { it.uid != currentUserId }
    }

    LaunchedEffect(searchFocusRequest) {
        if (searchFocusRequest > 0) {
            focusRequester.requestFocus()
            delay(50)
            keyboardController?.show()
        }
    }

    fun saveRecentUser(user: User) {
        val updatedIds = listOf(user.uid) + recentUserIds.filter { it != user.uid }
        recentUserIds = updatedIds.take(MAX_RECENT_SEARCHES)
        writeRecentUserIds(historyPreferences, recentUserIds)
    }

    fun openUser(user: User) {
        saveRecentUser(user)
        keyboardController?.hide()
        onUserClick(user)
    }

    fun clearRecentSearches() {
        recentUserIds = emptyList()
        recentUsers = emptyList()

        historyPreferences.edit()
            .remove(SEARCH_HISTORY_KEY)
            .remove(SEARCH_HISTORY_ORDERED_KEY)
            .apply()
    }

    fun removeRecentUser(userId: String) {
        recentUserIds = recentUserIds.filter { it != userId }
        recentUsers = recentUsers.filter { it.uid != userId }
        writeRecentUserIds(historyPreferences, recentUserIds)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SearchHeader()

            SearchInput(
                value = usernameInput,
                focusRequester = focusRequester,
                onValueChange = { newValue ->
                    usernameInput = newValue
                    searchViewModel.searchUser(
                        currentUserId = currentUserId,
                        rawUsername = newValue
                    )
                },
                onClear = {
                    usernameInput = ""
                    searchViewModel.clearSearch()
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
                onSearch = {
                    keyboardController?.hide()
                }
            )

            when (val state = searchState) {
                UserSearchState.Idle -> {
                    RecentSearches(
                        users = recentUsers,
                        onUserClick = ::openUser,
                        onRemoveUser = ::removeRecentUser,
                        onClearAll = ::clearRecentSearches
                    )
                }

                UserSearchState.Loading -> SearchLoading()

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

                UserSearchState.NotFound -> SearchNotFound(query = usernameInput)

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
private fun SearchHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 24.dp, end = 22.dp, bottom = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = "INCHAT / DISCOVER",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    letterSpacing = 1.35.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(9.dp))

        Text(
            text = "Find people",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 28.sp,
                lineHeight = 34.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(5.dp))

        Text(
            text = "Look up people by their InChat username.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchInput(
    value: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 15.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(12.dp))

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                text = "Search username",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )

            if (value.isNotBlank()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 23.dp, top = 9.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "USERNAME SEARCH",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        Text(
            text = "LIVE RESULTS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
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

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(
            title = "RECENT PROFILES",
            detail = users.size.toString(),
            action = {
                TextButton(
                    onClick = onClearAll,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 4.dp
                    )
                ) {
                    Text(
                        text = "CLEAR",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMono,
                            letterSpacing = 0.7.sp
                        )
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 2.dp,
                bottom = 24.dp
            )
        ) {
            items(items = users, key = { it.uid }) { user ->
                RecentSearchRow(
                    user = user,
                    onClick = { onUserClick(user) },
                    onRemove = { onRemoveUser(user.uid) }
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    users: List<User>,
    onUserClick: (User) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(
            title = "PEOPLE",
            detail = users.size.toString() + " FOUND"
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 2.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(items = users, key = { it.uid }) { user ->
                SearchUserRow(
                    user = user,
                    onClick = { onUserClick(user) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    detail: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 20.dp, end = 16.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.width(9.dp))

        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun RecentSearchRow(
    user: User,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 20.dp, top = 9.dp, end = 10.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchAvatar(user = user)
            Spacer(Modifier.width(13.dp))

            UserIdentity(
                user = user,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Remove recent search",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SearchRowDivider()
    }
}

@Composable
private fun SearchUserRow(
    user: User,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchAvatar(user = user)
            Spacer(Modifier.width(13.dp))

            UserIdentity(
                user = user,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "VIEW",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    letterSpacing = 0.7.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }

        SearchRowDivider()
    }
}

@Composable
private fun SearchAvatar(user: User) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .padding(2.dp)
    ) {
        InChatProfileAvatar(
            profilePhotoUrl = user.profilePhotoData.ifBlank {
                user.profilePhotoUrl
            },
            modifier = Modifier.fillMaxSize(),
            iconSize = 27.dp,
            contentDescription = "Profile picture"
        )
    }
}

@Composable
private fun UserIdentity(
    user: User,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = user.username,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val secondaryText = user.displayName
            .takeIf { it.isNotBlank() && it != user.username }
            ?: "InChat user"

        Spacer(Modifier.height(2.dp))

        Text(
            text = secondaryText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 82.dp, end = 20.dp)
            .height(0.7.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun SearchLoading() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(25.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = "SEARCHING DIRECTORY",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 0.9.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchNotFound(
    query: String,
    message: String = "No matching users found."
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(29.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (message == "No matching users found.") {
                "No profiles found"
            } else {
                "Search unavailable"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (query.isNotBlank() && message == "No matching users found.") {
            Spacer(Modifier.height(5.dp))

            Text(
                text = "Check the spelling or try another username.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchWelcome() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Find your people",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(7.dp))

        Text(
            text = "Search for an InChat username and discover people you can connect with.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.height(17.dp))

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outline)
        ) {
            Text(
                text = "TYPE A USERNAME TO BEGIN",
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    letterSpacing = 0.55.sp
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun readRecentUserIds(
    preferences: android.content.SharedPreferences
): List<String> {
    val ordered = preferences.getString(
        SEARCH_HISTORY_ORDERED_KEY,
        null
    )

    if (!ordered.isNullOrBlank()) {
        return try {
            val array = JSONArray(ordered)
            List(array.length()) { index ->
                array.optString(index)
            }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    return preferences.getStringSet(
        SEARCH_HISTORY_KEY,
        emptySet()
    ).orEmpty().toList()
}

private fun writeRecentUserIds(
    preferences: android.content.SharedPreferences,
    userIds: List<String>
) {
    val array = JSONArray()

    userIds.take(MAX_RECENT_SEARCHES).forEach { userId ->
        if (userId.isNotBlank()) {
            array.put(userId)
        }
    }

    preferences.edit()
        .putString(SEARCH_HISTORY_ORDERED_KEY, array.toString())
        .remove(SEARCH_HISTORY_KEY)
        .apply()
}
