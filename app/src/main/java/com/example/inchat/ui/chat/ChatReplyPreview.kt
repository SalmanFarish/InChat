package com.example.inchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo

@Composable
fun ReplyMessagePreview(
    reply: ReplyTo,
    isMe: Boolean,
    onClick: () -> Unit
) {

    Column(

        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        10.dp
                    )
                )
                .clickable(
                    onClick =
                        onClick
                )
                .background(

                    if (
                        isMe
                    ) {

                        MaterialTheme
                            .colorScheme
                            .onPrimary
                            .copy(
                                alpha =
                                    0.12f
                            )

                    } else {

                        MaterialTheme
                            .colorScheme
                            .onSurface
                            .copy(
                                alpha =
                                    0.07f
                            )
                    }
                )
                .padding(
                    horizontal =
                        9.dp,

                    vertical =
                        7.dp
                )
    ) {

        Text(

            text =
                "@${reply.senderNickname}",

            style =
                MaterialTheme
                    .typography
                    .labelSmall,

            fontWeight =
                FontWeight.SemiBold,

            color =
                if (
                    isMe
                ) {

                    MaterialTheme
                        .colorScheme
                        .onPrimary

                } else {

                    MaterialTheme
                        .colorScheme
                        .onSurface
                }
        )

        Spacer(
            modifier =
                Modifier.height(
                    2.dp
                )
        )

        Text(

            text =
                reply.text,

            maxLines =
                2,

            style =
                MaterialTheme
                    .typography
                    .bodySmall,

            color =
                if (
                    isMe
                ) {

                    MaterialTheme
                        .colorScheme
                        .onPrimary

                } else {

                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
                }
        )
    }
}

@Composable
fun ReplyComposerPreview(
    message: Message,
    onCancel: () -> Unit
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal =
                        12.dp,

                    vertical =
                        6.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(

            modifier =
                Modifier
                    .weight(
                        1f
                    )
                    .clip(
                        RoundedCornerShape(
                            12.dp
                        )
                    )
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    )
                    .padding(
                        horizontal =
                            12.dp,

                        vertical =
                            8.dp
                    )
        ) {

            Text(

                text =
                    "Replying to @${message.senderNickname}",

                style =
                    MaterialTheme
                        .typography
                        .labelSmall,

                fontWeight =
                    FontWeight.SemiBold,

                color =
                    MaterialTheme
                        .colorScheme
                        .primary
            )

            Spacer(
                modifier =
                    Modifier.height(
                        2.dp
                    )
            )

            Text(

                text =
                    message.text,

                maxLines =
                    2,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        IconButton(

            onClick =
                onCancel
        ) {

            Icon(

                imageVector =
                    Icons.Default.Close,

                contentDescription =
                    "Cancel reply"
            )
        }
    }
}

@Composable
fun EditComposerPreview(
    message: Message,
    onCancel: () -> Unit
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal =
                        12.dp,

                    vertical =
                        6.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(

            modifier =
                Modifier
                    .weight(
                        1f
                    )
                    .clip(
                        RoundedCornerShape(
                            12.dp
                        )
                    )
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    )
                    .padding(
                        horizontal =
                            12.dp,

                        vertical =
                            8.dp
                    )
        ) {

            Text(

                text =
                    "Editing message",

                style =
                    MaterialTheme
                        .typography
                        .labelSmall,

                fontWeight =
                    FontWeight.SemiBold,

                color =
                    MaterialTheme
                        .colorScheme
                        .primary
            )

            Spacer(
                modifier =
                    Modifier.height(
                        2.dp
                    )
            )

            Text(

                text =
                    message.text,

                maxLines =
                    2,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        IconButton(

            onClick =
                onCancel
        ) {

            Icon(

                imageVector =
                    Icons.Default.Close,

                contentDescription =
                    "Cancel edit"
            )
        }
    }
}