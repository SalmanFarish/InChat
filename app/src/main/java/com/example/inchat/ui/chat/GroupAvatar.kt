package com.example.inchat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.inchat.ui.theme.JetBrainsMono

@Composable
fun GroupAvatar(
    groupName: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 24.sp
) {
    val initial = groupName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G"

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                maxLines = 1
            )
        }
    }
}
