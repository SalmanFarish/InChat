package com.example.inchat.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun InChatProfileAvatar(
    profilePhotoUrl: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 54.dp,
    contentDescription: String? = "Profile picture"
) {

    var imageLoadFailed by
    remember(
        profilePhotoUrl
    ) {
        mutableStateOf(false)
    }

    if (
        profilePhotoUrl.isBlank() ||
        imageLoadFailed
    ) {

        Box(

            modifier =
                modifier
                    .clip(
                        CircleShape
                    )
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    ),

            contentAlignment =
                Alignment.Center
        ) {

            Icon(

                imageVector =
                    Icons.Default.Person,

                contentDescription =
                    contentDescription,

                modifier =
                    Modifier.size(
                        iconSize
                    ),

                tint =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

    } else {

        AsyncImage(

            model =
                profilePhotoUrl,

            contentDescription =
                contentDescription,

            contentScale =
                ContentScale.Crop,

            modifier =
                modifier
                    .clip(
                        CircleShape
                    ),

            onError = {
                imageLoadFailed =
                    true
            }
        )
    }
}