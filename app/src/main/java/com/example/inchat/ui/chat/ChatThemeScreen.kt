package com.example.inchat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.inchat.data.repository.ChatAppearanceRepository
import com.example.inchat.data.repository.ChatRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThemeScreen(
    currentUserId: String,
    otherUserId: String,
    onBackClick: () -> Unit
) {
    val appearanceRepository =
        remember {
            ChatAppearanceRepository()
        }

    val chatId =
        remember(
            currentUserId,
            otherUserId
        ) {
            ChatRepository()
                .getChatRoomId(
                    currentUserId,
                    otherUserId
                )
        }

    val selectedThemeId by
        appearanceRepository
            .observeTheme(chatId)
            .collectAsState(
                initial = ChatTheme.DESSERT.id
            )

    val selectedTheme =
        ChatTheme.fromId(
            selectedThemeId
        )

    val coroutineScope =
        rememberCoroutineScope()

    var errorMessage by
        remember {
            mutableStateOf<String?>(null)
        }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {
                errorMessage = null
            },
            title = {
                Text("Theme")
            },
            text = {
                Text(message)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        errorMessage = null
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        containerColor =
            MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Chat theme",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick
                    ) {
                        Icon(
                            imageVector =
                                Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            MaterialTheme.colorScheme.background
                    )
            )
        }
    ) { innerPadding ->

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 8.dp,
                            bottom = 12.dp
                        )
            ) {
                Text(
                    text = "Conversation theme",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text =
                        selectedTheme.title +
                                " · shared with both participants",
                    modifier =
                        Modifier.padding(top = 3.dp),
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyVerticalGrid(
                columns =
                    GridCells.Adaptive(
                        minSize = 155.dp
                    ),
                modifier =
                    Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 24.dp
                    ),
                horizontalArrangement =
                    Arrangement.spacedBy(10.dp),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = ChatTheme.all,
                    key = { it.id }
                ) { theme ->
                    ChatThemeCard(
                        theme = theme,
                        selected =
                            selectedThemeId ==
                                    theme.id,
                        onClick = {
                            if (
                                selectedThemeId ==
                                        theme.id
                            ) {
                                return@ChatThemeCard
                            }

                            coroutineScope.launch {
                                appearanceRepository
                                    .setTheme(
                                        chatId = chatId,
                                        currentUserId =
                                            currentUserId,
                                        themeId =
                                            theme.id
                                    )
                                    .onFailure { error ->
                                        errorMessage =
                                            error.message
                                                ?: "Could not change chat theme."
                                    }
                            }
                        }
                    )
                }
            }
        }
    }
}
