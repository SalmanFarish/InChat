package com.example.inchat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.inchat.data.model.Message
import kotlinx.coroutines.launch

@Composable
fun ChatMessageList(
    messages: List<Message>,
    currentUserId: String,
    otherUserNickname: String,
    blockState: BlockState,
    messagingBlocked: Boolean,
    otherUserReadTimestamp: Long,
    pendingMessageIds: Set<String>,
    isConnected: Boolean,
    chatTheme: ChatTheme,
    onReply: (Message) -> Unit,
    onLongClick: (Message) -> Unit,
    innerPadding: androidx.compose.foundation.layout.PaddingValues
) {

    val listState =
        rememberLazyListState()

    val coroutineScope =
        rememberCoroutineScope()

    var initialMessagesPositioned by
    remember {
        mutableStateOf(false)
    }

    /*
     * Reading this value makes the composable recompose
     * when "Seen just now" changes into "Seen".
     */
    val deliveryStatusClock =
        rememberDeliveryStatusClock(
            otherUserReadTimestamp
        )

    /*
     * =========================================================
     * AUTO SCROLL
     * =========================================================
     */
    LaunchedEffect(
        messages.size
    ) {

        if (
            messages.isEmpty()
        ) {

            return@LaunchedEffect
        }

        val newestIndex =
            messages.lastIndex

        val separatorCount =
            countDaySeparators(
                messages
            )

        val targetIndex =
            newestIndex +
                    separatorCount

        if (
            !initialMessagesPositioned
        ) {

            listState.scrollToItem(
                targetIndex
            )

            initialMessagesPositioned =
                true

        } else {

            listState.animateScrollToItem(
                targetIndex
            )
        }
    }

    ChatWallpaper(

        theme =
            chatTheme,

        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    innerPadding
                )
    ) {

        Box(

            modifier =
                Modifier.fillMaxSize()
        ) {

            if (
                messages.isEmpty()
            ) {

                Box(

                    modifier =
                        Modifier.fillMaxSize(),

                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(

                        text =
                            when (
                                blockState
                            ) {

                                BlockState.I_BLOCKED_THEM ->
                                    "You blocked @$otherUserNickname."

                                BlockState.THEY_BLOCKED_ME ->
                                    "@$otherUserNickname has blocked you."

                                BlockState.NONE ->
                                    "No messages yet.\n" +
                                            "Say hi to @$otherUserNickname!"
                            },

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,

                        textAlign =
                            TextAlign.Center
                    )
                }

            } else {

                val latestOwnMessageId =
                    messages
                        .lastOrNull {
                            it.senderId ==
                                    currentUserId
                        }
                        ?.id

                /*
                 * Read the clock so Compose tracks it.
                 */
                if (
                    deliveryStatusClock < 0L
                ) {

                    return@ChatWallpaper
                }

                LazyColumn(

                    state =
                        listState,

                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal =
                                    12.dp
                            ),

                    verticalArrangement =
                        Arrangement.spacedBy(
                            5.dp
                        ),

                    contentPadding =
                        PaddingValues(
                            vertical =
                                14.dp
                        )
                ) {

                    messages.forEachIndexed {
                            index,
                            message ->

                        val previousMessage =
                            messages
                                .getOrNull(
                                    index - 1
                                )

                        val showDaySeparator =
                            previousMessage ==
                                    null ||
                                    !isSameCalendarDay(
                                        previousMessage.timestamp,
                                        message.timestamp
                                    )

                        if (
                            showDaySeparator &&
                            message.timestamp > 0L
                        ) {

                            item(

                                key =
                                    "day_${getDayKey(message.timestamp)}"
                            ) {

                                DaySeparator(

                                    timestamp =
                                        message.timestamp
                                )
                            }
                        }

                        item(

                            key =
                                message.id
                        ) {

                            val isMe =
                                message.senderId ==
                                        currentUserId

                            val deliveryStatus =
                                if (
                                    isMe &&
                                    message.id ==
                                    latestOwnMessageId
                                ) {

                                    getMessageDeliveryStatus(

                                        message =
                                            message,

                                        otherUserReadTimestamp =
                                            otherUserReadTimestamp,

                                        pendingMessageIds =
                                            pendingMessageIds,

                                        isConnected =
                                            isConnected,

                                        currentTimeMillis =
                                            deliveryStatusClock
                                    )

                                } else {

                                    MessageDeliveryStatus
                                        .NONE
                                }

                            SwipeableMessageBubble(

                                message =
                                    message,

                                isMe =
                                    isMe,

                                deliveryStatus =
                                    deliveryStatus,

                                currentUserId =
                                    currentUserId,

                                onReply = {

                                    if (
                                        !messagingBlocked
                                    ) {

                                        onReply(
                                            message
                                        )
                                    }
                                },

                                onLongClick = {

                                    if (
                                        !messagingBlocked
                                    ) {

                                        onLongClick(
                                            message
                                        )
                                    }
                                },

                                onQuotedReplyClick = {
                                        originalMessageId ->

                                    val targetIndex =
                                        messages
                                            .indexOfFirst {
                                                it.id ==
                                                        originalMessageId
                                            }

                                    if (
                                        targetIndex >=
                                        0
                                    ) {

                                        coroutineScope
                                            .launch {

                                                val separatorCount =
                                                    countDaySeparatorsBeforeIndex(
                                                        messages,
                                                        targetIndex
                                                    )

                                                listState
                                                    .animateScrollToItem(
                                                        targetIndex +
                                                                separatorCount
                                                    )
                                            }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}