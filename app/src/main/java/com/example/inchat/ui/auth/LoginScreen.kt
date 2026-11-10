package com.example.inchat.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.ui.theme.JetBrainsMono
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
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var mode by remember { mutableStateOf(AuthMode.Login) }
    var emailInput by remember { mutableStateOf("") }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun clearMessages() {
        errorMessage = null
        successMessage = null
    }

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
                .padding(horizontal = 24.dp)
                .padding(top = 30.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AuthBrandHeader()

            Spacer(Modifier.height(35.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = when (mode) {
                        AuthMode.Login -> "Welcome back."
                        AuthMode.Register -> "Create your account."
                        AuthMode.Recover -> "Let's get you back in."
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 29.sp,
                        lineHeight = 35.sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(Modifier.height(7.dp))

                Text(
                    text = when (mode) {
                        AuthMode.Login ->
                            "Sign in to continue your conversations."
                        AuthMode.Register ->
                            "A real email, a username, and a password are all you need."
                        AuthMode.Recover ->
                            "Enter the email address connected to your InChat password."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(22.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(22.dp)
                    ),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    AuthFieldLabel("EMAIL ADDRESS")

                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = {
                            emailInput = it
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isLoading,
                        label = { Text("Email address") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Email
                        )
                    )

                    if (mode == AuthMode.Register) {
                        AuthFieldLabel("USERNAME")

                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = {
                                usernameInput = it
                                errorMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isLoading,
                            label = { Text("Choose a username") }
                        )
                    }

                    if (mode != AuthMode.Recover) {
                        AuthFieldLabel("PASSWORD")

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = {
                                passwordInput = it
                                errorMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isLoading,
                            label = { Text("Password") },
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        passwordVisible = !passwordVisible
                                    },
                                    enabled = !isLoading
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = if (passwordVisible) {
                                            "Hide password"
                                        } else {
                                            "Show password"
                                        },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Password
                            )
                        )

                        if (mode == AuthMode.Login) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        mode = AuthMode.Recover
                                        clearMessages()
                                        passwordInput = ""
                                        passwordVisible = false
                                    },
                                    enabled = !isLoading
                                ) {
                                    Text(
                                        text = "Forgot password?",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    if (mode == AuthMode.Recover) {
                        Text(
                            text = "Google sign-in accounts do not have an InChat password to reset. Return to sign in and choose Continue with Google.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Start
                        )
                    }

                    Button(
                        onClick = {
                            when (mode) {
                                AuthMode.Recover -> {
                                    if (emailInput.isBlank()) {
                                        errorMessage = "Enter your email address."
                                        return@Button
                                    }

                                    isLoading = true
                                    errorMessage = null
                                    authViewModel.sendPasswordResetEmail(emailInput) { success, message ->
                                        isLoading = false
                                        if (success) {
                                            keyboardController?.hide()
                                            successMessage = message
                                            mode = AuthMode.Login
                                        } else {
                                            errorMessage = message
                                                ?: "Could not send the reset email. Please try again."
                                        }
                                    }
                                }

                                AuthMode.Login,
                                AuthMode.Register -> {
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
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !isLoading,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(21.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = when (mode) {
                                    AuthMode.Login -> "Log in"
                                    AuthMode.Register -> "Create account"
                                    AuthMode.Recover -> "Send reset link"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (mode == AuthMode.Recover) {
                        TextButton(
                            onClick = {
                                mode = AuthMode.Login
                                clearMessages()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            Text("Back to log in")
                        }
                    }
                }
            }

            if (mode != AuthMode.Recover) {
                Spacer(Modifier.height(21.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    Text(
                        text = "OR",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMono,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }

                Spacer(Modifier.height(17.dp))

                OutlinedButton(
                    onClick = {
                        if (!isLoading) {
                            isLoading = true
                            errorMessage = null

                            coroutineScope.launch {
                                try {
                                    val idToken = GoogleSignInHelper.getIdToken(context)
                                    authViewModel.signInWithGoogle(idToken) { error ->
                                        isLoading = false
                                        errorMessage = error
                                    }
                                } catch (e: Exception) {
                                    isLoading = false
                                    val message = e.message
                                        ?: "Google sign-in failed. Please try again."

                                    errorMessage = if (
                                        message.contains("cancel", ignoreCase = true)
                                    ) {
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
                        .height(52.dp),
                    enabled = !isLoading,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        width = 0.9.dp,
                        color = MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text(
                        text = "Continue with Google",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(13.dp))

                TextButton(
                    onClick = {
                        mode = if (mode == AuthMode.Login) {
                            AuthMode.Register
                        } else {
                            AuthMode.Login
                        }
                        clearMessages()
                        passwordInput = ""
                        passwordVisible = false
                    },
                    enabled = !isLoading
                ) {
                    Text(
                        text = if (mode == AuthMode.Login) {
                            "New here? Create an account"
                        } else {
                            "Already have an account? Log in"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "PRIVATE CONVERSATIONS. MINIMAL IDENTITY.",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontSize = 8.sp,
                    letterSpacing = 0.65.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    if (successMessage != null) {
        AlertDialog(
            onDismissRequest = {
                successMessage = null
            },
            title = {
                Text("Check your email")
            },
            text = {
                Text(successMessage.orEmpty())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        successMessage = null
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun AuthBrandHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(19.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Spacer(Modifier.height(13.dp))

        Text(
            text = "INCHAT",
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "SECURE ACCESS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AuthFieldLabel(
    label: String
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = JetBrainsMono,
            fontSize = 9.sp,
            letterSpacing = 0.85.sp,
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
