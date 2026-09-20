package com.example.inchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.inchat.data.model.Message
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun DaySeparator(
    timestamp: Long
) {

    if (
        timestamp <= 0L
    ) {

        return
    }

    Box(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical =
                        12.dp
                ),

        contentAlignment =
            Alignment.Center
    ) {

        Surface(

            shape =
                RoundedCornerShape(
                    14.dp
                ),

            color =
                MaterialTheme
                    .colorScheme
                    .surfaceVariant
        ) {

            Text(

                text =
                    formatDaySeparator(
                        timestamp
                    ),

                modifier =
                    Modifier.padding(
                        start =
                            11.dp,

                        top =
                            5.dp,

                        end =
                            11.dp,

                        bottom =
                            5.dp
                    ),

                style =
                    MaterialTheme
                        .typography
                        .labelSmall,

                fontWeight =
                    FontWeight.Medium,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

fun countDaySeparators(
    messages: List<Message>
): Int {

    if (
        messages.isEmpty()
    ) {

        return 0
    }

    var count =
        0

    messages.forEachIndexed {
            index,
            message ->

        if (
            message.timestamp > 0L
        ) {

            val previous =
                messages
                    .getOrNull(
                        index - 1
                    )

            if (
                previous == null ||
                !isSameCalendarDay(
                    previous.timestamp,
                    message.timestamp
                )
            ) {

                count++
            }
        }
    }

    return count
}

fun countDaySeparatorsBeforeIndex(
    messages: List<Message>,
    targetIndex: Int
): Int {

    if (
        messages.isEmpty() ||
        targetIndex <= 0
    ) {

        return 0
    }

    var count =
        0

    for (
    index in 0 until targetIndex
    ) {

        val message =
            messages[index]

        if (
            message.timestamp <= 0L
        ) {

            continue
        }

        val previous =
            messages
                .getOrNull(
                    index - 1
                )

        if (
            previous == null ||
            !isSameCalendarDay(
                previous.timestamp,
                message.timestamp
            )
        ) {

            count++
        }
    }

    return count
}

fun getBlockedMessage(
    blockState: BlockState,
    otherUserNickname: String
): String {

    return when (
        blockState
    ) {

        BlockState.I_BLOCKED_THEM ->
            "You blocked @$otherUserNickname"

        BlockState.THEY_BLOCKED_ME ->
            "@$otherUserNickname has blocked you"

        BlockState.NONE ->
            ""
    }
}

fun formatMessageTime(
    timestamp: Long
): String {

    if (
        timestamp <= 0L
    ) {

        return ""
    }

    return SimpleDateFormat(
        "h:mm a",
        Locale.getDefault()
    ).format(
        Date(
            timestamp
        )
    )
}

fun isSameCalendarDay(
    firstTimestamp: Long,
    secondTimestamp: Long
): Boolean {

    if (
        firstTimestamp <= 0L ||
        secondTimestamp <= 0L
    ) {

        return true
    }

    val first =
        Calendar.getInstance().apply {

            timeInMillis =
                firstTimestamp
        }

    val second =
        Calendar.getInstance().apply {

            timeInMillis =
                secondTimestamp
        }

    return first.get(
        Calendar.ERA
    ) ==
            second.get(
                Calendar.ERA
            ) &&

            first.get(
                Calendar.YEAR
            ) ==
            second.get(
                Calendar.YEAR
            ) &&

            first.get(
                Calendar.DAY_OF_YEAR
            ) ==
            second.get(
                Calendar.DAY_OF_YEAR
            )
}

fun getDayKey(
    timestamp: Long
): String {

    val calendar =
        Calendar.getInstance().apply {

            timeInMillis =
                timestamp
        }

    return "${calendar.get(Calendar.YEAR)}_" +
            "${calendar.get(Calendar.DAY_OF_YEAR)}"
}

fun formatDaySeparator(
    timestamp: Long
): String {

    val todayCalendar =
        Calendar.getInstance()

    val yesterdayCalendar =
        Calendar.getInstance().apply {

            add(
                Calendar.DAY_OF_YEAR,
                -1
            )
        }

    val messageCalendar =
        Calendar.getInstance().apply {

            timeInMillis =
                timestamp
        }

    return when {

        isSameCalendarDay(
            messageCalendar.timeInMillis,
            todayCalendar.timeInMillis
        ) -> {

            "Today"
        }

        isSameCalendarDay(
            messageCalendar.timeInMillis,
            yesterdayCalendar.timeInMillis
        ) -> {

            "Yesterday"
        }

        else -> {

            SimpleDateFormat(
                "d MMM yyyy",
                Locale.getDefault()
            ).format(
                Date(
                    timestamp
                )
            )
        }
    }
}

fun formatLastSeen(
    timestamp: Long
): String {

    if (
        timestamp <= 0L
    ) {

        return "Offline"
    }

    val difference =
        System.currentTimeMillis() -
                timestamp

    val minute =
        60_000L

    val hour =
        60 *
                minute

    val day =
        24 *
                hour

    return when {

        difference < minute ->
            "Last seen just now"

        difference < hour ->
            "Last seen ${difference / minute} min ago"

        difference < day ->
            "Last seen ${difference / hour} hr ago"

        difference < 7 * day ->
            "Last seen ${difference / day} days ago"

        else ->
            "Last seen recently"
    }
}