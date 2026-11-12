package com.example.inchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.FirebaseServerClock
import kotlinx.coroutines.delay

@Composable
fun ChatMessageActionsDialog(
    message: Message,
    isOwnMessage: Boolean,
    onReply: () -> Unit,
    onInfo: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onCopied: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val currentTime = remember(message.id) {
        mutableStateOf(FirebaseServerClock.now())
    }
    val editAgeAtOpen = FirebaseServerClock.now() - message.timestamp

    LaunchedEffect(message.id, message.timestamp, isOwnMessage) {
        if (
            isOwnMessage &&
            message.timestamp > 0L &&
            editAgeAtOpen in 0L until ChatRepository.MESSAGE_EDIT_WINDOW_MS
        ) {
            delay(ChatRepository.MESSAGE_EDIT_WINDOW_MS - editAgeAtOpen)
            currentTime.value = FirebaseServerClock.now()
        }
    }

    val editAgeMillis = currentTime.value - message.timestamp
    val editAllowed =
        isOwnMessage &&
            message.timestamp > 0L &&
            editAgeMillis in 0L until ChatRepository.MESSAGE_EDIT_WINDOW_MS

    val confirmingDeleteState = remember(message.id) {
        mutableStateOf(false)
    }
    val popupOnLeft = remember(message.id) {
        kotlin.random.Random.Default.nextBoolean()
    }
    val positionProvider = remember(popupOnLeft, density) {
        MessageActionsPopupPositionProvider(
            preferLeft = popupOnLeft,
            edgeMarginPx = with(density) { 14.dp.roundToPx() }
        )
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 190.dp, max = 228.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 4.dp,
            shadowElevation = 18.dp,
            border = BorderStroke(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
            )
        ) {
            Column(
                modifier = Modifier.padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (confirmingDeleteState.value) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Delete this message?",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "This message will be deleted.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MessageActionRow(
                        icon = Icons.Default.Clear,
                        title = "Cancel",
                        onClick = { confirmingDeleteState.value = false }
                    )
                    MessageActionRow(
                        icon = Icons.Outlined.DeleteOutline,
                        title = "Delete",
                        destructive = true,
                        onClick = onDelete
                    )
                } else {
                    MessageActionRow(
                        icon = Icons.AutoMirrored.Filled.Reply,
                        title = "Reply",
                        onClick = onReply
                    )
                    MessageActionRow(
                        icon = Icons.Default.Info,
                        title = "Info",
                        onClick = onInfo
                    )
                    MessageActionRow(
                        icon = Icons.Outlined.ContentCopy,
                        title = "Copy",
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("InChat message", message.text)
                            )
                            onCopied()
                        }
                    )
                    if (editAllowed) {
                        MessageActionRow(
                            icon = Icons.Default.Edit,
                            title = "Edit",
                            onClick = onEdit
                        )
                    }
                    if (isOwnMessage) {
                        MessageActionRow(
                            icon = Icons.Outlined.DeleteOutline,
                            title = "Delete",
                            destructive = true,
                            onClick = { confirmingDeleteState.value = true }
                        )
                    }
                }
            }
        }
    }
}

private class MessageActionsPopupPositionProvider(
    private val preferLeft: Boolean,
    private val edgeMarginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - edgeMarginPx)
            .coerceAtLeast(edgeMarginPx)
        val x = if (preferLeft) edgeMarginPx else maxX
        val maxY = (windowSize.height - popupContentSize.height - edgeMarginPx)
            .coerceAtLeast(edgeMarginPx)
        val y = (anchorBounds.center.y - popupContentSize.height / 2)
            .coerceIn(edgeMarginPx, maxY)
        return IntOffset(x, y)
    }
}

@Composable
private fun MessageActionRow(
    icon: ImageVector,
    title: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val foreground = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (destructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = foreground
            )
            Spacer(Modifier.width(11.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                fontWeight = FontWeight.SemiBold,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
fun MessageInfoDialog(
    message: Message,
    isOwnMessage: Boolean,
    deliveredTimestamp: Long = 0L,
    readTimestamp: Long = 0L,
    seenByCount: Int = 0,
    currentTimeMillis: Long = System.currentTimeMillis(),
    onDismiss: () -> Unit
) {
    val isSeen =
        isOwnMessage &&
            readTimestamp > 0L &&
            message.timestamp > 0L &&
            readTimestamp >= message.timestamp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Message info",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MessageInfoRow(
                    label = if (isOwnMessage) "Sent" else "Received",
                    value = formatMessageTime(message.timestamp)
                )

                if (isOwnMessage) {
                    MessageInfoRow(
                        label = "Delivered",
                        value = if (deliveredTimestamp >= message.timestamp && deliveredTimestamp > 0L) {
                            formatMessageTime(deliveredTimestamp)
                        } else {
                            "Not delivered yet"
                        }
                    )

                    MessageInfoRow(
                        label = "Read",
                        value = when {
                            !isSeen -> "Not read yet"
                            seenByCount > 1 ->
                                "Read by $seenByCount people"
                            else ->
                                "Read"
                        }
                    )
                }

                if (message.edited) {
                    MessageInfoRow(
                        label = "Edited",
                        value = formatMessageTime(message.timestamp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun MessageInfoRow(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(2.dp)
        )

        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun DeleteMessageDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Delete Message")
        },
        text = {
            Text("Do you want to delete this message?")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}
