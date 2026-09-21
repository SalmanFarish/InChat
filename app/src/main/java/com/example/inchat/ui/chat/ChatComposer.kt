package com.example.inchat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.inchat.data.model.Message

@Composable
fun ChatComposer(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    currentUserId: String,
    currentNickname: String,
    otherUserId: String,
    otherUserNickname: String,
    chatViewModel: ChatViewModel,
    blockState: BlockState,
    replyingTo: Message?,
    editingMessage: Message?,
    messagingBlocked: Boolean,
    onClearReply: () -> Unit,
    onClearEdit: () -> Unit,
    onActionMessage: (String) -> Unit,
    onMessageSent: () -> Unit
) {

    val keyboardController =
        LocalSoftwareKeyboardController.current

    val focusRequester =
        remember {
            FocusRequester()
        }

    val textScrollState =
        rememberScrollState()

    /*
     * =========================================================
     * FOCUS REPLY / EDIT
     * =========================================================
     */
    LaunchedEffect(
        replyingTo?.id,
        editingMessage?.id
    ) {

        if (
            !messagingBlocked &&
            (
                    replyingTo != null ||
                            editingMessage != null
                    )
        ) {

            focusRequester
                .requestFocus()

            keyboardController
                ?.show()
        }
    }

    /*
     * =========================================================
     * BACK HANDLER
     * =========================================================
     */
    BackHandler(

        enabled =
            replyingTo != null ||
                    editingMessage != null
    ) {

        if (
            editingMessage != null
        ) {

            onClearEdit()

        } else {

            onClearReply()
        }
    }

    Surface(

        color =
            MaterialTheme
                .colorScheme
                .background,

        tonalElevation =
            0.dp,

        shadowElevation =
            8.dp,

        modifier =
            Modifier
                .fillMaxWidth()
                /*
                 * Explicitly lift the entire composer above
                 * the software keyboard. The Activity already
                 * uses adjustResize; this handles the Compose
                 * bottom-bar inset itself.
                 */
                .imePadding()
    ) {

        Column {

            /*
             * =================================================
             * EDIT PREVIEW
             * =================================================
             */
            AnimatedVisibility(

                visible =
                    editingMessage != null &&
                            !messagingBlocked,

                enter =
                    fadeIn() +
                            slideInVertically(
                                initialOffsetY = {
                                    -it / 2
                                }
                            ),

                exit =
                    fadeOut() +
                            slideOutVertically(
                                targetOffsetY = {
                                    -it / 2
                                }
                            )
            ) {

                editingMessage
                    ?.let { message ->

                        EditComposerPreview(

                            message =
                                message,

                            onCancel =
                                onClearEdit
                        )
                    }
            }

            /*
             * =================================================
             * REPLY PREVIEW
             * =================================================
             */
            AnimatedVisibility(

                visible =
                    replyingTo != null &&
                            editingMessage == null &&
                            !messagingBlocked,

                enter =
                    fadeIn() +
                            slideInVertically(
                                initialOffsetY = {
                                    -it / 2
                                }
                            ),

                exit =
                    fadeOut() +
                            slideOutVertically(
                                targetOffsetY = {
                                    -it / 2
                                }
                            )
            ) {

                replyingTo
                    ?.let { message ->

                        ReplyComposerPreview(

                            message =
                                message,

                            onCancel =
                                onClearReply
                        )
                    }
            }

            /*
             * =================================================
             * BLOCKED MESSAGE
             * =================================================
             */
            if (
                messagingBlocked
            ) {

                Text(

                    text =
                        getBlockedMessage(
                            blockState,
                            otherUserNickname
                        ),

                    modifier =
                        Modifier.padding(

                            start =
                                16.dp,

                            top =
                                8.dp,

                            end =
                                16.dp,

                            bottom =
                                8.dp
                        ),

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .error
                )
            }

            /*
             * =================================================
             * MESSAGE INPUT
             * =================================================
             */
            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start =
                                10.dp,

                            top =
                                7.dp,

                            end =
                                10.dp,

                            bottom =
                                8.dp
                        ),

                verticalAlignment =
                    Alignment.Bottom
            ) {

                Surface(

                    modifier =
                        Modifier
                            .weight(1f)
                            .animateContentSize(),

                    shape =
                        RoundedCornerShape(
                            24.dp
                        ),

                    color =
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant,

                    tonalElevation =
                        0.dp
                ) {

                    BasicTextField(

                        value =
                            messageText,

                        onValueChange =
                            onMessageTextChange,

                        enabled =
                            !messagingBlocked,

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(
                                    focusRequester
                                )
                                .verticalScroll(
                                    textScrollState
                                )
                                .padding(
                                    horizontal =
                                        17.dp,

                                    vertical =
                                        13.dp
                                ),

                        textStyle =
                            MaterialTheme
                                .typography
                                .bodyLarge
                                .copy(
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant
                                ),

                        cursorBrush =
                            SolidColor(
                                MaterialTheme
                                    .colorScheme
                                    .primary
                            ),

                        keyboardOptions =
                            KeyboardOptions(
                                capitalization =
                                    KeyboardCapitalization.Sentences,

                                keyboardType =
                                    KeyboardType.Text,

                                imeAction =
                                    ImeAction.Default
                            ),

                        singleLine =
                            false,

                        maxLines =
                            5,

                        decorationBox = { innerTextField ->

                            if (
                                messageText.isBlank()
                            ) {

                                Text(

                                    text =
                                        when {

                                            editingMessage != null ->
                                                "Edit your message..."

                                            blockState ==
                                                    BlockState.I_BLOCKED_THEM ->
                                                "You blocked this user"

                                            blockState ==
                                                    BlockState.THEY_BLOCKED_ME ->
                                                "You can't message this user"

                                            else ->
                                                "Message..."
                                        },

                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyLarge,

                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant
                                            .copy(
                                                alpha =
                                                    0.62f
                                            )
                                )
                            }

                            innerTextField()
                        }
                    )
                }

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                val sendEnabled =
                    !messagingBlocked &&
                            messageText
                                .isNotBlank()

                val sendScale by
                animateFloatAsState(

                    targetValue =
                        if (
                            sendEnabled
                        ) {

                            1f

                        } else {

                            0.88f
                        },

                    animationSpec =
                        spring(),

                    label =
                        "sendButtonScale"
                )

                IconButton(

                    onClick = {

                        if (
                            messagingBlocked
                        ) {

                            return@IconButton
                        }

                        val text =
                            messageText
                                .trim()

                        if (
                            text.isBlank()
                        ) {

                            return@IconButton
                        }

                        val editing =
                            editingMessage

                        /*
                         * =================================================
                         * EDIT MESSAGE
                         * =================================================
                         */
                        if (
                            editing != null
                        ) {

                            chatViewModel
                                .editMessage(

                                    messageId =
                                        editing.id,

                                    newText =
                                        text,

                                    currentUserId =
                                        currentUserId

                                ) { success, error ->

                                    if (
                                        success
                                    ) {

                                        onClearEdit()

                                        keyboardController
                                            ?.hide()

                                    } else {

                                        onActionMessage(
                                            error
                                                ?: "Could not edit message"
                                        )
                                    }
                                }

                            return@IconButton
                        }

                        /*
                         * =================================================
                         * SEND MESSAGE
                         * =================================================
                         */
                        chatViewModel
                            .sendMessage(

                                text =
                                    text,

                                currentUserId =
                                    currentUserId,

                                senderNickname =
                                    currentNickname,

                                otherUserId =
                                    otherUserId,

                                otherUserNickname =
                                    otherUserNickname
                            )

                        onMessageSent()

                        keyboardController
                            ?.hide()
                    },

                    enabled =
                        sendEnabled,

                    modifier =
                        Modifier
                            .size(
                                50.dp
                            )
                            .graphicsLayer {

                                scaleX =
                                    sendScale

                                scaleY =
                                    sendScale
                            },

                    colors =
                        IconButtonDefaults
                            .iconButtonColors(

                                containerColor =
                                    MaterialTheme
                                        .colorScheme
                                        .primary,

                                contentColor =
                                    MaterialTheme
                                        .colorScheme
                                        .onPrimary,

                                disabledContainerColor =
                                    MaterialTheme
                                        .colorScheme
                                        .surfaceVariant,

                                disabledContentColor =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant
                            )
                ) {

                    Icon(

                        imageVector =
                            Icons.AutoMirrored
                                .Filled
                                .Send,

                        contentDescription =
                            if (
                                editingMessage != null
                            ) {

                                "Save edited message"

                            } else {

                                "Send message"
                            }
                    )
                }
            }
        }
    }
}
