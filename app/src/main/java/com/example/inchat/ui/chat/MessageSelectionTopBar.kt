package com.example.inchat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.inchat.ui.theme.InChatMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSelectionTopBar(
    selectedCount: Int,
    canDeleteForEveryone: Boolean,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit message selection"
                )
            }
        },
        title = {
            AnimatedContent(
                targetState = selectedCount,
                transitionSpec = {
                    (
                        fadeIn(animationSpec = InChatMotion.effects(150)) +
                            slideInVertically(
                                animationSpec = InChatMotion.navigationSpring(),
                                initialOffsetY = { it / 4 }
                            )
                        ) togetherWith (
                        fadeOut(animationSpec = InChatMotion.effects(110)) +
                            slideOutVertically(
                                animationSpec = InChatMotion.navigationSpring(),
                                targetOffsetY = { -it / 4 }
                            )
                        )
                },
                label = "selectedMessageCount"
            ) { count ->
                Text(
                    text = "$count selected",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        actions = {
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy selected messages"
                )
            }
            IconButton(onClick = onDeleteForMe) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete selected messages for me"
                )
            }
            if (canDeleteForEveryone) {
                IconButton(onClick = onDeleteForEveryone) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = "Delete selected messages for everyone"
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}
