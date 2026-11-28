package com.example.inchat.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A small spring press response used by every clickable component through
 * LocalIndication. It replaces box-shaped ripples with a subtle physical
 * compression and a quick, soft return.
 */
private object PressScaleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return PressScaleNode(interactionSource)
    }

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 7319
}

private class PressScaleNode(
    private val interactionSource: InteractionSource
) : Modifier.Node(), DrawModifierNode {
    private val animatedScale = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        animatedScale.animateTo(
                            targetValue = InChatMotion.PressedScale,
                            animationSpec = InChatMotion.pressSpring()
                        )
                    }

                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> {
                        animatedScale.animateTo(
                            targetValue = 1f,
                            animationSpec = InChatMotion.releaseSpring()
                        )
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        scale(
            scale = animatedScale.value,
            pivot = Offset(size.width / 2f, size.height / 2f)
        ) {
            this@draw.drawContent()
        }
    }
}

private val TerminalGreenColorScheme =
    darkColorScheme(
        primary = TerminalGreen,
        onPrimary = Color(0xFF001B05),
        primaryContainer = Color(0xFF0A2810),
        onPrimaryContainer = Color(0xFFC7FFD0),

        secondary = TerminalGreenSoft,
        onSecondary = Color(0xFF001B05),
        secondaryContainer = Color(0xFF122917),
        onSecondaryContainer = Color(0xFFB9F7C1),

        tertiary = TerminalGreenBright,
        onTertiary = Color(0xFF001B05),
        tertiaryContainer = Color(0xFF0C3214),
        onTertiaryContainer = Color(0xFFD2FFD8),

        background = AppBackground,
        onBackground = TextPrimary,

        surface = AppSurface,
        onSurface = TextPrimary,
        surfaceVariant = AppSurfaceVariant,
        onSurfaceVariant = TextSecondary,
        surfaceDim = AppBackground,
        surfaceBright = AppSurfaceVariant,
        surfaceContainerLowest = AppBackground,
        surfaceContainerLow = AppSurface,
        surfaceContainer = AppSurface,
        surfaceContainerHigh = AppSurfaceVariant,
        surfaceContainerHighest = AppSurfaceVariant,
        surfaceTint = Black,

        outline = AppOutline,
        outlineVariant = AppOutlineVariant,

        error = TerminalError,
        onError = Color(0xFF240000),
        errorContainer = Color(0xFF351111),
        onErrorContainer = Color(0xFFFFDADA)
    )


private val LightColorScheme = lightColorScheme(
    primary = LightTerminalGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F4DD),
    onPrimaryContainer = Color(0xFF00210A),
    secondary = LightTerminalGreenSoft,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F1E3),
    onSecondaryContainer = Color(0xFF102B17),
    tertiary = LightTerminalGreenBright,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1F2D8),
    onTertiaryContainer = Color(0xFF00210A),
    background = LightAppBackground,
    onBackground = LightTextPrimary,
    surface = LightAppSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightAppSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceDim = Color(0xFFE2E9E3),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F7F3),
    surfaceContainer = LightAppSurfaceVariant,
    surfaceContainerHigh = Color(0xFFE8EFE9),
    surfaceContainerHighest = Color(0xFFDDE7DF),
    surfaceTint = LightTerminalGreen,
    outline = LightAppOutline,
    outlineVariant = LightAppOutlineVariant,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

/**
 * InChat keeps its terminal-green identity in both appearances.
 * Dark remains the default; light is an explicit user preference.
 */
@Composable
fun InChatTheme(
    isLight: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (isLight) LightColorScheme else TerminalGreenColorScheme,
        typography = Typography
    ) {
        CompositionLocalProvider(
            LocalIndication provides PressScaleIndication,
            content = content
        )
    }
}
