package com.example.inchat.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.crypto.EncryptedMessageDeliveryStatus

/** Presentation for encrypted messages; intentionally separate from plaintext status logic. */
@Composable
internal fun EncryptedMessageDeliveryIndicator(
    status: EncryptedMessageDeliveryStatus,
    tint: Color,
) {
    when (status) {
        EncryptedMessageDeliveryStatus.NOT_TRACKED -> Unit
        EncryptedMessageDeliveryStatus.WAITING ->
            Text(text = "!", fontSize = 11.sp, color = tint, maxLines = 1)
        EncryptedMessageDeliveryStatus.SENDING ->
            Text(text = "…", fontSize = 12.sp, color = tint, maxLines = 1)
        EncryptedMessageDeliveryStatus.SENT ->
            Icon(
                imageVector = Icons.Filled.Done,
                contentDescription = "Sent",
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        EncryptedMessageDeliveryStatus.DELIVERED ->
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Delivered",
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        EncryptedMessageDeliveryStatus.FAILED ->
            Text(text = "!", fontSize = 11.sp, color = tint, maxLines = 1)
    }
}

internal fun encryptedDeliveryStatusText(status: EncryptedMessageDeliveryStatus): String =
    when (status) {
        EncryptedMessageDeliveryStatus.NOT_TRACKED -> ""
        EncryptedMessageDeliveryStatus.WAITING -> "Waiting…"
        EncryptedMessageDeliveryStatus.SENDING -> "Sending…"
        EncryptedMessageDeliveryStatus.SENT -> "Sent"
        EncryptedMessageDeliveryStatus.DELIVERED -> "Delivered"
        EncryptedMessageDeliveryStatus.FAILED -> "Not sent"
    }
