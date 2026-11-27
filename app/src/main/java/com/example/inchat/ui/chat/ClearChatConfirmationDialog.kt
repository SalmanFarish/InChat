package com.example.inchat.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.runtime.Composable
import com.example.inchat.ui.components.InChatActionDialog

@Composable
fun ClearChatConfirmationDialog(
    title: String,
    description: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    InChatActionDialog(
        title = title,
        description = description,
        titleIcon = Icons.Outlined.DeleteSweep,
        confirmLabel = "Clear chat",
        confirmIcon = Icons.Outlined.DeleteSweep,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
