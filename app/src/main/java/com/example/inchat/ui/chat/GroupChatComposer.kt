package com.example.inchat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.inchat.data.model.Message

@Composable
fun GroupChatComposer(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    currentUserId: String,
    currentNickname: String,
    groupViewModel: GroupChatViewModel,
    replyingTo: Message?,
    editingMessage: Message?,
    onClearReply: () -> Unit,
    onClearEdit: () -> Unit,
    onActionMessage: (String) -> Unit,
    onMessageSent: () -> Unit
) {

    val keyboardController =
        LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var savingEdit by remember { mutableStateOf(false) }

    LaunchedEffect(editingMessage?.id) {
        if (editingMessage == null) {
            savingEdit = false
        }
    }

    LaunchedEffect(replyingTo?.id, editingMessage?.id) {
        if (replyingTo != null || editingMessage != null) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    BackHandler(
        enabled = replyingTo != null || editingMessage != null
    ) {
        if (editingMessage != null) onClearEdit() else onClearReply()
        keyboardController?.hide()
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 0.7.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                )
            )
            .navigationBarsPadding()
    ) {

        Column {

            AnimatedVisibility(
                visible = editingMessage != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 })
            ) {
                editingMessage?.let { message ->
                    EditComposerPreview(
                        message = message,
                        onCancel = onClearEdit
                    )
                }
            }

            AnimatedVisibility(
                visible = replyingTo != null && editingMessage == null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 })
            ) {
                replyingTo?.let { message ->
                    ReplyComposerPreview(
                        message = message,
                        onCancel = onClearReply
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 10.dp,
                        top = 7.dp,
                        end = 10.dp,
                        bottom = 8.dp
                    ),
                verticalAlignment = Alignment.Bottom
            ) {

                Surface(
                    modifier = Modifier
                        .weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {

                    BasicTextField(
                        value = messageText,
                        onValueChange = onMessageTextChange,
                        enabled = !savingEdit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .padding(
                                horizontal = 17.dp,
                                vertical = 13.dp
                            ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        cursorBrush = SolidColor(
                            MaterialTheme.colorScheme.primary
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Default
                        ),
                        singleLine = false,
                        maxLines = 5,
                        decorationBox = { innerTextField ->
                            if (messageText.isBlank()) {
                                Text(
                                    text = if (editingMessage != null) {
                                        "Edit your message..."
                                    } else {
                                        "Message..."
                                    },
                                    color = MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant
                                        .copy(alpha = 0.62f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                val sendEnabled = messageText.isNotBlank() && !savingEdit
                ComposerSendButton(
                    enabled = sendEnabled,
                    isEditing = editingMessage != null,
                    onClick = {
                        val text = messageText.trim()
                        if (text.isBlank()) return@ComposerSendButton

                        val editing = editingMessage

                        if (editing != null) {
                            if (savingEdit) return@ComposerSendButton
                            savingEdit = true
                            groupViewModel.editMessage(
                                messageId = editing.id,
                                newText = text,
                                currentUserId = currentUserId
                            ) { success, error ->
                                savingEdit = false
                                if (success) {
                                    onClearEdit()
                                    keyboardController?.hide()
                                } else {
                                    onActionMessage(
                                        error ?: "Could not edit message."
                                    )
                                }
                            }
                        } else {
                            groupViewModel.sendMessage(
                                text = text,
                                currentUserId = currentUserId,
                                senderNickname = currentNickname
                            ) { success, error ->
                                if (!success) {
                                    onActionMessage(
                                        error ?: "Could not send message."
                                    )
                                }
                            }

                            /*
                             * Clear the composer immediately after handing
                             * the message to Firebase. Realtime Database
                             * writes are queued locally while offline, so
                             * the input must not wait for server confirmation.
                             */
                            onMessageSent()
                            keyboardController?.hide()
                        }
                    },
                )
            }
        }
    }
}