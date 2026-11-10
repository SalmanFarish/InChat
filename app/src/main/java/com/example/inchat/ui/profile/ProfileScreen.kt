package com.example.inchat.ui.profile

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.auth.AuthViewModel
import com.example.inchat.ui.theme.JetBrainsMono

@Composable
fun ProfileScreen(
    username: String,
    uid: String,
    authViewModel: AuthViewModel,
    onSettingsClick: () -> Unit,
    onProfilePhotoClick: () -> Unit,
    onEditProfileClick: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val currentDisplayName by authViewModel.displayName.collectAsState()
    val currentBio by authViewModel.bio.collectAsState()

    val repository = remember { UserRepository() }
    var profilePhotoData by remember(uid) { mutableStateOf("") }

    LaunchedEffect(uid) {
        if (uid.isBlank()) return@LaunchedEffect

        val user = repository.getUserByIdFast(uid)
        if (user != null) {
            profilePhotoData = user.profilePhotoData.ifBlank {
                user.profilePhotoUrl
            }
        }
    }

    val displayName = currentDisplayName.trim().ifBlank { username }
    val bio = currentBio.trim()

    val shareText = buildString {
        append(displayName)
        append("\n")
        append("@")
        append(username)
        if (bio.isNotBlank()) {
            append("\n\n")
            append(bio)
        }
        append("\n\nFind me on InChat:")
        append("\nhttps://inchat.app/user/$username")
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 22.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "INCHAT / ACCOUNT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMono,
                            fontSize = 10.sp,
                            letterSpacing = 1.25.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(7.dp))

                    Text(
                        text = "Your profile",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 28.sp,
                            lineHeight = 34.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(30.dp))

            Box(
                modifier = Modifier
                    .size(132.dp)
                    .border(
                        width = 1.2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        shape = CircleShape
                    )
                    .padding(5.dp)
            ) {
                InChatProfileAvatar(
                    profilePhotoUrl = profilePhotoData,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onProfilePhotoClick
                        ),
                    iconSize = 58.dp,
                    contentDescription = "Edit profile picture"
                )
            }

            Spacer(Modifier.height(7.dp))

            TextButton(onClick = onProfilePhotoClick) {
                Text(
                    text = if (profilePhotoData.isBlank()) "Add profile photo" else "Change photo"
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 25.sp,
                    lineHeight = 31.sp
                ),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(5.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(50),
                border = BorderStroke(
                    0.7.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Text(
                    text = "@$username",
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(17.dp))

            Text(
                text = bio.ifBlank { "Add a short bio so people know a little about you." },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (bio.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(26.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onEditProfileClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Edit profile")
                }

                OutlinedButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, shareText)
                            type = "text/plain"
                        }

                        context.startActivity(
                            Intent.createChooser(sendIntent, "Share InChat profile")
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }

            Spacer(Modifier.height(32.dp))

            ProfileSectionLabel("ACCOUNT DETAILS")

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(18.dp)
                    ),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column {
                    CopyableProfileRow(
                        title = "Username",
                        value = "@$username",
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(username))
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    CopyableProfileRow(
                        title = "Account ID",
                        value = uid,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(uid))
                        }
                    )
                }
            }

            Spacer(Modifier.height(15.dp))

            Text(
                text = "Your username is your permanent InChat identity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ProfileSectionLabel(
    text: String
) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            letterSpacing = 0.9.sp,
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CopyableProfileRow(
    title: String,
    value: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy $title",
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
