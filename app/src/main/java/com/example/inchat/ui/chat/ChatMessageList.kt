package com.example.inchat.ui.chat

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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

    val density = LocalDensity.current

    var chatSwipeOffsetPx by
    remember {
        mutableFloatStateOf(0f)
    }

    val animatedChatSwipeOffsetPx by
    androidx.compose.animation.core.animateFloatAsState(
        targetValue =
            chatSwipeOffsetPx,
        animationSpec =
            androidx.compose.animation.core.spring(),
        label =
            "chatTimestampReveal"
    )

    var initialMessagesPositioned by
    remember {
        mutableStateOf(false)
    }

    var previousMessageCount by
    remember {
        mutableIntStateOf(0)
    }

    var previousLastMessageId by
    remember {
        mutableStateOf<String?>(null)
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
     *
     * The chat only follows newly received messages when the
     * user is already at (or very close to) the bottom.
     *
     * A newly sent message from the current user is always
     * followed so the sender can immediately see their message.
     */
    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.id
    ) {

        if (
            messages.isEmpty()
        ) {

            /*
             * The message list can become empty when navigating
             * to another conversation. The next non-empty list
             * should therefore be treated as a fresh chat load.
             */
            initialMessagesPositioned =
                false

            previousMessageCount =
                0

            previousLastMessageId =
                null

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

        /*
         * =====================================================
         * FIRST LOAD
         * =====================================================
         *
         * Always start at the newest message when a conversation
         * is first populated.
         */
        if (
            !initialMessagesPositioned
        ) {

            listState.scrollToItem(
                targetIndex
            )

            initialMessagesPositioned =
                true

            previousMessageCount =
                messages.size

            previousLastMessageId =
                messages.lastOrNull()
                    ?.id

            return@LaunchedEffect
        }

        /*
         * =====================================================
         * DETECT LIST CHANGE
         * =====================================================
         *
         * We intentionally don't react to every recomposition.
         * Only a change in message count or newest message ID
         * matters for message-following behavior.
         */
        val currentLastMessageId =
            messages
                .lastOrNull()
                ?.id

        val listChanged =
            messages.size !=
                    previousMessageCount ||
                    currentLastMessageId !=
                    previousLastMessageId

        if (
            !listChanged
        ) {

            return@LaunchedEffect
        }

        val newestMessage =
            messages.lastOrNull()

        val newOwnMessage =
            messages.size >
                    previousMessageCount &&
                    newestMessage != null &&
                    newestMessage.id !=
                    previousLastMessageId &&
                    newestMessage.senderId ==
                    currentUserId

        /*
         * =====================================================
         * DETERMINE WHETHER USER IS AT BOTTOM
         * =====================================================
         *
         * LazyColumn contains both day separators and messages,
         * so compare against the actual composed item count.
         *
         * Being one item away from the bottom still counts as
         * "at the bottom" for normal chat behavior.
         */
        val layoutInfo =
            listState.layoutInfo

        val totalItemsCount =
            layoutInfo.totalItemsCount

        val lastVisibleItemIndex =
            layoutInfo
                .visibleItemsInfo
                .lastOrNull()
                ?.index
                ?: -1

        val isNearBottom =
            totalItemsCount > 0 &&
                    lastVisibleItemIndex >=
                    totalItemsCount - 2

        /*
         * Follow the conversation when:
         *
         * 1. The user is already at the bottom, OR
         * 2. The user just sent a new message themselves.
         *
         * This prevents incoming messages from stealing the
         * user's reading position when they have scrolled upward.
         */
        if (
            isNearBottom ||
            newOwnMessage
        ) {

            listState.animateScrollToItem(
                targetIndex
            )
        }

        previousMessageCount =
            messages.size

        previousLastMessageId =
            currentLastMessageId
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
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = {
                                _,
                                dragAmount ->

                            if (dragAmount > 0f) {
                                chatSwipeOffsetPx =
                                    (
                                            chatSwipeOffsetPx +
                                                    dragAmount
                                            )
                                        .coerceIn(
                                            0f,
                                            with(density) {
                                                56.dp.toPx()
                                            }
                                        )
                            }
                        },
                        onDragEnd = {
                            chatSwipeOffsetPx = 0f
                        },
                        onDragCancel = {
                            chatSwipeOffsetPx = 0f
                        }
                    )
                }
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
                                    " @$otherUserNickname has blocked you."

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

                                chatSwipeOffsetPx =
                                    animatedChatSwipeOffsetPx,

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