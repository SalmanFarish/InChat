package com.example.inchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
    val appearanceRepository = remember {
        ChatAppearanceRepository()
    }

    val chatId = remember(currentUserId, otherUserId) {
        ChatRepository().getChatRoomId(
            currentUserId,
            otherUserId
        )
    }

    val selectedThemeId by appearanceRepository
        .observeTheme(chatId)
        .collectAsState(initial = ChatTheme.DESSERT.id)

    val coroutineScope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentErrorMessage = errorMessage

    if (currentErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Theme") },
            text = { Text(currentErrorMessage) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Chat theme") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            Text(
                text = "Changes apply to both people in this conversation.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 20.dp,
                        top = 8.dp,
                        end = 20.dp,
                        bottom = 12.dp
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = ChatTheme.all,
                    key = { it.id }
                ) { theme ->
                    ChatThemeRow(
                        theme = theme,
                        selected = selectedThemeId == theme.id,
                        onClick = {
                            coroutineScope.launch {
                                appearanceRepository.setTheme(
                                    chatId = chatId,
                                    currentUserId = currentUserId,
                                    themeId = theme.id
                                ).onFailure { error ->
                                    errorMessage =
                                        error.message ?: "Could not change chat theme."
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
