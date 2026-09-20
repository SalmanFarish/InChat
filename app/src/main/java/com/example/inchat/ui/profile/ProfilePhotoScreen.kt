package com.example.inchat.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

private const val PROFILE_PHOTO_OUTPUT_SIZE =
    512

private const val PROFILE_PHOTO_MAX_BYTES =
    200 * 1024

private const val PROFILE_PHOTO_INITIAL_QUALITY =
    90

private const val PROFILE_PHOTO_MIN_QUALITY =
    45

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePhotoScreen(
    currentPhotoData: String,
    onBackClick: () -> Unit,
    onSavePhoto: (ByteArray) -> Unit,
    onRemovePhoto: () -> Unit,
    isSaving: Boolean = false
) {
    val context =
        androidx.compose.ui.platform.LocalContext.current

    val scope =
        rememberCoroutineScope()

    var selectedBitmap by
    remember {
        mutableStateOf<Bitmap?>(null)
    }

    var zoom by
    remember {
        mutableStateOf(1f)
    }

    var offsetX by
    remember {
        mutableStateOf(0f)
    }

    var offsetY by
    remember {
        mutableStateOf(0f)
    }

    var previewSize by
    remember {
        mutableStateOf(
            IntSize.Zero
        )
    }

    var isLoadingPhoto by
    remember {
        mutableStateOf(false)
    }

    var errorMessage by
    remember {
        mutableStateOf<String?>(null)
    }

    val photoPickerLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .GetContent()
        ) { uri ->

            if (uri == null) {
                return@rememberLauncherForActivityResult
            }

            scope.launch {

                isLoadingPhoto =
                    true

                errorMessage =
                    null

                val bitmap =
                    withContext(
                        Dispatchers.IO
                    ) {

                        decodeSelectedBitmap(
                            contentResolver =
                                context.contentResolver,

                            uri =
                                uri
                        )
                    }

                isLoadingPhoto =
                    false

                if (bitmap == null) {

                    errorMessage =
                        "Could not open that image. Please choose another photo."

                    return@launch
                }

                selectedBitmap =
                    bitmap

                zoom =
                    1f

                offsetX =
                    0f

                offsetY =
                    0f
            }
        }

    /*
     * Load the existing database photo once when the screen
     * opens. This allows the user to reposition an existing
     * profile picture before saving it again.
     */
    LaunchedEffect(
        currentPhotoData
    ) {

        if (
            selectedBitmap != null ||
            currentPhotoData.isBlank()
        ) {
            return@LaunchedEffect
        }

        val bitmap =
            withContext(
                Dispatchers.Default
            ) {

                decodeDatabasePhoto(
                    currentPhotoData
                )
            }

        if (bitmap != null) {

            selectedBitmap =
                bitmap
        }
    }

    /*
     * Compose may still reference the Bitmap while the screen is
     * leaving composition. Do not call Bitmap.recycle() here;
     * letting the bitmap be garbage-collected avoids rendering a
     * recycled bitmap during navigation.
     */

    Scaffold(

        containerColor =
            MaterialTheme
                .colorScheme
                .background,

        topBar = {

            TopAppBar(

                title = {

                    Text(

                        text =
                            "Profile Photo",

                        fontWeight =
                            FontWeight.SemiBold
                    )
                },

                navigationIcon = {

                    IconButton(

                        onClick =
                            onBackClick,

                        enabled =
                            !isSaving &&
                                    !isLoadingPhoto
                    ) {

                        Icon(

                            imageVector =
                                Icons.AutoMirrored
                                    .Filled
                                    .ArrowBack,

                            contentDescription =
                                "Back"
                        )
                    }
                }
            )
        }

    ) { innerPadding ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        innerPadding
                    )
                    .padding(
                        horizontal =
                            20.dp,

                        vertical =
                            24.dp
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(

                text =
                    "Adjust your profile photo",

                style =
                    MaterialTheme
                        .typography
                        .headlineSmall,

                fontWeight =
                    FontWeight.Bold,

                textAlign =
                    TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )

            Text(

                text =
                    "Crop with the circle. Pinch to zoom, then drag to position.",

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,

                textAlign =
                    TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(
                        28.dp
                    )
            )

            Text(

                text =
                    "CROP & ADJUST",

                style =
                    MaterialTheme
                        .typography
                        .labelMedium,

                fontWeight =
                    FontWeight.SemiBold,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Spacer(
                modifier =
                    Modifier.height(
                        10.dp
                    )
            )

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(
                            1f
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                Box(

                    modifier =
                        Modifier
                            .size(
                                300.dp
                            )
                            .clip(
                                CircleShape
                            )
                            .background(
                                MaterialTheme
                                    .colorScheme
                                    .surfaceVariant
                            )
                            .onSizeChanged {
                                previewSize =
                                    it
                            }
                            .pointerInput(
                                selectedBitmap,
                                isSaving
                            ) {

                                detectTransformGestures {

                                        _, pan, gestureZoom, _ ->

                                    if (
                                        selectedBitmap == null ||
                                        isSaving ||
                                        isLoadingPhoto
                                    ) {
                                        return@detectTransformGestures
                                    }

                                    zoom =
                                        (
                                                zoom *
                                                        gestureZoom
                                                )
                                            .coerceIn(
                                                1f,
                                                4f
                                            )

                                    offsetX +=
                                        pan.x

                                    offsetY +=
                                        pan.y

                                    val maxOffsetX =
                                        max(
                                            0f,
                                            (
                                                    previewSize.width *
                                                            zoom -
                                                            previewSize.width
                                                    ) / 2f
                                        )

                                    val maxOffsetY =
                                        max(
                                            0f,
                                            (
                                                    previewSize.height *
                                                            zoom -
                                                            previewSize.height
                                                    ) / 2f
                                        )

                                    offsetX =
                                        offsetX.coerceIn(
                                            -maxOffsetX,
                                            maxOffsetX
                                        )

                                    offsetY =
                                        offsetY.coerceIn(
                                            -maxOffsetY,
                                            maxOffsetY
                                        )
                                }
                            },

                    contentAlignment =
                        Alignment.Center
                ) {

                    val bitmap =
                        selectedBitmap

                    if (bitmap != null) {

                        Image(

                            bitmap =
                                bitmap.asImageBitmap(),

                            contentDescription =
                                "Profile photo preview",

                            contentScale =
                                ContentScale.Crop,

                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {

                                        scaleX =
                                            zoom

                                        scaleY =
                                            zoom

                                        translationX =
                                            offsetX

                                        translationY =
                                            offsetY
                                    }
                        )

                    } else {

                        InChatProfileAvatar(

                            profilePhotoUrl =
                                currentPhotoData,

                            modifier =
                                Modifier.fillMaxSize(),

                            iconSize =
                                96.dp,

                            contentDescription =
                                "Current profile picture"
                        )
                    }

                    if (
                        isLoadingPhoto ||
                        isSaving
                    ) {

                        Box(

                            modifier =
                                Modifier.fillMaxSize(),

                            contentAlignment =
                                Alignment.Center
                        ) {

                            CircularProgressIndicator()
                        }
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.height(
                        22.dp
                    )
            )

            Row(

                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {

                OutlinedButton(

                    onClick = {

                        if (
                            !isSaving &&
                            !isLoadingPhoto
                        ) {

                            photoPickerLauncher
                                .launch(
                                    "image/*"
                                )
                        }
                    },

                    enabled =
                        !isSaving &&
                                !isLoadingPhoto,

                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {

                    Icon(

                        imageVector =
                            Icons.Default.PhotoLibrary,

                        contentDescription =
                            null
                    )

                    Spacer(
                        modifier =
                            Modifier.width(
                                8.dp
                            )
                    )

                    Text(
                        text =
                            "Choose Photo"
                    )
                }

                OutlinedButton(

                    onClick = {

                        if (
                            !isSaving
                        ) {
                            onRemovePhoto()
                        }
                    },

                    enabled =
                        !isSaving &&
                                currentPhotoData.isNotBlank(),

                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {

                    Icon(

                        imageVector =
                            Icons.Default.Delete,

                        contentDescription =
                            null
                    )

                    Spacer(
                        modifier =
                            Modifier.width(
                                8.dp
                            )
                    )

                    Text(
                        text =
                            "Remove"
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(
                        12.dp
                    )
            )

            Button(

                onClick = {

                    val bitmap =
                        selectedBitmap
                            ?: return@Button

                    if (
                        previewSize.width <= 0 ||
                        previewSize.height <= 0
                    ) {

                        errorMessage =
                            "Could not prepare the photo."

                        return@Button
                    }

                    scope.launch {

                        val croppedBytes =
                            withContext(
                                Dispatchers.Default
                            ) {

                                cropAndCompressProfilePhoto(
                                    bitmap =
                                        bitmap,

                                    previewSize =
                                        previewSize,

                                    zoom =
                                        zoom,

                                    offsetX =
                                        offsetX,

                                    offsetY =
                                        offsetY
                                )
                            }

                        if (
                            croppedBytes == null
                        ) {

                            errorMessage =
                                "Could not compress the photo. Please try another image."

                        } else {

                            errorMessage =
                                null

                            onSavePhoto(
                                croppedBytes
                            )
                        }
                    }
                },

                enabled =
                    selectedBitmap != null &&
                            !isSaving &&
                            !isLoadingPhoto,

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Icon(

                    imageVector =
                        Icons.Default.Save,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    text =
                        "Crop & Save"
                )
            }

            errorMessage?.let { message ->

                Spacer(
                    modifier =
                        Modifier.height(
                            10.dp
                        )
                )

                Text(

                    text =
                        message,

                    color =
                        MaterialTheme
                            .colorScheme
                            .error,

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    textAlign =
                        TextAlign.Center
                )
            }
        }
    }
}

/*
 * ============================================================
 * DECODE SELECTED GALLERY IMAGE
 * ============================================================
 */

private fun decodeSelectedBitmap(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri
): Bitmap? {

    val bounds =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

    try {

        contentResolver
            .openInputStream(
                uri
            )
            ?.use { inputStream ->

                BitmapFactory.decodeStream(
                    inputStream,
                    null,
                    bounds
                )
            }

    } catch (
        _: Exception
    ) {

        return null
    }

    if (
        bounds.outWidth <= 0 ||
        bounds.outHeight <= 0
    ) {

        return null
    }

    var sampleSize =
        1

    while (
        bounds.outWidth / sampleSize > 2048 ||
        bounds.outHeight / sampleSize > 2048
    ) {

        sampleSize *=
            2
    }

    val options =
        BitmapFactory.Options().apply {

            inSampleSize =
                sampleSize

            inPreferredConfig =
                Bitmap.Config.ARGB_8888
        }

    return try {

        contentResolver
            .openInputStream(
                uri
            )
            ?.use { inputStream ->

                BitmapFactory.decodeStream(
                    inputStream,
                    null,
                    options
                )
            }

    } catch (
        _: Exception
    ) {

        null
    }
}

/*
 * ============================================================
 * DATABASE PHOTO DECODER
 * ============================================================
 */

private fun decodeDatabasePhoto(
    value: String
): Bitmap? {

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

        val bytes =
            Base64.decode(
                base64Data,
                Base64.DEFAULT
            )

        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size
        )

    } catch (
        _: Exception
    ) {

        null
    }
}

/*
 * ============================================================
 * CROP + COMPRESS
 * ============================================================
 */

private fun cropAndCompressProfilePhoto(
    bitmap: Bitmap,
    previewSize: IntSize,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
): ByteArray? {

    if (
        previewSize.width <= 0 ||
        previewSize.height <= 0 ||
        bitmap.width <= 0 ||
        bitmap.height <= 0
    ) {

        return null
    }

    val viewportWidth =
        previewSize.width.toFloat()

    val viewportHeight =
        previewSize.height.toFloat()

    val baseScale =
        max(

            viewportWidth /
                    bitmap.width.toFloat(),

            viewportHeight /
                    bitmap.height.toFloat()
        )

    val scale =
        baseScale *
                zoom

    val scaledWidth =
        bitmap.width *
                scale

    val scaledHeight =
        bitmap.height *
                scale

    val imageLeft =
        (
                viewportWidth -
                        scaledWidth
                ) / 2f +
                offsetX

    val imageTop =
        (
                viewportHeight -
                        scaledHeight
                ) / 2f +
                offsetY

    val cropLeft =
        (
                -imageLeft /
                        scale
                )
            .coerceIn(
                0f,
                bitmap.width.toFloat()
            )

    val cropTop =
        (
                -imageTop /
                        scale
                )
            .coerceIn(
                0f,
                bitmap.height.toFloat()
            )

    val cropWidth =
        min(
            viewportWidth /
                    scale,

            bitmap.width -
                    cropLeft
        )

    val cropHeight =
        min(
            viewportHeight /
                    scale,

            bitmap.height -
                    cropTop
        )

    if (
        cropWidth <= 0f ||
        cropHeight <= 0f
    ) {

        return null
    }

    val cropSize =
        min(
            cropWidth,
            cropHeight
        )

    val adjustedLeft =
        (
                cropLeft +
                        (cropWidth - cropSize) / 2f
                )
            .toInt()
            .coerceIn(
                0,
                bitmap.width - 1
            )

    val adjustedTop =
        (
                cropTop +
                        (cropHeight - cropSize) / 2f
                )
            .toInt()
            .coerceIn(
                0,
                bitmap.height - 1
            )

    val maxCropSize =
        min(
            cropSize.toInt(),
            min(
                bitmap.width -
                        adjustedLeft,

                bitmap.height -
                        adjustedTop
            )
        )

    if (
        maxCropSize <= 0
    ) {

        return null
    }

    val croppedBitmap =
        Bitmap.createBitmap(

            bitmap,

            adjustedLeft,

            adjustedTop,

            maxCropSize,

            maxCropSize
        )

    val outputBitmap =
        Bitmap.createScaledBitmap(

            croppedBitmap,

            PROFILE_PHOTO_OUTPUT_SIZE,

            PROFILE_PHOTO_OUTPUT_SIZE,

            true
        )

    if (
        croppedBitmap !== outputBitmap
    ) {

        croppedBitmap.recycle()
    }

    var quality =
        PROFILE_PHOTO_INITIAL_QUALITY

    var result =
        compressJpeg(
            bitmap =
                outputBitmap,

            quality =
                quality
        )

    while (
        result != null &&
        result.size >
        PROFILE_PHOTO_MAX_BYTES &&
        quality >
        PROFILE_PHOTO_MIN_QUALITY
    ) {

        quality -=
            5

        result =
            compressJpeg(
                bitmap =
                    outputBitmap,

                quality =
                    quality
            )
    }

    outputBitmap.recycle()

    return if (
        result != null &&
        result.size <=
        PROFILE_PHOTO_MAX_BYTES
    ) {

        result

    } else {

        null
    }
}

/*
 * ============================================================
 * JPEG COMPRESSION
 * ============================================================
 */

private fun compressJpeg(
    bitmap: Bitmap,
    quality: Int
): ByteArray? {

    return try {

        ByteArrayOutputStream()
            .use { outputStream ->

                val success =
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        quality,
                        outputStream
                    )

                if (!success) {

                    null

                } else {

                    outputStream
                        .toByteArray()
                }
            }

    } catch (
        _: Exception
    ) {

        null
    }
}