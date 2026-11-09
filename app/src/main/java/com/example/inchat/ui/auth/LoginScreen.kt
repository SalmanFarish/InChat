package com.example.inchat.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class AuthMode {
    Login,
    Register,
    Recover
}

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var mode by remember { mutableStateOf(AuthMode.Login) }
    var emailInput by remember { mutableStateOf("") }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (mode) {
                    AuthMode.Login -> "Welcome to InChat"
                    AuthMode.Register -> "Create your InChat account"
                    AuthMode.Recover -> "Reset your InChat password"
                },
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (mode) {
                    AuthMode.Login ->
                        "Log in with your email address and password."
                    AuthMode.Register ->
                        "Use a real email address, choose a username and create a password."
                    AuthMode.Recover ->
                        "Enter your account email. Firebase will send you a password-reset link."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            if (mode == AuthMode.Recover) {
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        errorMessage = null
                        successMessage = null
                    },
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (emailInput.isBlank()) {
                            errorMessage = "Enter your email address."
                            return@Button
                        }

                        isLoading = true
                        errorMessage = null
                        authViewModel.sendPasswordResetEmail(emailInput) { success, message ->
                            isLoading = false
                            if (success) {
                                successMessage = message
                                mode = AuthMode.Login
                            } else {
                                errorMessage = message
                                    ?: "Could not send the reset email."
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            "Send reset link",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        mode = AuthMode.Login
                        errorMessage = null
                    },
                    enabled = !isLoading
                ) {
                    Text("Back to Log In")
                }
            } else {
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        errorMessage = null
                        successMessage = null
                    },
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                if (mode == AuthMode.Register) {
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = {
                            usernameInput = it
                            errorMessage = null
                        },
                        label = { Text("Choose a username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = {
                        passwordInput = it
                        errorMessage = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Button(
                    onClick = {
                        if (emailInput.isBlank() || passwordInput.isBlank()) {
                            errorMessage = "Enter your email address and password."
                            return@Button
                        }

                        if (
                            mode == AuthMode.Register &&
                            usernameInput.isBlank()
                        ) {
                            errorMessage = "Choose a username."
                            return@Button
                        }

                        isLoading = true
                        errorMessage = null
                        successMessage = null

                        if (mode == AuthMode.Login) {
                            authViewModel.login(
                                rawEmail = emailInput,
                                password = passwordInput
                            ) { error ->
                                isLoading = false
                                errorMessage = error
                            }
                        } else {
                            authViewModel.register(
                                rawEmail = emailInput,
                                rawUsername = usernameInput,
                                password = passwordInput
                            ) { error ->
                                isLoading = false
                                errorMessage = error
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            if (mode == AuthMode.Login) "Log In" else "Create Account",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "OR",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        if (!isLoading) {
                            isLoading = true
                            errorMessage = null

                            coroutineScope.launch {
                                try {
                                    val idToken =
                                        GoogleSignInHelper.getIdToken(context)

                                    authViewModel.signInWithGoogle(idToken) { error ->
                                        isLoading = false
                                        errorMessage = error
                                    }
                                } catch (e: Exception) {
                                    isLoading = false
                                    val message =
                                        e.message
                                            ?: "Google sign-in failed. Please try again."

                                    errorMessage =
                                        if (message.contains("cancel", ignoreCase = true)) {
                                            "Google sign-in was cancelled."
                                        } else {
                                            message
                                        }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = !isLoading
                ) {
                    Text(
                        "Continue with Google",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (mode == AuthMode.Login) {
                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            mode = AuthMode.Recover
                            errorMessage = null
                            successMessage = null
                        },
                        enabled = !isLoading
                    ) {
                        Text("Forgot password?")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        mode =
                            if (mode == AuthMode.Login) {
                                AuthMode.Register
                            } else {
                                AuthMode.Login
                            }

                        errorMessage = null
                        successMessage = null
                    },
                    enabled = !isLoading
                ) {
                    Text(
                        if (mode == AuthMode.Login) {
                            "New here? Create an account"
                        } else {
                            "Already have an account? Log in"
                        }
                    )
                }
            }
        }
    }

    if (successMessage != null) {
        AlertDialog(
            onDismissRequest = { successMessage = null },
            title = { Text("Password reset") },
            text = { Text(successMessage!!) },
            confirmButton = {
                TextButton(onClick = { successMessage = null }) {
                    Text("OK")
                }
            }
        )
    }
}
