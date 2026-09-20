package com.example.inchat.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.model.Presence

@Composable
fun PresenceStatus(
    presence: Presence,
    isTyping: Boolean
) {

    if (
        isTyping
    ) {

        Text(

            text =
                "typing…",

            style =
                MaterialTheme
                    .typography
                    .labelSmall,

            color =
                MaterialTheme
                    .colorScheme
                    .primary
        )

        return
    }

    if (
        presence.online
    ) {

        Row(

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(

                text =
                    "●",

                fontSize =
                    9.sp,

                color =
                    MaterialTheme
                        .colorScheme
                        .primary
            )

            Spacer(
                modifier =
                    Modifier.width(
                        4.dp
                    )
            )

            Text(

                text =
                    "Online",

                style =
                    MaterialTheme
                        .typography
                        .labelSmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

    } else {

        Text(

            text =
                formatLastSeen(
                    presence.lastSeen
                ),

            style =
                MaterialTheme
                    .typography
                    .labelSmall,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}