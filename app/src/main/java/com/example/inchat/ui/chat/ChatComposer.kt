package com.example.inchat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
    chatReady: Boolean,
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

    var sendingMessage by remember {
        mutableStateOf(false)
    }
    var savingEdit by remember {
        mutableStateOf(false)
    }

    var sendAttemptSequence by remember {
        mutableIntStateOf(0)
    }
    val latestSendAttemptSequence by rememberUpdatedState(sendAttemptSequence)
    val latestComposerText by rememberUpdatedState(messageText)

    LaunchedEffect(editingMessage?.id) {
        if (editingMessage == null) {
            savingEdit = false
        }
    }

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

        keyboardController?.hide()
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
                /*
                 * The screen Scaffold owns IME insets. The composer
                 * only applies navigation-bar spacing here.
                 */
                .navigationBarsPadding()
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
                            .weight(1f),

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
                            !messagingBlocked && !sendingMessage && !savingEdit,

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(
                                    focusRequester
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
                    chatReady &&
                            !sendingMessage &&
                            !savingEdit &&
                            !messagingBlocked &&
                            messageText
                                .isNotBlank()

                ComposerSendButton(
                    enabled = sendEnabled,
                    isEditing = editingMessage != null,
                    onClick = {

                        if (
                            messagingBlocked
                        ) {

                            return@ComposerSendButton
                        }

                        val text =
                            messageText
                                .trim()

                        if (
                            text.isBlank()
                        ) {

                            return@ComposerSendButton
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
                            if (savingEdit) {
                                return@ComposerSendButton
                            }

                            savingEdit = true
                            chatViewModel
                                .editMessage(

                                    messageId =
                                        editing.id,

                                    newText =
                                        text,

                                    currentUserId =
                                        currentUserId

                                ) { success, error ->
                                    savingEdit = false

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

                            return@ComposerSendButton
                        }

                        /*
                         * =================================================
                         * SEND MESSAGE
                         * =================================================
                         */
                        sendingMessage = true
                        sendAttemptSequence += 1
                        val thisSendAttempt = sendAttemptSequence

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
                                    otherUserNickname,

                                onQueued = {
                                    // The message is now in Firebase's local
                                    // realtime queue and appears immediately.
                                    sendingMessage = false
                                    onMessageSent()
                                    keyboardController?.hide()
                                },

                                onResult = { success, error ->
                                    sendingMessage = false

                                    if (!success) {
                                        // Restore only this latest draft, and never
                                        // overwrite text typed or sent afterward.
                                        if (
                                            thisSendAttempt == latestSendAttemptSequence &&
                                            latestComposerText.isBlank()
                                        ) {
                                            onMessageTextChange(text)
                                        }
                                        onActionMessage(
                                            error ?: "Message could not be sent."
                                        )
                                    }
                                }
                            )
                    },
                )
            }
        }
    }
}


/**
 * Bitchat-inspired compact send control: a soft accent disc with an upward arrow
 * and a restrained press animation. The same control is shared by private and group chats.
 */
@Composable
internal fun ComposerSendButton(
    enabled: Boolean,
    isEditing: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "composerSendPressScale"
    )
    val activeColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (enabled) {
                        activeColor.copy(alpha = 0.20f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isEditing) {
                    Icons.Default.Check
                } else {
                    Icons.Filled.ArrowUpward
                },
                contentDescription = if (isEditing) {
                    "Save edited message"
                } else {
                    "Send message"
                },
                modifier = Modifier.size(20.dp),
                tint = if (enabled) {
                    activeColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
