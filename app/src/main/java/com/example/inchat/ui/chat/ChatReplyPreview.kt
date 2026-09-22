package com.example.inchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.inchat.data.model.Message
import com.example.inchat.data.model.ReplyTo

@Composable
fun ReplyMessagePreview(
    reply: ReplyTo,
    isMe: Boolean,
    chatTheme: ChatTheme,
    onClick: () -> Unit
) {

    val backgroundColor =
        if (
            isMe
        ) {

            chatTheme.outgoingTextColor
                .copy(
                    alpha =
                        0.10f
                )

        } else {

            chatTheme.incomingTextColor
                .copy(
                    alpha =
                        0.06f
                )
        }

    val primaryTextColor =
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

    val secondaryTextColor =
        if (
            isMe
        ) {

            MaterialTheme
                .colorScheme
                .onPrimary
                .copy(
                    alpha =
                        0.78f
                )

        } else {

            chatTheme.secondaryTextColor
        }

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        10.dp
                    )
                )
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick =
                        onClick
                )
                .background(
                    backgroundColor
                )
                .padding(
                    horizontal =
                        9.dp,

                    vertical =
                        6.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(

            modifier =
                Modifier
                    .width(
                        3.dp
                    )
                    .height(
                        30.dp
                    )
                    .clip(
                        RoundedCornerShape(
                            2.dp
                        )
                    )
                    .background(
                        chatTheme.outgoingBubbleColor
                    )
        )

        Spacer(
            modifier =
                Modifier.width(
                    8.dp
                )
        )

        Column(

            modifier =
                Modifier.widthIn(
                    max =
                        210.dp
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

                maxLines =
                    1,

                color =
                    primaryTextColor
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
                    1,

                overflow =
                    TextOverflow.Ellipsis,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    secondaryTextColor
            )
        }
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

        Row(

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
                            10.dp,

                        vertical =
                            9.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(

                modifier =
                    Modifier
                        .width(
                            3.dp
                        )
                        .height(
                            42.dp
                        )
                        .clip(
                            RoundedCornerShape(
                                2.dp
                            )
                        )
                        .background(
                            MaterialTheme
                                .colorScheme
                                .primary
                        )
            )

            Spacer(
                modifier =
                    Modifier.width(
                        9.dp
                    )
            )

            Column(

                modifier =
                    Modifier.weight(
                        1f
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

                    maxLines =
                        1,

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

                    overflow =
                        TextOverflow.Ellipsis,

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

                overflow =
                    TextOverflow.Ellipsis,

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