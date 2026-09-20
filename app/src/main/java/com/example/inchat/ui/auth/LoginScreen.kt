package com.example.inchat.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class AuthMode {
    Login,
    Register,
    Recover
}

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel
) {
    var mode by
    remember {
        mutableStateOf(
            AuthMode.Login
        )
    }

    var usernameInput by
    remember {
        mutableStateOf("")
    }

    var passwordInput by
    remember {
        mutableStateOf("")
    }

    var recoveryCodeInput by
    remember {
        mutableStateOf("")
    }

    var newPasswordInput by
    remember {
        mutableStateOf("")
    }

    var confirmPasswordInput by
    remember {
        mutableStateOf("")
    }

    var errorMessage by
    remember {
        mutableStateOf<String?>(
            null
        )
    }

    var successMessage by
    remember {
        mutableStateOf<String?>(
            null
        )
    }

    var isLoading by
    remember {
        mutableStateOf(false)
    }

    /*
     * Recovery codes generated during registration.
     */
    var recoveryCodes by
    remember {
        mutableStateOf<List<String>?>(
            null
        )
    }

    val showingRegistrationRecoveryCodes =
        recoveryCodes != null

    /*
     * =========================================================
     * AUTHENTICATION SCREEN
     * =========================================================
     */
    Surface(

        modifier =
            Modifier.fillMaxSize(),

        color =
            MaterialTheme
                .colorScheme
                .background
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        24.dp
                    ),

            verticalArrangement =
                Arrangement.Center,

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(

                text =
                    when (
                        mode
                    ) {

                        AuthMode.Login ->
                            "Welcome to InChat"

                        AuthMode.Register ->
                            "Create your InChat account"

                        AuthMode.Recover ->
                            "Recover your InChat account"
                    },

                fontSize =
                    32.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    MaterialTheme
                        .colorScheme
                        .primary,

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
                    when (
                        mode
                    ) {

                        AuthMode.Login ->
                            "Log in with your username and password."

                        AuthMode.Register ->
                            "Pick a unique username and a password."

                        AuthMode.Recover ->
                            "Use one of your saved recovery codes to set a new password."
                    },

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
                        32.dp
                    )
            )

            /*
             * =================================================
             * RECOVERY MODE
             * =================================================
             */
            if (
                mode ==
                AuthMode.Recover
            ) {

                OutlinedTextField(

                    value =
                        usernameInput,

                    onValueChange = { value ->

                        usernameInput =
                            value

                        errorMessage =
                            null

                        successMessage =
                            null
                    },

                    label = {
                        Text(
                            "Username"
                        )
                    },

                    singleLine =
                        true,

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled =
                        !isLoading
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )

                OutlinedTextField(

                    value =
                        recoveryCodeInput,

                    onValueChange = { value ->

                        recoveryCodeInput =
                            value
                                .uppercase()

                        errorMessage =
                            null

                        successMessage =
                            null
                    },

                    label = {
                        Text(
                            "Recovery Code"
                        )
                    },

                    placeholder = {
                        Text(
                            "INCH-XXXX-XXXX-XXXX-XXXX"
                        )
                    },

                    singleLine =
                        true,

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled =
                        !isLoading
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )

                OutlinedTextField(

                    value =
                        newPasswordInput,

                    onValueChange = { value ->

                        newPasswordInput =
                            value

                        errorMessage =
                            null
                    },

                    label = {
                        Text(
                            "New Password"
                        )
                    },

                    singleLine =
                        true,

                    visualTransformation =
                        PasswordVisualTransformation(),

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Password
                        ),

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled =
                        !isLoading
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )

                OutlinedTextField(

                    value =
                        confirmPasswordInput,

                    onValueChange = { value ->

                        confirmPasswordInput =
                            value

                        errorMessage =
                            null
                    },

                    label = {
                        Text(
                            "Confirm New Password"
                        )
                    },

                    singleLine =
                        true,

                    visualTransformation =
                        PasswordVisualTransformation(),

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Password
                        ),

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled =
                        !isLoading
                )

                if (
                    errorMessage != null
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )

                    Text(
                        text =
                            errorMessage!!,

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

                Spacer(
                    modifier =
                        Modifier.height(
                            24.dp
                        )
                )

                Button(

                    onClick = {

                        if (
                            usernameInput
                                .isBlank() ||
                            recoveryCodeInput
                                .isBlank() ||
                            newPasswordInput
                                .isBlank() ||
                            confirmPasswordInput
                                .isBlank()
                        ) {

                            errorMessage =
                                "Fill in all fields"

                            return@Button
                        }

                        if (
                            newPasswordInput.length <
                            6
                        ) {

                            errorMessage =
                                "New password must be at least 6 characters"

                            return@Button
                        }

                        if (
                            newPasswordInput !=
                            confirmPasswordInput
                        ) {

                            errorMessage =
                                "Passwords do not match"

                            return@Button
                        }

                        isLoading =
                            true

                        errorMessage =
                            null

                        successMessage =
                            null

                        authViewModel
                            .recoverPassword(
                                username =
                                    usernameInput,

                                recoveryCode =
                                    recoveryCodeInput,

                                newPassword =
                                    newPasswordInput
                            ) { message ->

                                isLoading =
                                    false

                                if (
                                    message == null
                                ) {

                                    errorMessage =
                                        "Could not reset your password."

                                } else {

                                    successMessage =
                                        message

                                    /*
                                     * Clear the sensitive fields
                                     * immediately after success.
                                     */
                                    recoveryCodeInput =
                                        ""

                                    newPasswordInput =
                                        ""

                                    confirmPasswordInput =
                                        ""

                                    /*
                                     * Return to the normal login
                                     * screen.
                                     */
                                    mode =
                                        AuthMode.Login
                                }
                            }
                    },

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                                50.dp
                            ),

                    enabled =
                        !isLoading
                ) {

                    if (
                        isLoading
                    ) {

                        CircularProgressIndicator(

                            modifier =
                                Modifier.size(
                                    24.dp
                                ),

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onPrimary
                        )

                    } else {

                        Text(
                            text =
                                "Reset Password",

                            fontSize =
                                16.sp,

                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(
                            16.dp
                        )
                )

                TextButton(

                    onClick = {

                        mode =
                            AuthMode.Login

                        errorMessage =
                            null

                        successMessage =
                            null
                    },

                    enabled =
                        !isLoading
                ) {

                    Text(
                        "Back to Log In"
                    )
                }
            }

            /*
             * =================================================
             * LOGIN / REGISTER MODE
             * =================================================
             */
            else {

                OutlinedTextField(

                    value =
                        usernameInput,

                    onValueChange = { value ->

                        usernameInput =
                            value

                        errorMessage =
                            null

                        successMessage =
                            null
                    },

                    label = {
                        Text(
                            "Username"
                        )
                    },

                    singleLine =
                        true,

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled =
                        !isLoading &&
                                !showingRegistrationRecoveryCodes
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )

                OutlinedTextField(

                    value =
                        passwordInput,

                    onValueChange = { value ->

                        passwordInput =
                            value

                        errorMessage =
                            null

                        successMessage =
                            null
                    },

                    label = {
                        Text(
                            "Password"
                        )
                    },

                    singleLine =
                        true,

                    visualTransformation =
                        PasswordVisualTransformation(),

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Password
                        ),

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled =
                        !isLoading &&
                                !showingRegistrationRecoveryCodes
                )

                if (
                    errorMessage != null
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )

                    Text(
                        text =
                            errorMessage!!,

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

                Spacer(
                    modifier =
                        Modifier.height(
                            24.dp
                        )
                )

                Button(

                    onClick = {

                        if (
                            usernameInput
                                .isBlank() ||
                            passwordInput
                                .isBlank()
                        ) {

                            errorMessage =
                                "Fill in both fields"

                            return@Button
                        }

                        isLoading =
                            true

                        errorMessage =
                            null

                        successMessage =
                            null

                        if (
                            mode ==
                            AuthMode.Login
                        ) {

                            authViewModel
                                .login(
                                    usernameInput,
                                    passwordInput
                                ) { error ->

                                    isLoading =
                                        false

                                    errorMessage =
                                        error
                                }

                        } else {

                            authViewModel
                                .register(
                                    usernameInput,
                                    passwordInput
                                ) { error, codes ->

                                    isLoading =
                                        false

                                    errorMessage =
                                        error

                                    if (
                                        codes != null
                                    ) {

                                        recoveryCodes =
                                            codes
                                    }
                                }
                        }
                    },

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                                50.dp
                            ),

                    enabled =
                        !isLoading &&
                                !showingRegistrationRecoveryCodes
                ) {

                    if (
                        isLoading
                    ) {

                        CircularProgressIndicator(

                            modifier =
                                Modifier.size(
                                    24.dp
                                ),

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onPrimary
                        )

                    } else {

                        Text(

                            text =
                                if (
                                    mode ==
                                    AuthMode.Login
                                ) {
                                    "Log In"
                                } else {
                                    "Create Account"
                                },

                            fontSize =
                                16.sp,

                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }

                if (
                    mode ==
                    AuthMode.Login
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )

                    TextButton(

                        onClick = {

                            mode =
                                AuthMode.Recover

                            errorMessage =
                                null

                            successMessage =
                                null
                        },

                        enabled =
                            !isLoading &&
                                    !showingRegistrationRecoveryCodes
                    ) {

                        Text(
                            "Forgot password?"
                        )
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(
                            8.dp
                        )
                )

                TextButton(

                    onClick = {

                        mode =
                            if (
                                mode ==
                                AuthMode.Login
                            ) {
                                AuthMode.Register
                            } else {
                                AuthMode.Login
                            }

                        errorMessage =
                            null

                        successMessage =
                            null
                    },

                    enabled =
                        !isLoading &&
                                !showingRegistrationRecoveryCodes
                ) {

                    Text(

                        if (
                            mode ==
                            AuthMode.Login
                        ) {
                            "New here? Create an account"
                        } else {
                            "Already have an account? Log in"
                        }
                    )
                }
            }
        }
    }

    /*
     * =========================================================
     * SUCCESS MESSAGE
     * =========================================================
     */
    if (
        successMessage != null
    ) {

        AlertDialog(

            onDismissRequest = {

                successMessage =
                    null
            },

            title = {

                Text(
                    "Password Reset"
                )
            },

            text = {

                Text(
                    successMessage!!
                )
            },

            confirmButton = {

                TextButton(

                    onClick = {

                        successMessage =
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
     * REGISTRATION RECOVERY CODES
     * =========================================================
     */
    if (
        recoveryCodes != null
    ) {

        RecoveryCodesDialog(

            codes =
                recoveryCodes!!,

            onSaved = {

                recoveryCodes =
                    null

                authViewModel
                    .completeRegistration()
            }
        )
    }
}

/*
 * =========================================================
 * REGISTRATION RECOVERY CODE DIALOG
 * =========================================================
 */
@Composable
private fun RecoveryCodesDialog(
    codes: List<String>,
    onSaved: () -> Unit
) {
    AlertDialog(

        onDismissRequest = {
            /*
             * Intentionally empty.
             *
             * The user must explicitly confirm that
             * they have saved the recovery codes.
             */
        },

        title = {

            Text(

                text =
                    "Save your recovery codes",

                fontWeight =
                    FontWeight.Bold
            )
        },

        text = {

            Column {

                Text(
                    text =
                        "These codes are the only way to recover your account if you forget your password.",

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
                        "Save them somewhere safe. Each code can be used only once.",

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
                            16.dp
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

                        style =
                            MaterialTheme
                                .typography
                                .bodyLarge,

                        fontWeight =
                            FontWeight.Medium,

                        textAlign =
                            TextAlign.Center
                    )
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick =
                    onSaved
            ) {

                Text(
                    "I've saved them"
                )
            }
        }
    )
}