package com.example.inchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.crypto.EncryptedMessageDeliveryStatus
import com.example.inchat.data.model.Message
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ChatMessageList(
    messages: List<Message>,
    messagesLoaded: Boolean,
    hasOlderMessages: Boolean = false,
    isLoadingOlderMessages: Boolean = false,
    onLoadOlderMessages: () -> Unit = {},
    currentUserId: String,
    otherUserNickname: String,
    blockState: BlockState,
    messagingBlocked: Boolean,
    otherUserDeliveredTimestamp: Long,
    otherUserReadTimestamp: Long,
    pendingMessageIds: Set<String>,
    isConnected: Boolean,
    showSenderNames: Boolean = false,
    readReceiptsEnabled: Boolean = true,
    chatTheme: ChatTheme,
    onReply: (Message) -> Unit,
    onLongClick: (Message) -> Unit,
    onReactionClick: (Message, String) -> Unit,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    selectedMessageIds: Set<String> = emptySet(),
    onToggleMessageSelection: (Message) -> Unit = {},
    encryptedMessageIds: Set<String> = emptySet(),
    encryptedDeliveryStatuses: Map<String, EncryptedMessageDeliveryStatus> = emptyMap(),
    isOtherUserTyping: Boolean = false,
) {

    val listState =
        rememberLazyListState()

    val coroutineScope =
        rememberCoroutineScope()

    var showScrollToLatest by remember {
        mutableStateOf(false)
    }

    // Match shadcn's MessageScrollerButton: only show the control when
    // the reader is away from the live edge; never steal scroll focus.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex =
                layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val lastContentItemIndex =
                layoutInfo.totalItemsCount - if (isOtherUserTyping) 2 else 1

            lastContentItemIndex >= 0 && lastVisibleIndex < lastContentItemIndex
        }
            .distinctUntilChanged()
            .collect { showScrollToLatest = it }
    }

    val density = LocalDensity.current

    // Read IME insets during composition; coroutine effects use this snapshot.
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottom > 0


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

    // Only animate messages arriving after the initial history snapshot.
    var enteringMessageId by remember { mutableStateOf<String?>(null) }
    var enteringMessageFromBottom by remember { mutableStateOf(false) }
    var observedLatestMessageId by remember { mutableStateOf<String?>(null) }
    var hasObservedInitialSnapshot by remember { mutableStateOf(false) }

    LaunchedEffect(messagesLoaded, messages.lastOrNull()?.id) {
        if (!messagesLoaded) return@LaunchedEffect
        val latest = messages.lastOrNull()
        val latestId = latest?.id
        val previousId = observedLatestMessageId
        if (!hasObservedInitialSnapshot) {
            observedLatestMessageId = latestId
            hasObservedInitialSnapshot = true
        } else if (latestId != null && latestId != previousId) {
            enteringMessageId = latestId
            enteringMessageFromBottom = latest.senderId == currentUserId
            observedLatestMessageId = latestId
            kotlinx.coroutines.delay(520)
            if (enteringMessageId == latestId) enteringMessageId = null
        }
    }

    /*
     * Preserve the bottom anchor while the IME animates, but only if
     * the user was already reading the latest messages. A manual drag
     * while the keyboard is open releases this anchor.
     */
    var keepBottomDuringIme by remember {
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
     *
     * The chat only follows newly received messages when the
     * user is already at (or very close to) the bottom.
     *
     * A newly sent message from the current user is always
     * followed so the sender can immediately see their message.
     */
    LaunchedEffect(
        messagesLoaded,
        hasOlderMessages,
        messages.size,
        messages.lastOrNull()?.id,
        isOtherUserTyping
    ) {

        if (
            !messagesLoaded
        ) {

            return@LaunchedEffect
        }

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

            keepBottomDuringIme =
                false

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
                    separatorCount +
                    if (hasOlderMessages) 1 else 0

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

            withFrameNanos { }
            val latestItemIndex = listState.layoutInfo.totalItemsCount - 1
            if (latestItemIndex >= 0) {
                listState.scrollToItem(latestItemIndex, Int.MAX_VALUE)
            }

            keepBottomDuringIme = true

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
            if (newOwnMessage || imeVisible) {
                // Avoid competing animations while the keyboard is moving
                // or when the user sends a message and the IME is dismissed.
                withFrameNanos { }
                val latestItemIndex = listState.layoutInfo.totalItemsCount - 1
                if (latestItemIndex >= 0) {
                    listState.scrollToItem(latestItemIndex, Int.MAX_VALUE)
                }
            } else {
                val latestItemIndex = listState.layoutInfo.totalItemsCount - 1
                if (latestItemIndex >= 0) {
                    listState.animateScrollToItem(latestItemIndex)
                    withFrameNanos { }
                    listState.scrollToItem(latestItemIndex, Int.MAX_VALUE)
                }
            }
        }

        previousMessageCount =
            messages.size

        previousLastMessageId =
            currentLastMessageId
    }

    /*
     * When typing starts, the indicator is appended as the final LazyColumn
     * item. If the reader was at the latest message immediately beforehand,
     * move to that new final row. Do not pull someone away from older history.
     */
    LaunchedEffect(isOtherUserTyping, messagesLoaded) {
        if (isOtherUserTyping && messagesLoaded && messages.isNotEmpty()) {
            withFrameNanos { }
            val layoutInfo = listState.layoutInfo
            val typingItemIndex = layoutInfo.totalItemsCount - 1
            val lastMessageItemIndex = typingItemIndex - 1
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val wasAtLiveEdge = lastVisibleIndex >= lastMessageItemIndex

            if (typingItemIndex >= 0 && wasAtLiveEdge) {
                listState.animateScrollToItem(typingItemIndex)
                withFrameNanos { }
                listState.scrollToItem(typingItemIndex, Int.MAX_VALUE)
            }
        }
    }

    /*
     * Remember whether the user was already at the bottom while the
     * keyboard is hidden. The viewport gets shorter as the IME rises;
     * keep the newest message anchored without animating the list itself.
     */
    LaunchedEffect(listState, imeVisible) {
        if (!imeVisible) {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val lastVisibleIndex =
                    layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1

                layoutInfo.totalItemsCount > 0 &&
                        lastVisibleIndex >= layoutInfo.totalItemsCount - 2
            }.collect { isNearBottom ->
                keepBottomDuringIme = isNearBottom
            }
        }
    }

    /*
     * IME insets animate frame by frame. Follow that resize with an
     * immediate bottom correction only when the chat was already at the
     * bottom. This avoids the old animateScrollToItem jump on keyboard open.
     */
    LaunchedEffect(listState, messagesLoaded, imeVisible) {
        if (imeVisible && keepBottomDuringIme && messagesLoaded) {
            snapshotFlow {
                listState.layoutInfo.viewportSize.height
            }
                .distinctUntilChanged()
                .collect {
                    // Wait for the resized viewport to be measured, then
                    // restore the bottom anchor without an animated scroll.
                    withFrameNanos { }
                    val lastItemIndex =
                        listState.layoutInfo.totalItemsCount - 1

                    if (lastItemIndex >= 0) {
                        listState.scrollToItem(lastItemIndex)
                    }
                }
        }
    }

    /*
     * If the user deliberately scrolls up while the keyboard is open,
     * stop forcing the list to the newest message during the remaining
     * keyboard animation or when it is dismissed.
     */
    LaunchedEffect(listState, imeVisible) {
        if (imeVisible) {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    keepBottomDuringIme = false
                }
            }
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
                Modifier
                    .fillMaxSize()
        ) {

            if (
                !messagesLoaded
            ) {

                /*
                 * Keep the wallpaper completely stable while the
                 * first Firebase snapshot is being delivered.
                 */
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                )

            } else if (
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
                                    "You blocked $otherUserNickname."

                                BlockState.THEY_BLOCKED_ME ->
                                    "$otherUserNickname has blocked you."

                                BlockState.NONE ->
                                    "No messages yet.\n" +
                                            "Say hi to $otherUserNickname!"
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
                            .alpha(
                                if (
                                    initialMessagesPositioned
                                ) {
                                    1f
                                } else {
                                    0f
                                }
                            )
                            .padding(
                                horizontal =
                                    12.dp
                            ),

                    verticalArrangement =
                        Arrangement.spacedBy(
                            4.dp
                        ),

                    contentPadding =
                        PaddingValues(
                            top = 14.dp,
                            bottom = 14.dp
                        )
                ) {

                    if (hasOlderMessages) {
                        item(
                            key = "load_earlier_messages",
                            contentType = "load_earlier_messages"
                        ) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(
                                    onClick = onLoadOlderMessages,
                                    enabled = !isLoadingOlderMessages
                                ) {
                                    Text(
                                        if (isLoadingOlderMessages) {
                                            "Loading earlier messages…"
                                        } else {
                                            "Load earlier messages"
                                        }
                                    )
                                }
                            }
                        }
                    }

                    messages.forEachIndexed {
                            index,
                            message ->

                        val previousMessage =
                            messages
                                .getOrNull(
                                    index - 1
                                )

                        // Calculate each adjacent day comparison once. The
                        // previous-day result is shared by the separator and
                        // message-group checks below.
                        val sameDayAsPrevious =
                            previousMessage != null &&
                                    isSameCalendarDay(
                                        previousMessage.timestamp,
                                        message.timestamp
                                    )

                        val showDaySeparator =
                            previousMessage == null || !sameDayAsPrevious

                        val isSystemEvent = message.systemEvent.isNotBlank()
                        val isFirstInGroup =
                            isSystemEvent ||
                                    previousMessage == null ||
                                    previousMessage.systemEvent.isNotBlank() ||
                                    previousMessage.senderId != message.senderId ||
                                    !sameDayAsPrevious

                        val nextMessage = messages.getOrNull(index + 1)
                        val sameDayAsNext =
                            nextMessage != null &&
                                    !isSystemEvent &&
                                    nextMessage.systemEvent.isBlank() &&
                                    nextMessage.senderId == message.senderId &&
                                    isSameCalendarDay(
                                        message.timestamp,
                                        nextMessage.timestamp
                                    )

                        val isLastInGroup =
                            isSystemEvent ||
                                    nextMessage == null ||
                                    nextMessage.systemEvent.isNotBlank() ||
                                    nextMessage.senderId != message.senderId ||
                                    !sameDayAsNext

                        if (
                            showDaySeparator &&
                            message.timestamp > 0L
                        ) {

                            item(
                                key = "day_${getDayKey(message.timestamp)}",
                                contentType = "day_separator"
                            ) {

                                DaySeparator(

                                    timestamp =
                                        message.timestamp
                                )
                            }
                        }

                        item(
                            key = message.id,
                            contentType = if (message.systemEvent.isNotBlank()) {
                                "system_event"
                            } else {
                                "message_bubble"
                            }
                        ) {
                            if (message.systemEvent.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = message.text,
                                        modifier = Modifier.padding(horizontal = 20.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {


                            val isMe =
                                message.senderId ==
                                        currentUserId

                            val deliveryStatus =
                                if (isMe) {

                                    getMessageDeliveryStatus(

                                        message =
                                            message,

                                        otherUserDeliveredTimestamp =
                                            otherUserDeliveredTimestamp,

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

                            val encryptedDeliveryStatus =
                                if (isMe && message.id in encryptedMessageIds) {
                                    encryptedDeliveryStatuses[message.id]
                                        ?: EncryptedMessageDeliveryStatus.NOT_TRACKED
                                } else {
                                    null
                                }

                            if (message.id == enteringMessageId) {
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.94f) +
                                        if (enteringMessageFromBottom) {
                                            slideInVertically(
                                                animationSpec = tween(320),
                                                initialOffsetY = { it / 2 }
                                            )
                                        } else {
                                            slideInHorizontally(
                                                animationSpec = tween(320),
                                                initialOffsetX = { -it / 3 }
                                            )
                                        }
                                ) {
                                SwipeableMessageBubble(
    
                                    message =
                                        message,
    
                                    isMe =
                                        isMe,
    
                                    deliveryStatus =
                                        deliveryStatus,
    
                                    encryptedDeliveryStatus =
                                        encryptedDeliveryStatus,
    
                                    readTimestamp =
                                        otherUserReadTimestamp,
    
                                    currentTimeMillis =
                                        deliveryStatusClock,
    
                                    currentUserId =
                                        currentUserId,
    
                                    chatTheme =
                                        chatTheme,
    
    
                                    isFirstInGroup =
                                        isFirstInGroup,
    
                                    isLastInGroup =
                                        isLastInGroup,
    
                                    showSenderName =
                                        showSenderNames && !isMe && isFirstInGroup,
    
                                    isSelected = message.id in selectedMessageIds,
                                    onClick = {
                                        if (selectedMessageIds.isNotEmpty()) {
                                            onToggleMessageSelection(message)
                                        }
                                    },
    
                                    onReply = {
                                        if (
                                            !messagingBlocked &&
                                            selectedMessageIds.isEmpty()
                                        ) {
                                            onReply(message)
                                        }
                                    },
    
                                    onLongClick = {
                                        if (!messagingBlocked) {
                                            if (selectedMessageIds.isNotEmpty()) {
                                                onToggleMessageSelection(message)
                                            } else {
                                                onLongClick(message)
                                            }
                                        }
                                    },
    
                                    onReactionClick = { reaction ->
                                        if (
                                            !messagingBlocked &&
                                            selectedMessageIds.isEmpty()
                                        ) {
                                            onReactionClick(message, reaction)
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
                                                                    separatorCount +
                                                                    if (hasOlderMessages) 1 else 0
                                                        )
                                                }
                                        }
                                    }
                                )
                                }
                            } else {
                                SwipeableMessageBubble(
    
                                    message =
                                        message,
    
                                    isMe =
                                        isMe,
    
                                    deliveryStatus =
                                        deliveryStatus,
    
                                    encryptedDeliveryStatus =
                                        encryptedDeliveryStatus,
    
                                    readTimestamp =
                                        otherUserReadTimestamp,
    
                                    currentTimeMillis =
                                        deliveryStatusClock,
    
                                    currentUserId =
                                        currentUserId,
    
                                    chatTheme =
                                        chatTheme,
    
    
                                    isFirstInGroup =
                                        isFirstInGroup,
    
                                    isLastInGroup =
                                        isLastInGroup,
    
                                    showSenderName =
                                        showSenderNames && !isMe && isFirstInGroup,
    
                                    isSelected = message.id in selectedMessageIds,
                                    onClick = {
                                        if (selectedMessageIds.isNotEmpty()) {
                                            onToggleMessageSelection(message)
                                        }
                                    },
    
                                    onReply = {
                                        if (
                                            !messagingBlocked &&
                                            selectedMessageIds.isEmpty()
                                        ) {
                                            onReply(message)
                                        }
                                    },
    
                                    onLongClick = {
                                        if (!messagingBlocked) {
                                            if (selectedMessageIds.isNotEmpty()) {
                                                onToggleMessageSelection(message)
                                            } else {
                                                onLongClick(message)
                                            }
                                        }
                                    },
    
                                    onReactionClick = { reaction ->
                                        if (
                                            !messagingBlocked &&
                                            selectedMessageIds.isEmpty()
                                        ) {
                                            onReactionClick(message, reaction)
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
                                                                    separatorCount +
                                                                    if (hasOlderMessages) 1 else 0
                                                        )
                                                }
                                        }
                                    }
                                )
                            }
                        
                            }
                        }
                    }
                    if (isOtherUserTyping && !messagingBlocked) {
                        item(
                            key = "typing_indicator",
                            contentType = "typing_indicator"
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp, bottom = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                TypingIndicator(chatTheme = chatTheme)
                            }
                        }
                    }
                }



                AnimatedVisibility(
                    visible = showScrollToLatest && initialMessagesPositioned,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        tonalElevation = 3.dp
                    ) {
                        IconButton(
                            onClick = {
                                val latestIndex = listState.layoutInfo.totalItemsCount - 1
                                if (latestIndex >= 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(latestIndex)
                                        withFrameNanos { }
                                        val settledLastIndex = listState.layoutInfo.totalItemsCount - 1
                                        if (settledLastIndex >= 0) {
                                            listState.scrollToItem(settledLastIndex, Int.MAX_VALUE)
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Jump to latest messages"
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun TypingIndicator(chatTheme: ChatTheme) {
    val transition = rememberInfiniteTransition(label = "typing-indicator")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620),
            repeatMode = RepeatMode.Reverse
        ),
        label = "typing-dots-alpha"
    )

    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 20.dp, topEnd = 20.dp, bottomStart = 7.dp, bottomEnd = 20.dp
        ),
        color = chatTheme.incomingBubbleColor,
        contentColor = chatTheme.incomingTextColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    ) {
        Text(
            text = "•••",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = chatTheme.statusAccentColor.copy(alpha = pulse),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        )
    }
}
