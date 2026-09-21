package com.example.inchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onCopied: () -> Unit
) {

    val context =
        LocalContext.current

    androidx.compose.ui.window.Dialog(

        onDismissRequest =
            onDismiss
    ) {

        Surface(

            shape =
                RoundedCornerShape(
                    22.dp
                ),

            color =
                MaterialTheme
                    .colorScheme
                    .surface,

            tonalElevation =
                6.dp,

            modifier =
                Modifier
                    .widthIn(
                        max =
                            370.dp
                    )
                    .animateContentSize()
        ) {

            Column(

                modifier =
                    Modifier.padding(
                        10.dp
                    )
            ) {

                Text(

                    text =
                        "React",

                    style =
                        MaterialTheme
                            .typography
                            .labelLarge,

                    fontWeight =
                        FontWeight.SemiBold,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,

                    modifier =
                        Modifier.padding(
                            start =
                                10.dp,

                            top =
                                6.dp,

                            end =
                                10.dp,

                            bottom =
                                6.dp
                        )
                )

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceEvenly
                ) {

                    ChatRepository
                        .SUPPORTED_REACTIONS
                        .forEach { reaction ->

                            val selected =
                                message
                                    .reactions[
                                    currentUserId
                                ] ==
                                        reaction

                            TextButton(

                                onClick = {

                                    onReaction(
                                        reaction
                                    )
                                }
                            ) {

                                Text(

                                    text =
                                        reaction,

                                    fontSize =
                                        if (
                                            selected
                                        ) {
                                            30.sp
                                        } else {
                                            27.sp
                                        },

                                    style =
                                        MaterialTheme
                                            .typography
                                            .headlineSmall
                                            .copy(
                                                platformStyle =
                                                    PlatformTextStyle(
                                                        includeFontPadding =
                                                            false
                                                    )
                                            )
                                )
                            }
                        }
                }

                Spacer(
                    modifier =
                        Modifier.height(
                            4.dp
                        )
                )

                DropdownMenuItem(

                    text = {

                        Text(
                            "Copy"
                        )
                    },

                    leadingIcon = {

                        Icon(

                            imageVector =
                                Icons.Default.ContentCopy,

                            contentDescription =
                                null
                        )
                    },

                    onClick = {

                        val clipboard =
                            context
                                .getSystemService(
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

                if (
                    isOwnMessage
                ) {

                    DropdownMenuItem(

                        text = {

                            Text(
                                "Edit"
                            )
                        },

                        leadingIcon = {

                            Icon(

                                imageVector =
                                    Icons.Default.Edit,

                                contentDescription =
                                    null
                            )
                        },

                        onClick =
                            onEdit
                    )

                    DropdownMenuItem(

                        text = {

                            Text(

                                text =
                                    "Delete",

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .error
                            )
                        },

                        leadingIcon = {

                            Icon(

                                imageVector =
                                    Icons.Default.Delete,

                                contentDescription =
                                    null,

                                tint =
                                    MaterialTheme
                                        .colorScheme
                                        .error
                            )
                        },

                        onClick =
                            onDelete
                    )
                }

                TextButton(

                    onClick =
                        onDismiss,

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        "Cancel"
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteMessageDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {

    AlertDialog(

        onDismissRequest =
            onDismiss,

        title = {

            Text(
                "Delete Message"
            )
        },

        text = {

            Text(
                "Do you want to delete this message?"
            )
        },

        confirmButton = {

            TextButton(
                onClick =
                    onConfirm
            ) {

                Text(

                    text =
                        "Delete",

                    color =
                        MaterialTheme
                            .colorScheme
                            .error
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick =
                    onDismiss
            ) {

                Text(
                    "Cancel"
                )
            }
        }
    )
}