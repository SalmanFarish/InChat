package com.example.inchat.ui.profile

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
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

    val profilePhoto =
        remember(
            profilePhotoUrl
        ) {
            decodeDatabaseProfilePhoto(
                profilePhotoUrl
            )
        }

    var imageLoadFailed by
    remember(
        profilePhotoUrl
    ) {
        mutableStateOf(false)
    }

    /*
     * No photo at all.
     */
    if (
        profilePhotoUrl.isBlank()
    ) {

        EmptyProfileAvatar(
            modifier =
                modifier,

            iconSize =
                iconSize,

            contentDescription =
                contentDescription
        )

        return
    }

    /*
     * New free-plan database photo.
     *
     * It is stored as:
     *
     * data:image/jpeg;base64,...
     */
    if (
        profilePhoto != null
    ) {

        Image(

            bitmap =
                profilePhoto.asImageBitmap(),

            contentDescription =
                contentDescription,

            contentScale =
                ContentScale.Crop,

            modifier =
                modifier
                    .clip(
                        CircleShape
                    )
        )

        return
    }

    /*
     * Older profiles may still contain a Firebase Storage URL.
     *
     * Keep supporting those URLs so existing users do not
     * suddenly lose their profile picture.
     */
    if (
        imageLoadFailed
    ) {

        EmptyProfileAvatar(
            modifier =
                modifier,

            iconSize =
                iconSize,

            contentDescription =
                contentDescription
        )

        return
    }

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

/*
 * ============================================================
 * DATABASE PROFILE PHOTO DECODER
 * ============================================================
 *
 * Returns null when the supplied value is not a database
 * Base64 image. That allows the caller to fall back to the
 * older URL-based profile photo system.
 */
private fun decodeDatabaseProfilePhoto(
    value: String
): android.graphics.Bitmap? {

    if (
        !value.startsWith(
            "data:image/",
            ignoreCase = true
        )
    ) {

        return null
    }

    val separatorIndex =
        value.indexOf(
            ","
        )

    if (
        separatorIndex < 0
    ) {

        return null
    }

    val base64Data =
        value
            .substring(
                separatorIndex + 1
            )
            .trim()

    if (
        base64Data.isBlank()
    ) {

        return null
    }

    return try {

        val imageBytes =
            Base64.decode(
                base64Data,
                Base64.DEFAULT
            )

        BitmapFactory.decodeByteArray(
            imageBytes,
            0,
            imageBytes.size
        )

    } catch (
        _: Exception
    ) {

        null
    }
}

/*
 * ============================================================
 * EMPTY PROFILE AVATAR
 * ============================================================
 */

@Composable
private fun EmptyProfileAvatar(
    modifier: Modifier,
    iconSize: Dp,
    contentDescription: String?
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
}