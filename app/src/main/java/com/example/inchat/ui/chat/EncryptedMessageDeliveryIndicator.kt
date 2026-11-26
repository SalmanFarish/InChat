package com.example.inchat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (fadeIn(tween(170)) + scaleIn(initialScale = 0.72f)) togetherWith
                fadeOut(tween(110))
        },
        label = "encrypted-delivery-state"
    ) { current ->
        when (current) {
            EncryptedMessageDeliveryStatus.NOT_TRACKED -> Unit
            EncryptedMessageDeliveryStatus.WAITING,
            EncryptedMessageDeliveryStatus.FAILED ->
                Text(text = "!", fontSize = 11.sp, color = tint, maxLines = 1)
            EncryptedMessageDeliveryStatus.SENDING -> DeliveryPulse(tint)
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
        }
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
