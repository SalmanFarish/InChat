package com.example.inchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Message
import com.example.inchat.data.repository.ChatRepository
import com.example.inchat.data.repository.FirebaseServerClock

@Composable
fun ChatMessageActionsDialog(
    message: Message,
    isOwnMessage: Boolean,
    currentUserId: String,
    onReaction: (String) -> Unit,
    onReply: () -> Unit,
    onInfo: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onCopied: () -> Unit
) {
    val context = LocalContext.current
    val editAgeMillis = FirebaseServerClock.now() - message.timestamp
    val editAllowed =
        isOwnMessage &&
            message.timestamp > 0L &&
            editAgeMillis in 0L..ChatRepository.MESSAGE_EDIT_WINDOW_MS
    val confirmingDeleteState = androidx.compose.runtime.remember(message.id) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val popupOnLeft = androidx.compose.runtime.remember(message.id) {
        kotlin.random.Random.Default.nextBoolean()
    }
    val positionProvider = androidx.compose.runtime.remember(popupOnLeft) {
        MessageActionsPopupPositionProvider(popupOnLeft)
    }

    androidx.compose.ui.window.Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 4.dp,
            shadowElevation = 18.dp,
            modifier = Modifier
                .widthIn(min = 196.dp, max = 236.dp)
                .animateContentSize()
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
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null
                            )
                        },
                        title = "Cancel",
                        onClick = { confirmingDeleteState.value = false }
                    )
                    MessageActionRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        title = "Delete",
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = onDelete
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChatRepository.SUPPORTED_REACTIONS.forEach { reaction ->
                            val selected = message.reactions[currentUserId] == reaction
                            Box(
                                modifier = Modifier
                                    .size(if (selected) 42.dp else 40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                                        }
                                    )
                                    .clickable { onReaction(reaction) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = reaction,
                                    fontSize = if (selected) 25.sp else 23.sp,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        platformStyle = PlatformTextStyle(
                                            includeFontPadding = false
                                        )
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(5.dp))
                    MessageActionRow(
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null
                            )
                        },
                        title = "Reply",
                        onClick = onReply
                    )
                    MessageActionRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null
                            )
                        },
                        title = "Info",
                        onClick = onInfo
                    )
                    MessageActionRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null
                            )
                        },
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
                    if (isOwnMessage) {
                        MessageActionRow(
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = if (editAllowed) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            },
                            title = "Edit",
                            titleColor = if (editAllowed) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            enabled = editAllowed,
                            onClick = onEdit
                        )
                        MessageActionRow(
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            title = "Delete",
                            titleColor = MaterialTheme.colorScheme.error,
                            onClick = { confirmingDeleteState.value = true }
                        )
                    }
                }
            }
        }
    }
}

private class MessageActionsPopupPositionProvider(
    private val preferLeft: Boolean
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): androidx.compose.ui.unit.IntOffset {
        val margin = 14
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = if (preferLeft) margin else maxX
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        val y = (anchorBounds.center.y - popupContentSize.height / 2).coerceIn(margin, maxY)
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}


@Composable
private fun MessageActionRow(
    icon: @Composable () -> Unit,
    title: String,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = titleColor ?: if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
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
