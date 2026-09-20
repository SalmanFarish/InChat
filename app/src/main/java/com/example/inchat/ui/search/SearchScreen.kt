package com.example.inchat.ui.search

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.User

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun SearchScreen(
    currentUserId: String,
    searchViewModel: SearchViewModel,
    onUserClick: (User) -> Unit
) {

    var usernameInput by
    rememberSaveable {
        mutableStateOf("")
    }

    val searchState by
    searchViewModel
        .searchState
        .collectAsState()

    val keyboardController =
        LocalSoftwareKeyboardController.current

    val focusRequester =
        remember {
            FocusRequester()
        }

    LaunchedEffect(
        Unit
    ) {

        searchViewModel
            .clearSearch()
    }

    fun performSearch() {

        val query =
            usernameInput
                .trim()

        if (
            query.isBlank()
        ) {

            return
        }

        searchViewModel
            .searchUser(

                currentUserId =
                    currentUserId,

                rawUsername =
                    query
            )

        keyboardController
            ?.hide()
    }

    Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background,

        topBar = {

            TopAppBar(

                title = {

                    Text(

                        text =
                            "Search",

                        fontSize =
                            20.sp,

                        fontWeight =
                            FontWeight.Bold,

                        letterSpacing =
                            (-0.2).sp
                    )
                },

                colors =
                    TopAppBarDefaults
                        .topAppBarColors(

                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .background,

                            scrolledContainerColor =
                                MaterialTheme
                                    .colorScheme
                                    .background,

                            titleContentColor =
                                MaterialTheme
                                    .colorScheme
                                    .onBackground
                        )
            )
        }

    ) { innerPadding ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        innerPadding
                    )
                    .padding(
                        horizontal =
                            18.dp,

                        vertical =
                            16.dp
                    )
        ) {

            Text(

                text =
                    "Find someone",

                fontSize =
                    27.sp,

                fontWeight =
                    FontWeight.Bold,

                letterSpacing =
                    (-0.5).sp
            )

            Spacer(
                modifier =
                    Modifier.height(
                        5.dp
                    )
            )

            Text(

                text =
                    "Search by their username.",

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Spacer(
                modifier =
                    Modifier.height(
                        18.dp
                    )
            )

            OutlinedTextField(

                value =
                    usernameInput,

                onValueChange = {
                        newValue ->

                    usernameInput =
                        newValue

                    searchViewModel
                        .clearSearch()
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(
                            focusRequester
                        ),

                singleLine =
                    true,

                label = {

                    Text(
                        "Username"
                    )
                },

                placeholder = {

                    Text(
                        "Enter username"
                    )
                },

                leadingIcon = {

                    Icon(

                        imageVector =
                            Icons.Default.Search,

                        contentDescription =
                            "Search"
                    )
                },

                trailingIcon = {

                    if (
                        usernameInput.isNotEmpty()
                    ) {

                        IconButton(

                            onClick = {

                                usernameInput =
                                    ""

                                searchViewModel
                                    .clearSearch()
                            }
                        ) {

                            Icon(

                                imageVector =
                                    Icons.Default.Clear,

                                contentDescription =
                                    "Clear search"
                            )
                        }
                    }
                }
            )

            Spacer(
                modifier =
                    Modifier.height(
                        12.dp
                    )
            )

            Button(

                onClick =
                    ::performSearch,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            50.dp
                        ),

                enabled =
                    usernameInput
                        .isNotBlank()
            ) {

                Icon(

                    imageVector =
                        Icons.Default.Search,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(

                    text =
                        "Search",

                    fontWeight =
                        FontWeight.SemiBold
                )
            }

            Spacer(
                modifier =
                    Modifier.height(
                        22.dp
                    )
            )

            when (
                val state =
                    searchState
            ) {

                UserSearchState.Idle -> {

                    SearchEmptyState()
                }

                UserSearchState.Loading -> {

                    Box(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical =
                                        40.dp
                                ),

                        contentAlignment =
                            Alignment.Center
                    ) {

                        CircularProgressIndicator()
                    }
                }

                is UserSearchState.Found -> {

                    SearchUserItem(

                        user =
                            state.user,

                        onClick = {

                            onUserClick(
                                state.user
                            )
                        }
                    )
                }

                UserSearchState.NotFound -> {

                    SearchMessage(

                        text =
                            "No user found with that username."
                    )
                }

                is UserSearchState.Error -> {

                    SearchMessage(

                        text =
                            state.message
                    )
                }
            }
        }
    }
}

/*
 * ============================================================
 * SEARCH USER RESULT
 * ============================================================
 */
@Composable
private fun SearchUserItem(
    user: User,
    onClick: () -> Unit
) {

    Card(

        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick =
                        onClick
                ),

        colors =
            CardDefaults
                .cardColors(

                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                ),

        shape =
            RoundedCornerShape(
                18.dp
            )
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        16.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Surface(

                modifier =
                    Modifier.size(
                        52.dp
                    ),

                shape =
                    androidx.compose
                        .foundation
                        .shape
                        .CircleShape,

                color =
                    MaterialTheme
                        .colorScheme
                        .primaryContainer
            ) {

                Box(

                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(

                        imageVector =
                            Icons.Default.Person,

                        contentDescription =
                            null,

                        modifier =
                            Modifier.size(
                                30.dp
                            ),

                        tint =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.width(
                        14.dp
                    )
            )

            Column {

                Text(

                    text =
                        "@${user.username}",

                    fontSize =
                        18.sp,

                    fontWeight =
                        FontWeight.SemiBold
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            2.dp
                        )
                )

                Text(

                    text =
                        "View profile",

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }
    }
}

/*
 * ============================================================
 * SEARCH EMPTY STATE
 * ============================================================
 */
@Composable
private fun SearchEmptyState() {

    Column(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    top =
                        40.dp
                ),

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Icon(

            imageVector =
                Icons.Default.Search,

            contentDescription =
                null,

            modifier =
                Modifier.size(
                    42.dp
                ),

            tint =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )

        Text(

            text =
                "Search for a username",

            style =
                MaterialTheme
                    .typography
                    .titleMedium,

            fontWeight =
                FontWeight.SemiBold
        )

        Spacer(
            modifier =
                Modifier.height(
                    5.dp
                )
        )

        Text(

            text =
                "Find an InChat user and start a conversation.",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}

/*
 * ============================================================
 * SEARCH MESSAGE
 * ============================================================
 */
@Composable
private fun SearchMessage(
    text: String
) {

    Box(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical =
                        30.dp
                ),

        contentAlignment =
            Alignment.Center
    ) {

        Text(

            text =
                text,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}