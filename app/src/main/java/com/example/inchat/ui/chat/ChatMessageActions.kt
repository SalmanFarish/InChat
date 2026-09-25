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

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .widthIn(max = 370.dp)
                .animateContentSize()
        ) {
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                /*
                 * Compact reaction dock.
                 * No heading: the emoji row is the primary action,
                 * matching the quick reaction feel of modern DMs.
                 */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChatRepository.SUPPORTED_REACTIONS.forEach { reaction ->
                        val selected =
                            message.reactions[currentUserId] == reaction

                        Box(
                            modifier = Modifier
                                .size(
                                    if (selected) 50.dp else 46.dp
                                )
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                            .copy(alpha = 0.32f)
                                    }
                                )
                                .clickable {
                                    onReaction(reaction)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = reaction,
                                fontSize = if (selected) 29.sp else 26.sp,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    platformStyle = PlatformTextStyle(
                                        includeFontPadding = false
                                    )
                                )
                            )
                        }
                    }
                }

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

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
                            context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager

                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "InChat message",
                                message.text
                            )
                        )

                        onCopied()
                    }
                )

                if (isOwnMessage) {
                    MessageActionRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null
                            )
                        },
                        title = "Edit",
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
                        onClick = onDelete
                    )
                }

                Spacer(
                    modifier = Modifier.height(4.dp)
                )

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionRow(
    icon: @Composable () -> Unit,
    title: String,
    titleColor: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = 12.dp,
                vertical = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }

        Spacer(
            modifier = Modifier.size(12.dp)
        )

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = titleColor
        )
    }
}

@Composable
fun MessageInfoDialog(
    message: Message,
    isOwnMessage: Boolean,
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
                        label = "Seen",
                        value = when {
                            !isSeen -> "Not seen yet"
                            seenByCount > 1 ->
                                "Seen by $seenByCount people"

                            else ->
                                formatSeenReceipt(
                                    readTimestamp = readTimestamp,
                                    currentTimeMillis = currentTimeMillis
                                )
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
