package com.example.inchat.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.ripple.LocalIndication
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
                            targetValue = 0.965f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    }

                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> {
                        animatedScale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        scale(
            scale = animatedScale.value,
            pivot = size.center
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

/**
 * InChat uses its AMOLED terminal-green appearance on every device.
 * The system light/dark setting does not switch to a white interface.
 */
@Composable
fun InChatTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TerminalGreenColorScheme,
        typography = Typography
    ) {
        CompositionLocalProvider(
            LocalIndication provides PressScaleIndication,
            content = content
        )
    }
}
