package com.example.inchat.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.ui.auth.AuthViewModel
import com.example.inchat.ui.auth.GoogleSignInHelper
import kotlinx.coroutines.launch

private data class SettingsItem(
    val title: String,
    val subtitle: String,
    val enabled: Boolean = false
)

private data class SettingsSection(
    val title: String,
    val items: List<SettingsItem>
)

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun SettingsScreen(
    username: String,
    uid: String,
    authViewModel: AuthViewModel,
    onBackClick: () -> Unit,
    onPrivacyClick: () -> Unit
) {

    val context =
        LocalContext.current

    val coroutineScope =
        rememberCoroutineScope()

    val isGoogleAccount =
        authViewModel.usesGoogleSignIn()

    val isLegacyPasswordAccount =
        authViewModel.usesLegacyUsernamePassword()

    val currentAuthEmail =
        authViewModel.currentAuthEmail()

    val currentAuthEmailVerified =
        authViewModel.isCurrentAuthEmailVerified()

    var showRecoveryConfirmation by
    remember {
        mutableStateOf(false)
    }

    var showEmailUpdateDialog by
    remember {
        mutableStateOf(false)
    }

    var emailAddressInput by
    remember {
        mutableStateOf("")
    }

    var emailPasswordInput by
    remember {
        mutableStateOf("")
    }

    var emailUpdateLoading by
    remember {
        mutableStateOf(false)
    }

    var emailUpdateError by
    remember {
        mutableStateOf<String?>(null)
    }

    var emailUpdateSuccess by
    remember {
        mutableStateOf<String?>(null)
    }

    var recoveryCodes by
    remember {
        mutableStateOf<List<String>?>(
            null
        )
    }

    var recoveryError by
    remember {
        mutableStateOf<String?>(
            null
        )
    }

    var recoveryLoading by
    remember {
        mutableStateOf(false)
    }

    var showDeleteDialog by
    remember {
        mutableStateOf(false)
    }

    var deletePassword by
    remember {
        mutableStateOf("")
    }

    var deleteLoading by
    remember {
        mutableStateOf(false)
    }

    var deleteError by
    remember {
        mutableStateOf<String?>(
            null
        )
    }

    val cleanUsername =
        username
            .trim()

    val cleanUid =
        uid
            .trim()

    val sections =
        listOf(

            SettingsSection(
                title =
                    "ACCOUNT",

                items =
                    listOf(

                        SettingsItem(
                            title =
                                "Username",

                            subtitle =
                                cleanUsername
                                    .ifBlank {
                                        "Your permanent InChat username"
                                    },

                            enabled =
                                false
                        ),

                        SettingsItem(
                            title =
                                "Account ID",

                            subtitle =
                                cleanUid
                                    .ifBlank {
                                        "Unavailable"
                                    },

                            enabled =
                                false
                        ),

                        SettingsItem(
                            title =
                                "Email address",

                            subtitle =
                                when {
                                    isGoogleAccount ->
                                        "Managed by Google"

                                    isLegacyPasswordAccount ->
                                        "Add a verified email for password recovery"

                                    currentAuthEmailVerified ->
                                        "Verified: " + currentAuthEmail

                                    currentAuthEmail.isNotBlank() ->
                                        "Verification pending: " + currentAuthEmail

                                    else ->
                                        "No email address available"
                                },

                            enabled =
                                true
                        )
                    )
            ),

            SettingsSection(
                title =
                    "SECURITY",

                items =
                    listOf(

                        SettingsItem(
                            title =
                                "Recovery codes",

                            subtitle =
                                when {
                                    isGoogleAccount ->
                                        "Account recovery is managed by Google"

                                    isLegacyPasswordAccount ->
                                        "Keep these until you add and verify a real email address"

                                    else ->
                                        "Email-based password recovery is active"
                                },

                            enabled =
                                true
                        ),

                        SettingsItem(
                            title =
                                "Change password",

                            subtitle =
                                "Update the password used to sign in",

                            enabled =
                                false
                        )
                    )
            ),

            SettingsSection(
                title =
                    "PRIVACY",

                items =
                    listOf(

                        SettingsItem(
                            title =
                                "Last seen",

                            subtitle =
                                "Choose whether other people can see when you were last active",

                            enabled =
                                true
                        ),

                        SettingsItem(
                            title =
                                "Online status",

                            subtitle =
                                "Choose whether other people can see you online",

                            enabled =
                                true
                        ),

                        SettingsItem(
                            title =
                                "Read receipts",

                            subtitle =
                                "Let other people see when you have read their messages",

                            enabled =
                                true
                        ),

                        SettingsItem(
                            title =
                                "Typing indicator",

                            subtitle =
                                "Let other people see when you are typing",

                            enabled =
                                true
                        ),

                        SettingsItem(
                            title =
                                "Who can find me",

                            subtitle =
                                "Choose whether people can discover you by username",

                            enabled =
                                true
                        )
                    )
            ),

            SettingsSection(
                title =
                    "NOTIFICATIONS",

                items =
                    listOf(

                        SettingsItem(
                            title =
                                "Message notifications",

                            subtitle =
                                "Control notifications for new messages"
                        ),

                        SettingsItem(
                            title =
                                "Notification previews",

                            subtitle =
                                "Control message text shown in notifications"
                        ),

                        SettingsItem(
                            title =
                                "Sound",

                            subtitle =
                                "Control notification sound"
                        ),

                        SettingsItem(
                            title =
                                "Vibration",

                            subtitle =
                                "Control notification vibration"
                        )
                    )
            ),

            SettingsSection(
                title =
                    "APPEARANCE",

                items =
                    listOf(

                        SettingsItem(
                            title =
                                "Theme",

                            subtitle =
                                "Choose light, dark or system appearance"
                        ),

                        SettingsItem(
                            title =
                                "AMOLED mode",

                            subtitle =
                                "Use a deeper black interface"
                        )
                    )
            ),

            SettingsSection(
                title =
                    "CHATS",

                items =
                    listOf(

                        SettingsItem(
                            title =
                                "Chat appearance",

                            subtitle =
                                "Customize the look of conversations"
                        ),

                        SettingsItem(
                            title =
                                "Enter key behavior",

                            subtitle =
                                "Choose what the keyboard enter key does"
                        ),

                        SettingsItem(
                            title =
                                "Media and data",

                            subtitle =
                                "Control media handling and data usage"
                        )
                    )
            ),

            SettingsSection(
                title =
                    "SAFETY",

                items =
                    listOf(

                        SettingsItem(
                            title =
                                "Blocked users",

                            subtitle =
                                "Manage the people you have blocked"
                        ),

                        SettingsItem(
                            title =
                                "Safety information",

                            subtitle =
                                "Learn about blocking, reporting and privacy"
                        )
                    )
            ),

            SettingsSection(
                title =
                    "SUPPORT",

                items =
                    listOf(

                        SettingsItem(
                            title =
                                "Help",

                            subtitle =
                                "Get help using InChat"
                        ),

                        SettingsItem(
                            title =
                                "Contact support",

                            subtitle =
                                "Get in touch with InChat support"
                        ),

                        SettingsItem(
                            title =
                                "Privacy policy",

                            subtitle =
                                "Read how InChat handles your information"
                        ),

                        SettingsItem(
                            title =
                                "Terms of service",

                            subtitle =
                                "Read the terms for using InChat"
                        ),

                        SettingsItem(
                            title =
                                "About InChat",

                            subtitle =
                                "Application information and version"
                        )
                    )
            )
        )

    /*
     * =========================================================
     * EMAIL ADDRESS UPDATE
     * =========================================================
     */
    if (showEmailUpdateDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!emailUpdateLoading) {
                    showEmailUpdateDialog = false
                    emailPasswordInput = ""
                    emailUpdateError = null
                }
            },
            title = {
                Text(
                    if (isLegacyPasswordAccount) {
                        "Add a recovery email"
                    } else {
                        "Change email address"
                    }
                )
            },
            text = {
                Column {
                    Text(
                        if (isLegacyPasswordAccount) {
                            "Add an email address you can access. After you verify it, use that email address to sign in and reset your password. Your username, chats and groups will remain unchanged."
                        } else {
                            "Firebase will send a verification link to the new address. Your sign-in email changes only after you confirm the link."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    OutlinedTextField(
                        value = emailAddressInput,
                        onValueChange = {
                            emailAddressInput = it
                            emailUpdateError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !emailUpdateLoading,
                        label = {
                            Text("New email address")
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email
                        )
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    OutlinedTextField(
                        value = emailPasswordInput,
                        onValueChange = {
                            emailPasswordInput = it
                            emailUpdateError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !emailUpdateLoading,
                        label = {
                            Text("Current password")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password
                        )
                    )

                    if (emailUpdateError != null) {
                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )
                        Text(
                            text = emailUpdateError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !emailUpdateLoading &&
                            emailAddressInput.isNotBlank() &&
                            emailPasswordInput.isNotBlank(),
                    onClick = {
                        emailUpdateLoading = true
                        emailUpdateError = null

                        authViewModel.requestEmailUpdate(
                            rawEmail = emailAddressInput,
                            currentPassword = emailPasswordInput
                        ) { success, message ->
                            emailUpdateLoading = false

                            if (success) {
                                showEmailUpdateDialog = false
                                emailPasswordInput = ""
                                emailUpdateSuccess = message
                                    ?: "Verification email sent."
                            } else {
                                emailUpdateError = message
                                    ?: "Could not update your email address."
                            }
                        }
                    }
                ) {
                    if (emailUpdateLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text("Send verification")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !emailUpdateLoading,
                    onClick = {
                        showEmailUpdateDialog = false
                        emailPasswordInput = ""
                        emailUpdateError = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (emailUpdateSuccess != null) {
        AlertDialog(
            onDismissRequest = {
                emailUpdateSuccess = null
            },
            title = {
                Text("Email account")
            },
            text = {
                Text(emailUpdateSuccess!!)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        emailUpdateSuccess = null
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }

    /*
     * =========================================================
     * RECOVERY CONFIRMATION
     * =========================================================
     */
    if (
        showRecoveryConfirmation
    ) {

        AlertDialog(

            onDismissRequest = {

                if (
                    !recoveryLoading
                ) {

                    showRecoveryConfirmation =
                        false
                }
            },

            title = {

                Text(
                    "Generate new recovery codes?"
                )
            },

            text = {

                Text(
                    "This will immediately invalidate all previous recovery codes and create five new ones."
                )
            },

            confirmButton = {

                TextButton(

                    enabled =
                        !recoveryLoading,

                    onClick = {

                        recoveryLoading =
                            true

                        recoveryError =
                            null

                        authViewModel
                            .generateRecoveryCodes {

                                    success,
                                    codes,
                                    error ->

                                recoveryLoading =
                                    false

                                if (
                                    success &&
                                    codes != null
                                ) {

                                    showRecoveryConfirmation =
                                        false

                                    recoveryCodes =
                                        codes

                                } else {

                                    recoveryError =
                                        error
                                            ?: "Could not generate recovery codes"
                                }
                            }
                    }
                ) {

                    if (
                        recoveryLoading
                    ) {

                        CircularProgressIndicator(

                            modifier =
                                Modifier
                                    .padding(
                                        end =
                                            8.dp
                                    )
                                    .height(
                                        18.dp
                                    ),

                            strokeWidth =
                                2.dp
                        )
                    }

                    Text(
                        "Generate"
                    )
                }
            },

            dismissButton = {

                TextButton(

                    enabled =
                        !recoveryLoading,

                    onClick = {

                        showRecoveryConfirmation =
                            false
                    }
                ) {

                    Text(
                        "Cancel"
                    )
                }
            }
        )
    }

    /*
     * =========================================================
     * RECOVERY ERROR
     * =========================================================
     */
    if (
        recoveryError != null
    ) {

        AlertDialog(

            onDismissRequest = {

                recoveryError =
                    null
            },

            title = {

                Text(
                    "Recovery Codes"
                )
            },

            text = {

                Text(
                    recoveryError!!
                )
            },

            confirmButton = {

                TextButton(

                    onClick = {

                        recoveryError =
                            null
                    }
                ) {

                    Text(
                        "OK"
                    )
                }
            }
        )
    }

    /*
     * =========================================================
     * RECOVERY CODES
     * =========================================================
     */
    if (
        recoveryCodes != null
    ) {

        val codes =
            recoveryCodes!!

        AlertDialog(

            onDismissRequest = {
                /*
                 * Intentionally disabled.
                 */
            },

            title = {

                Text(

                    text =
                        "Your Recovery Codes",

                    fontWeight =
                        FontWeight.Bold
                )
            },

            text = {

                Column {

                    Text(
                        text =
                            "Save these codes somewhere safe. Anyone with one of these codes may be able to recover the account.",

                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                12.dp
                            )
                    )

                    Text(
                        text =
                            "Each code can be used only once.",

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .error
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                14.dp
                            )
                    )

                    codes.forEach { code ->

                        Text(

                            text =
                                code,

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        vertical =
                                            4.dp
                                    ),

                            textAlign =
                                TextAlign.Center,

                            style =
                                MaterialTheme
                                    .typography
                                    .bodyLarge,

                            fontWeight =
                                FontWeight.Medium
                        )
                    }

                    Spacer(
                        modifier =
                            Modifier.height(
                                12.dp
                            )
                    )

                    OutlinedButton(

                        onClick = {

                            val allCodes =
                                codes.joinToString(
                                    "\n"
                                )

                            val clipboard =
                                context.getSystemService(
                                    ClipboardManager::class.java
                                )

                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(
                                    "InChat Recovery Codes",
                                    allCodes
                                )
                            )
                        },

                        modifier =
                            Modifier.fillMaxWidth()
                    ) {

                        Icon(

                            imageVector =
                                Icons.Default.ContentCopy,

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
                            "Copy all codes"
                        )
                    }
                }
            },

            confirmButton = {

                TextButton(

                    onClick = {

                        recoveryCodes =
                            null
                    }
                ) {

                    Text(
                        "I've saved them"
                    )
                }
            }
        )
    }

    /*
     * =========================================================
     * DELETE ACCOUNT
     * =========================================================
     */
    if (
        showDeleteDialog
    ) {

        AlertDialog(

            onDismissRequest = {

                if (
                    !deleteLoading
                ) {

                    showDeleteDialog =
                        false

                    deletePassword =
                        ""

                    deleteError =
                        null
                }
            },

            title = {

                Text(
                    "Delete account?"
                )
            },

            text = {

                Column {

                    Text(

                        text =
                            "This permanently deletes your InChat profile, username reservation, recovery codes, presence data, blocked-user entries and recent-chat data.",

                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                14.dp
                            )
                    )

                    Text(

                        text =
                            "Shared conversations are not deleted from other participants' accounts.",

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                14.dp
                            )
                    )

                    if (isGoogleAccount) {

                        Text(
                            text =
                                "Re-enter your Google account to confirm this action.",

                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium
                        )

                    } else {

                        OutlinedTextField(

                            value =
                                deletePassword,

                            onValueChange = {

                                deletePassword =
                                    it

                                deleteError =
                                    null
                            },

                            enabled =
                                !deleteLoading,

                            modifier =
                                Modifier.fillMaxWidth(),

                            singleLine =
                                true,

                            label = {

                                Text(
                                    "Password"
                                )
                            },

                            placeholder = {

                                Text(
                                    "Enter your password"
                                )
                            },

                            visualTransformation =
                                PasswordVisualTransformation()
                        )
                    }

                    if (
                        deleteError != null
                    ) {

                        Spacer(
                            modifier =
                                Modifier.height(
                                    8.dp
                                )
                        )

                        Text(

                            text =
                                deleteError!!,

                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }
                }
            },

            confirmButton = {

                TextButton(

                    enabled =
                        !deleteLoading &&
                                (
                                    isGoogleAccount ||
                                    deletePassword.isNotBlank()
                                ),

                    onClick = {

                        deleteLoading =
                            true

                        deleteError =
                            null

                        if (isGoogleAccount) {

                            coroutineScope.launch {

                                try {

                                    val idToken =
                                        GoogleSignInHelper
                                            .getIdToken(
                                                context
                                            )

                                    authViewModel
                                        .deleteAccountWithGoogle(
                                            idToken
                                        ) { success, error ->

                                            deleteLoading =
                                                false

                                            if (success) {

                                                showDeleteDialog =
                                                    false

                                            } else {

                                                deleteError =
                                                    error
                                                        ?: "Could not delete your account."
                                            }
                                        }

                                } catch (e: Exception) {

                                    deleteLoading =
                                        false

                                    deleteError =
                                        e.message
                                            ?: "Google confirmation failed."
                                }
                            }

                        } else {

                            authViewModel
                                .deleteAccount(
                                    password =
                                        deletePassword
                                ) { success, error ->

                                    deleteLoading =
                                        false

                                    if (
                                        success
                                    ) {

                                        showDeleteDialog =
                                            false

                                        deletePassword =
                                            ""

                                    } else {

                                        deleteError =
                                            error
                                                ?: "Could not delete your account."
                                    }
                                }
                        }
                    }
                ) {

                    if (
                        deleteLoading
                    ) {

                        CircularProgressIndicator(

                            modifier =
                                Modifier
                                    .padding(
                                        end =
                                            8.dp
                                    )
                                    .height(
                                        18.dp
                                    ),

                            strokeWidth =
                                2.dp
                        )
                    }

                    Text(
                        "Delete account"
                    )
                }
            },

            dismissButton = {

                TextButton(

                    enabled =
                        !deleteLoading,

                    onClick = {

                        showDeleteDialog =
                            false

                        deletePassword =
                            ""

                        deleteError =
                            null
                    }
                ) {

                    Text(
                        "Cancel"
                    )
                }
            }
        )
    }

    /*
     * =========================================================
     * SETTINGS
     * =========================================================
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
                            "Settings",

                        fontSize =
                            20.sp,

                        fontWeight =
                            FontWeight.Bold,

                        letterSpacing =
                            (-0.3).sp
                    )
                },

                navigationIcon = {

                    IconButton(

                        onClick =
                            onBackClick
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
                },

                colors =
                    TopAppBarDefaults
                        .topAppBarColors(

                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .background,

                            scrolledContainerColor =
                                MaterialTheme
                                    .colorScheme
                                    .background,

                            titleContentColor =
                                MaterialTheme
                                    .colorScheme
                                    .onBackground,

                            navigationIconContentColor =
                                MaterialTheme
                                    .colorScheme
                                    .onBackground
                        )
            )
        }

    ) { innerPadding ->

        LazyColumn(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        innerPadding
                    ),

            contentPadding =
                androidx.compose
                    .foundation
                    .layout
                    .PaddingValues(
                        start =
                            16.dp,

                        top =
                            10.dp,

                        end =
                            16.dp,

                        bottom =
                            28.dp
                    ),

            verticalArrangement =
                Arrangement.spacedBy(
                    20.dp
                )
        ) {

            item {

                Surface(

                    modifier =
                        Modifier.fillMaxWidth(),

                    shape =
                        androidx.compose
                            .foundation
                            .shape
                            .RoundedCornerShape(
                                22.dp
                            ),

                    color =
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant,

                    tonalElevation =
                        1.dp
                ) {

                    Column(

                        modifier =
                            Modifier.padding(
                                horizontal =
                                    18.dp,

                                vertical =
                                    18.dp
                            )
                    ) {

                        Text(

                            text =
                                "Your account",

                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,

                            fontWeight =
                                FontWeight.Bold
                        )

                        Spacer(
                            modifier =
                                Modifier.height(
                                    4.dp
                                )
                        )

                        Text(

                            text =
                                cleanUsername
                                    .ifBlank {
                                        "Username unavailable"
                                    },

                            style =
                                MaterialTheme
                                    .typography
                                    .bodyLarge,

                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Spacer(
                            modifier =
                                Modifier.height(
                                    2.dp
                                )
                        )

                        Text(

                            text =
                                "Manage your InChat account and privacy.",

                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }
                }
            }

            sections.forEach { section ->

                item {

                    SettingsSectionCard(

                        section =
                            section,

                        onRecoveryCodesClick = {

                            if (isLegacyPasswordAccount) {
                                recoveryError =
                                    null

                                showRecoveryConfirmation =
                                    true
                            } else {
                                emailUpdateSuccess =
                                    if (isGoogleAccount) {
                                        "Account recovery is managed by Google."
                                    } else {
                                        "This account uses email-based password recovery. Use Forgot password on the login screen."
                                    }
                            }
                        },

                        onEmailClick = {
                            if (isGoogleAccount) {
                                emailUpdateSuccess =
                                    "This account uses Google sign-in. Manage its email address through your Google account."
                            } else {
                                emailAddressInput =
                                    if (isLegacyPasswordAccount) {
                                        ""
                                    } else {
                                        currentAuthEmail
                                    }

                                emailPasswordInput = ""
                                emailUpdateError = null
                                showEmailUpdateDialog = true
                            }
                        },

                        onPrivacyClick =
                            onPrivacyClick
                    )
                }
            }

            item {

                Column(

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    SettingsSectionLabel(
                        "ACCOUNT ACTIONS"
                    )

                    SettingsActionCard(

                        title =
                            "Log out",

                        subtitle =
                            "Sign out of this InChat account",

                        onClick = {

                            authViewModel
                                .logout()
                        }
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                1.dp
                            )
                    )

                    SettingsActionCard(

                        title =
                            "Delete account",

                        subtitle =
                            "Permanently remove your InChat account",

                        onClick = {

                            deletePassword =
                                ""

                            deleteError =
                                null

                            showDeleteDialog =
                                true
                        }
                    )
                }
            }

            item {

                Column(

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                top =
                                    2.dp
                            ),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(

                        text =
                            "InChat",

                        style =
                            MaterialTheme
                                .typography
                                .labelMedium,

                        fontWeight =
                            FontWeight.SemiBold
                    )

                    Spacer(
                        modifier =
                            Modifier.height(
                                3.dp
                            )
                    )

                    Text(

                        text =
                            "Private conversations. Minimal identity.",

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,

                        textAlign =
                            TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    section: SettingsSection,
    onRecoveryCodesClick: () -> Unit,
    onEmailClick: () -> Unit,
    onPrivacyClick: () -> Unit
) {

    Column(
        modifier =
            Modifier.fillMaxWidth()
    ) {

        SettingsSectionLabel(
            section.title
        )

        Surface(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                androidx.compose
                    .foundation
                    .shape
                    .RoundedCornerShape(
                        20.dp
                    ),

            color =
                MaterialTheme
                    .colorScheme
                    .surfaceVariant,

            tonalElevation =
                1.dp
        ) {

            Column {

                section.items.forEachIndexed {
                        index,
                        item ->

                    SettingsRow(

                        item =
                            item,

                        onClick = {

                            when (
                                item.title
                            ) {

                                "Recovery codes" ->
                                    onRecoveryCodesClick()

                                "Email address" ->
                                    onEmailClick()

                                "Last seen",
                                "Online status",
                                "Read receipts",
                                "Typing indicator",
                                "Who can find me" ->
                                    onPrivacyClick()
                            }
                        }
                    )

                    if (
                        index <
                        section.items.lastIndex
                    ) {

                        SettingsDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(
    title: String
) {

    Text(

        text =
            title,

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start =
                        4.dp,

                    end =
                        4.dp,

                    bottom =
                        7.dp
                ),

        style =
            MaterialTheme
                .typography
                .labelSmall,

        fontWeight =
            FontWeight.Bold,

        color =
            MaterialTheme
                .colorScheme
                .onSurfaceVariant,

        letterSpacing =
            0.8.sp
    )
}

@Composable
private fun SettingsRow(
    item: SettingsItem,
    onClick: () -> Unit
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .then(

                    if (
                        item.enabled
                    ) {

                        Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick =
                                onClick
                        )

                    } else {

                        Modifier
                    }
                )
                .padding(
                    start =
                        18.dp,

                    top =
                        15.dp,

                    end =
                        18.dp,

                    bottom =
                        15.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(

            modifier =
                Modifier.weight(
                    1f
                )
        ) {

            Text(

                text =
                    item.title,

                fontSize =
                    16.sp,

                fontWeight =
                    FontWeight.SemiBold,

                color =
                    if (
                        item.enabled
                    ) {

                        MaterialTheme
                            .colorScheme
                            .onBackground

                    } else {

                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                    }
            )

            Spacer(
                modifier =
                    Modifier.height(
                        2.dp
                    )
            )

            Text(

                text =
                    item.subtitle,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }

        if (
            item.enabled
        ) {

            Spacer(
                modifier =
                    Modifier.width(
                        12.dp
                    )
            )

            Text(

                text =
                    "›",

                fontSize =
                    24.sp,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

        } else {

            Spacer(
                modifier =
                    Modifier.width(
                        12.dp
                    )
                )

            Text(

                text =
                    "Soon",

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
}

@Composable
private fun SettingsActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {

    Surface(

        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick =
                        onClick
                ),

        color =
            MaterialTheme
                .colorScheme
                .surfaceVariant,

        tonalElevation =
            1.dp,

        shape =
            androidx.compose
                .foundation
                .shape
                .RoundedCornerShape(
                    topStart =
                        20.dp,

                    topEnd =
                        20.dp,

                    bottomStart =
                        4.dp,

                    bottomEnd =
                        4.dp
                )
    ) {

        Column(

            modifier =
                Modifier.padding(
                    horizontal =
                        18.dp,

                    vertical =
                        15.dp
                )
        ) {

            Text(

                text =
                    title,

                fontSize =
                    16.sp,

                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                modifier =
                    Modifier.height(
                        2.dp
                    )
            )

            Text(

                text =
                    subtitle,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsDivider() {

    Spacer(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(
                    1.dp
                )
                .padding(
                    horizontal =
                        18.dp
                )
    )
}
