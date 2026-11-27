package com.example.inchat.ui.profile

import com.example.inchat.ui.components.inChatOutlinedTextFieldColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.data.repository.UserRepository
import com.example.inchat.ui.theme.GoogleSansFlex
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    username: String,
    uid: String,
    currentDisplayName: String,
    currentBio: String,
    onBackClick: () -> Unit,
    onProfileSaved: (String, String) -> Unit
) {
    val repository = remember { UserRepository() }
    val coroutineScope = rememberCoroutineScope()

    var displayName by remember(currentDisplayName) {
        mutableStateOf(currentDisplayName)
    }
    var bio by remember(currentBio) {
        mutableStateOf(currentBio)
    }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val cleanName = displayName.trim()
    val cleanBio = bio.trim()
    val canSave = !saving && cleanName.length <= 30 && cleanBio.length <= 160

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Edit profile",
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(
                        enabled = !saving,
                        onClick = onBackClick
                    ) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    0.8.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "DISPLAY NAME",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = GoogleSansFlex,
                            fontSize = 10.sp,
                            letterSpacing = 0.9.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        colors = inChatOutlinedTextFieldColors(),
                        value = displayName,
                        onValueChange = {
                            if (it.length <= 30) {
                                displayName = it
                                errorMessage = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Your name") },
                        supportingText = {
                            Text(
                                text = "${displayName.length}/30",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        enabled = !saving
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "BIO",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = GoogleSansFlex,
                            fontSize = 10.sp,
                            letterSpacing = 0.9.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        colors = inChatOutlinedTextFieldColors(),
                        value = bio,
                        onValueChange = {
                            if (it.length <= 160) {
                                bio = it
                                errorMessage = null
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(154.dp),
                        placeholder = { Text("Tell people a little about yourself...") },
                        supportingText = {
                            Text(
                                text = "${bio.length}/160",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        enabled = !saving,
                        maxLines = 5
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    0.8.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = "YOUR USERNAME",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = GoogleSansFlex,
                            fontSize = 10.sp,
                            letterSpacing = 0.9.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "@$username",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Your username is your permanent InChat identity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Button(
                onClick = {
                    if (cleanName.length > 30) {
                        errorMessage = "Display name must be 30 characters or less."
                        return@Button
                    }
                    if (cleanBio.length > 160) {
                        errorMessage = "Bio must be 160 characters or less."
                        return@Button
                    }
                    if (uid.isBlank()) {
                        errorMessage = "Unable to identify your account."
                        return@Button
                    }

                    saving = true
                    errorMessage = null
                    coroutineScope.launch {
                        repository.updateProfile(
                            uid = uid,
                            displayName = cleanName,
                            bio = cleanBio
                        ).onSuccess {
                            saving = false
                            onProfileSaved(cleanName, cleanBio)
                        }.onFailure { error ->
                            saving = false
                            errorMessage = error.message ?: "Could not save profile."
                        }
                    }
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = "Save changes",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
