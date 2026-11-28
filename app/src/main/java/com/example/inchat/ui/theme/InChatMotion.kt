package com.example.inchat.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Shared motion language for InChat.
 *
 * Spatial changes use interruptible springs so gestures and navigation feel
 * connected. Opacity/color effects use short eased tweens so they stay calm
 * and do not compete with the movement.
 */
object InChatMotion {
    const val PressedScale = 0.972f

    fun <T> navigationSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.86f,
        stiffness = 360f
    )

    fun <T> contentSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.88f,
        stiffness = 320f
    )

    fun <T> pressSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.82f,
        stiffness = 520f
    )

    fun <T> releaseSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.76f,
        stiffness = 420f
    )

    fun <T> effects(durationMillis: Int = 190): TweenSpec<T> = tween(
        durationMillis = durationMillis,
        easing = FastOutSlowInEasing
    )
}
