package com.example.inchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessageActionsDialog(
    message: Message,
    isOwnMessage: Boolean,
    deliveredTimestamp: Long = 0L,
    readTimestamp: Long = 0L,
    seenByCount: Int = 0,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onSelectMessages: () -> Unit,
    onDismiss: () -> Unit,
    onCopied: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val currentTime = remember(message.id) {
        mutableLongStateOf(FirebaseServerClock.now())
    }
    val editAgeAtOpen = FirebaseServerClock.now() - message.timestamp

    LaunchedEffect(message.id, message.timestamp, isOwnMessage) {
        if (
            isOwnMessage &&
            message.timestamp > 0L &&
            editAgeAtOpen in 0L until ChatRepository.MESSAGE_EDIT_WINDOW_MS
        ) {
            delay((ChatRepository.MESSAGE_EDIT_WINDOW_MS - editAgeAtOpen).milliseconds)
            currentTime.longValue = FirebaseServerClock.now()
        }
    }

    val editAgeMillis = currentTime.longValue - message.timestamp
    val editAllowed =
        isOwnMessage &&
            message.timestamp > 0L &&
            editAgeMillis in 0L until ChatRepository.MESSAGE_EDIT_WINDOW_MS

    val showInfoState = remember(message.id) {
        mutableStateOf(false)
    }
    val confirmingDeleteState = remember(message.id) {
        mutableStateOf(false)
    }
    val confirmingDeleteForMeState = remember(message.id) {
        mutableStateOf(false)
    }
    val popupOnLeft = remember(message.id) {
        kotlin.random.Random.nextBoolean()
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
            modifier = Modifier.widthIn(
                min = if (showInfoState.value) 220.dp else 190.dp,
                max = if (showInfoState.value) 280.dp else 228.dp
            ),
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
                if (showInfoState.value) {
                    MessageInfoContent(
                        message = message,
                        isOwnMessage = isOwnMessage,
                        deliveredTimestamp = deliveredTimestamp,
                        readTimestamp = readTimestamp,
                        seenByCount = seenByCount,
                        onBack = { showInfoState.value = false },
                        onDismiss = onDismiss
                    )
                } else if (confirmingDeleteState.value || confirmingDeleteForMeState.value) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (confirmingDeleteForMeState.value) {
                                "Delete this message for you?"
                            } else {
                                "Delete this message for everyone?"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = if (confirmingDeleteForMeState.value) {
                                "It will disappear from your chat only."
                            } else {
                                "This message will be removed from all participants' chats."
                            },
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
                        onClick = {
                            confirmingDeleteState.value = false
                            confirmingDeleteForMeState.value = false
                        }
                    )
                    MessageActionRow(
                        icon = Icons.Outlined.DeleteOutline,
                        title = if (confirmingDeleteForMeState.value) "Delete for me" else "Delete for everyone",
                        destructive = true,
                        onClick = if (confirmingDeleteForMeState.value) onDeleteForMe else onDeleteForEveryone
                    )
                } else {
                    MessageActionRow(
                        icon = Icons.AutoMirrored.Outlined.Reply,
                        title = "Reply",
                        onClick = onReply
                    )
                    MessageActionRow(
                        icon = Icons.Outlined.Info,
                        title = "Info",
                        onClick = { showInfoState.value = true }
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
                            icon = Icons.Outlined.Edit,
                            title = "Edit",
                            onClick = onEdit
                        )
                    }
                    MessageActionRow(
                        icon = Icons.Outlined.SelectAll,
                        title = "Select messages",
                        onClick = onSelectMessages
                    )
                    MessageActionRow(
                        icon = Icons.Outlined.DeleteOutline,
                        title = "Delete for me",
                        onClick = { confirmingDeleteForMeState.value = true }
                    )
                    if (isOwnMessage) {
                        MessageActionRow(
                            icon = Icons.Outlined.DeleteOutline,
                            title = "Delete for everyone",
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
private fun MessageInfoContent(
    message: Message,
    isOwnMessage: Boolean,
    deliveredTimestamp: Long,
    readTimestamp: Long,
    seenByCount: Int,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDelivered =
        isOwnMessage &&
            deliveredTimestamp > 0L &&
            message.timestamp > 0L &&
            deliveredTimestamp >= message.timestamp

    val isSeen =
        isOwnMessage &&
            readTimestamp > 0L &&
            message.timestamp > 0L &&
            readTimestamp >= message.timestamp

    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "‹ Back",
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 7.dp, horizontal = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Message info",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 7.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Close",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ) {
            Text(
                text = message.text.ifBlank { "No text content" },
                modifier = Modifier.padding(11.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            MessageInfoRow(
                icon = Icons.Outlined.Schedule,
                label = if (isOwnMessage) "Sent" else "Received",
                value = formatMessageTime(message.timestamp)
            )

            if (isOwnMessage) {
                MessageInfoRow(
                    icon = Icons.Outlined.DoneAll,
                    label = "Delivered",
                    value = if (isDelivered) {
                        formatMessageTime(deliveredTimestamp)
                    } else {
                        "Not delivered yet"
                    },
                    highlighted = isDelivered
                )

                MessageInfoRow(
                    icon = Icons.Outlined.Visibility,
                    label = "Read",
                    value = when {
                        !isSeen -> "Not read yet"
                        seenByCount > 1 -> "Read by $seenByCount people"
                        else -> "Read"
                    },
                    highlighted = isSeen
                )
            }

            if (message.edited) {
                MessageInfoRow(
                    icon = Icons.Outlined.Edit,
                    label = "Edited",
                    value = "Yes"
                )
            }
        }
    }
}

@Composable
private fun MessageInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    highlighted: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(11.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (highlighted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 9.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
