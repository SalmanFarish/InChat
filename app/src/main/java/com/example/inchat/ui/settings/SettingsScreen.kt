package com.example.inchat.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.ui.auth.AuthViewModel
import com.example.inchat.ui.auth.GoogleSignInHelper
import com.example.inchat.ui.theme.JetBrainsMono
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    username: String,
    uid: String,
    authViewModel: AuthViewModel,
    onBackClick: () -> Unit,
    onPrivacyClick: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val isGoogleAccount = authViewModel.usesGoogleSignIn()
    val currentEmail by authViewModel.email.collectAsState()

    var hasPasswordSignIn by remember {
        mutableStateOf(authViewModel.hasPasswordSignIn())
    }
    var showAddPasswordDialog by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmNewPasswordVisible by remember { mutableStateOf(false) }
    var addPasswordLoading by remember { mutableStateOf(false) }
    var addPasswordError by remember { mutableStateOf<String?>(null) }
    var passwordAddedMessage by remember { mutableStateOf<String?>(null) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    var deleteLoading by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 14.dp,
                end = 20.dp,
                bottom = 36.dp
            ),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 3.dp)
                ) {
                    Text(
                        text = "INCHAT / PREFERENCES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMono,
                            fontSize = 10.sp,
                            letterSpacing = 1.1.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(7.dp))

                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 26.sp,
                            lineHeight = 32.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "Manage your account, security and privacy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingsSectionLabel("ACCOUNT")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        0.8.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 14.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsIconTile(
                                icon = Icons.Outlined.AccountCircle,
                                tint = MaterialTheme.colorScheme.primary
                            )

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Your InChat account",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(Modifier.height(3.dp))

                                Text(
                                    text = "@${username.trim().ifBlank { "unknown" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 15.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        if (currentEmail.isNotBlank()) {
                            CopyableSettingRow(
                                title = "Email address",
                                value = currentEmail,
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(currentEmail))
                                }
                            )

                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 15.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }

                        CopyableSettingRow(
                            title = "Username",
                            value = "@${username.trim()}",
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(username.trim()))
                            }
                        )

                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 15.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        CopyableSettingRow(
                            title = "Account ID",
                            value = uid.trim(),
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(uid.trim()))
                            }
                        )
                    }
                }
            }

            item {
                SettingsSectionLabel("SECURITY & SIGN-IN")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        0.8.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 15.dp
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsIconTile(
                                icon = Icons.Outlined.Lock,
                                tint = MaterialTheme.colorScheme.primary
                            )

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Password sign-in",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(Modifier.height(3.dp))

                                Text(
                                    text = when {
                                        hasPasswordSignIn ->
                                            "Email and password sign-in is available."
                                        isGoogleAccount ->
                                            "Add a password as another way to sign in."
                                        else ->
                                            "Password sign-in is not linked to this account."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            SettingsStatusPill(
                                label = if (hasPasswordSignIn) "ON" else if (isGoogleAccount) "NOT SET" else "—",
                                active = hasPasswordSignIn
                            )
                        }

                        if (isGoogleAccount && !hasPasswordSignIn) {
                            Spacer(Modifier.height(10.dp))

                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            TextButton(
                                onClick = {
                                    newPassword = ""
                                    confirmNewPassword = ""
                                    newPasswordVisible = false
                                    confirmNewPasswordVisible = false
                                    addPasswordError = null
                                    showAddPasswordDialog = true
                                },
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .padding(top = 4.dp)
                            ) {
                                Text(
                                    text = "Add password",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsSectionLabel("PRIVACY")
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onPrivacyClick
                        ),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        0.8.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 15.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsIconTile(
                            icon = Icons.Outlined.Visibility,
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Privacy & visibility",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(Modifier.height(3.dp))

                            Text(
                                text = "Control last seen, online status, read receipts and more.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = "Open privacy settings",
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SettingsSectionLabel("ACCOUNT ACTIONS")
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SettingsActionRow(
                        icon = Icons.Outlined.Logout,
                        title = "Log out",
                        subtitle = "Sign out of this InChat account.",
                        destructive = false,
                        onClick = {
                            authViewModel.logout()
                        }
                    )

                    SettingsActionRow(
                        icon = Icons.Outlined.DeleteForever,
                        title = "Delete account",
                        subtitle = "Permanently remove your account and profile data.",
                        destructive = true,
                        onClick = {
                            deletePassword = ""
                            deleteError = null
                            showDeleteDialog = true
                        }
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "INCHAT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMono,
                            letterSpacing = 1.2.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(5.dp))

                    Text(
                        text = "Private conversations. Minimal identity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAddPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!addPasswordLoading) {
                    showAddPasswordDialog = false
                    newPassword = ""
                    confirmNewPassword = ""
                    newPasswordVisible = false
                    confirmNewPasswordVisible = false
                    addPasswordError = null
                }
            },
            title = {
                Text("Add a password")
            },
            text = {
                Column {
                    Text(
                        text = "Add a password to your existing Google account. Your InChat username, chats, messages, and account ID will stay the same.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            addPasswordError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !addPasswordLoading,
                        singleLine = true,
                        label = { Text("New password") },
                        visualTransformation = if (newPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    newPasswordVisible = !newPasswordVisible
                                },
                                enabled = !addPasswordLoading
                            ) {
                                Icon(
                                    imageVector = if (newPasswordVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (newPasswordVisible) {
                                        "Hide new password"
                                    } else {
                                        "Show new password"
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        )
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = confirmNewPassword,
                        onValueChange = {
                            confirmNewPassword = it
                            addPasswordError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !addPasswordLoading,
                        singleLine = true,
                        label = { Text("Confirm password") },
                        visualTransformation = if (confirmNewPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    confirmNewPasswordVisible = !confirmNewPasswordVisible
                                },
                                enabled = !addPasswordLoading
                            ) {
                                Icon(
                                    imageVector = if (confirmNewPasswordVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (confirmNewPasswordVisible) {
                                        "Hide confirmation password"
                                    } else {
                                        "Show confirmation password"
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        )
                    )

                    Spacer(Modifier.height(9.dp))

                    Text(
                        text = "At least 6 characters. You will confirm ownership of your Google account before the password is linked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    addPasswordError?.let { message ->
                        Spacer(Modifier.height(9.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !addPasswordLoading,
                    onClick = {
                        when {
                            newPassword.length < 6 -> {
                                addPasswordError = "Password must be at least 6 characters."
                            }

                            newPassword != confirmNewPassword -> {
                                addPasswordError = "The passwords do not match."
                            }

                            else -> {
                                addPasswordLoading = true
                                addPasswordError = null

                                coroutineScope.launch {
                                    try {
                                        val googleIdToken =
                                            GoogleSignInHelper.getIdToken(context)

                                        authViewModel.addPasswordToGoogleAccount(
                                            googleIdToken = googleIdToken,
                                            password = newPassword
                                        ) { success, message ->
                                            addPasswordLoading = false

                                            if (success) {
                                                hasPasswordSignIn = true
                                                showAddPasswordDialog = false
                                                newPassword = ""
                                                confirmNewPassword = ""
                                                newPasswordVisible = false
                                                confirmNewPasswordVisible = false
                                                passwordAddedMessage = message
                                            } else {
                                                addPasswordError = message
                                                    ?: "Could not add your password. Please try again."
                                            }
                                        }
                                    } catch (e: Exception) {
                                        addPasswordLoading = false
                                        addPasswordError = e.message
                                            ?: "Google confirmation failed. Please try again."
                                    }
                                }
                            }
                        }
                    }
                ) {
                    if (addPasswordLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    Text(
                        if (addPasswordLoading) {
                            "Please wait"
                        } else {
                            "Confirm & add"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !addPasswordLoading,
                    onClick = {
                        showAddPasswordDialog = false
                        newPassword = ""
                        confirmNewPassword = ""
                        newPasswordVisible = false
                        confirmNewPasswordVisible = false
                        addPasswordError = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (passwordAddedMessage != null) {
        AlertDialog(
            onDismissRequest = {
                passwordAddedMessage = null
            },
            title = {
                Text("Password added")
            },
            text = {
                Text(passwordAddedMessage.orEmpty())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        passwordAddedMessage = null
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!deleteLoading) {
                    showDeleteDialog = false
                    deletePassword = ""
                    deleteError = null
                }
            },
            title = {
                Text("Delete account?")
            },
            text = {
                Column {
                    Text(
                        text = "This permanently deletes your InChat profile, username reservation, presence data, blocked-user entries and recent-chat data.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Shared conversations are not deleted from other participants' accounts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    if (isGoogleAccount) {
                        Text(
                            text = "Confirm this action with your Google account.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        OutlinedTextField(
                            value = deletePassword,
                            onValueChange = {
                                deletePassword = it
                                deleteError = null
                            },
                            enabled = !deleteLoading,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Password") },
                            placeholder = { Text("Enter your password") },
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }

                    deleteError?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteLoading &&
                        (isGoogleAccount || deletePassword.isNotBlank()),
                    onClick = {
                        deleteLoading = true
                        deleteError = null

                        if (isGoogleAccount) {
                            coroutineScope.launch {
                                try {
                                    val idToken = GoogleSignInHelper.getIdToken(context)
                                    authViewModel.deleteAccountWithGoogle(idToken) { success, error ->
                                        deleteLoading = false

                                        if (success) {
                                            showDeleteDialog = false
                                        } else {
                                            deleteError = error
                                                ?: "Could not delete your account."
                                        }
                                    }
                                } catch (e: Exception) {
                                    deleteLoading = false
                                    deleteError = e.message
                                        ?: "Google confirmation failed."
                                }
                            }
                        } else {
                            authViewModel.deleteAccount(
                                password = deletePassword
                            ) { success, error ->
                                deleteLoading = false

                                if (success) {
                                    showDeleteDialog = false
                                    deletePassword = ""
                                } else {
                                    deleteError = error
                                        ?: "Could not delete your account."
                                }
                            }
                        }
                    }
                ) {
                    if (deleteLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    Text("Delete account")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteLoading,
                    onClick = {
                        showDeleteDialog = false
                        deletePassword = ""
                        deleteError = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsIconTile(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color
) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(13.dp),
        color = tint.copy(alpha = 0.12f)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
        }
    }
}

@Composable
private fun SettingsStatusPill(
    label: String,
    active: Boolean
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            0.7.dp,
            if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 8.sp,
                letterSpacing = 0.45.sp
            ),
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun SettingsSectionLabel(
    title: String
) {
    Text(
        text = title,
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
private fun CopyableSettingRow(
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
                text = value.ifBlank { "Unavailable" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onCopy, enabled = value.isNotBlank()) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy $title",
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    destructive: Boolean,
    onClick: () -> Unit
) {
    val accent = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            0.8.dp,
            if (destructive) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIconTile(
                icon = icon,
                tint = accent
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )

                Spacer(Modifier.height(3.dp))

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
