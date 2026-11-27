package com.example.inchat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * InChat's compact action-card dialog.
 *
 * Destructive confirmations use the same red cancel and warning-action rows
 * as the contextual chat and message action cards.
 */
@Composable
fun InChatActionDialog(
    title: String,
    description: String? = null,
    confirmLabel: String,
    confirmIcon: ImageVector,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    titleIcon: ImageVector? = null,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    confirmDestructive: Boolean = true,
    showCancel: Boolean = true,
    cancelLabel: String = "Cancel",
    secondaryActionLabel: String? = null,
    secondaryActionIcon: ImageVector? = null,
    secondaryActionDestructive: Boolean = true,
    secondaryActionEnabled: Boolean = true,
    onSecondaryAction: (() -> Unit)? = null,
    isLoading: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Dialog(
        onDismissRequest = {
            if (dismissEnabled) onDismiss()
        }
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(120)) +
                scaleIn(initialScale = 0.94f, animationSpec = tween(150))
        ) {
            Surface(
                modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
                shadowElevation = 18.dp,
                border = BorderStroke(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (titleIcon != null) {
                            Icon(
                                imageVector = titleIcon,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (!description.isNullOrBlank()) {
                        Text(
                            text = description,
                            modifier = Modifier.padding(
                                start = 10.dp,
                                end = 10.dp,
                                bottom = 8.dp
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    content()

                    if (showCancel) {
                        ActionDialogRow(
                            icon = Icons.Default.Clear,
                            title = cancelLabel,
                            destructive = true,
                            enabled = dismissEnabled,
                            onClick = onDismiss
                        )
                    }

                    if (
                        secondaryActionLabel != null &&
                        secondaryActionIcon != null &&
                        onSecondaryAction != null
                    ) {
                        ActionDialogRow(
                            icon = secondaryActionIcon,
                            title = secondaryActionLabel,
                            destructive = secondaryActionDestructive,
                            enabled = secondaryActionEnabled,
                            onClick = onSecondaryAction
                        )
                    }

                    ActionDialogRow(
                        icon = confirmIcon,
                        title = confirmLabel,
                        destructive = confirmDestructive,
                        enabled = confirmEnabled && !isLoading,
                        loading = isLoading,
                        onClick = onConfirm
                    )

                    Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionDialogRow(
    icon: ImageVector,
    title: String,
    destructive: Boolean,
    enabled: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    val foreground = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (destructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = foreground
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = foreground
                )
            }
            Spacer(Modifier.width(11.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Medium,
                color = foreground
            )
        }
    }
}
