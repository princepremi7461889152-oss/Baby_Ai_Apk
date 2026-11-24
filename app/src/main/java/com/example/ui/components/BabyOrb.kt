package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BabyMotionTokens
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.rememberReducedMotionEnabled
import kotlin.math.sin

enum class BabyOrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

@Composable
fun BabyOrb(
    state: BabyOrbState,
    amplitude: Float = 0f,
    size: Dp = 64.dp,
    showIcon: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentDesc: String? = null,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val ext = BabyThemeTokens.colors

    val primaryColor by animateColorAsState(
        targetValue = when (state) {
            BabyOrbState.IDLE -> ext.orbPrimary
            BabyOrbState.LISTENING -> ext.orbPrimary
            BabyOrbState.THINKING -> ext.orbSecondary
            BabyOrbState.SPEAKING -> ext.success
            BabyOrbState.ERROR -> ext.orbError
        },
        animationSpec = BabyMotionTokens.standardTween(reducedMotion),
        label = "orbPrimaryColor"
    )
    val secondaryColor by animateColorAsState(
        targetValue = when (state) {
            BabyOrbState.IDLE -> ext.orbSecondary
            BabyOrbState.LISTENING -> ext.orbSecondary
            BabyOrbState.THINKING -> ext.orbPrimary
            BabyOrbState.SPEAKING -> ext.orbSecondary
            BabyOrbState.ERROR -> MaterialTheme.colorScheme.errorContainer
        },
        animationSpec = BabyMotionTokens.standardTween(reducedMotion),
        label = "orbSecondaryColor"
    )

    val springAmplitude by animateFloatAsState(
        targetValue = when (state) {
            BabyOrbState.LISTENING -> amplitude.coerceIn(0.08f, 1f)
            BabyOrbState.SPEAKING -> 0.45f
            BabyOrbState.THINKING -> 0.28f
            BabyOrbState.ERROR -> 0.15f
            BabyOrbState.IDLE -> 0.05f
        },
        animationSpec = BabyMotionTokens.orbSpring(reducedMotion),
        label = "orbSpringAmp"
    )

    val infinite = rememberInfiniteTransition(label = "orbInfinite")
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reducedMotion) 12000 else 2400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbBreath"
    )
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reducedMotion) 20000 else 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbSpin"
    )

    val activePulse = if (reducedMotion) 0f else breath

    val baseModifier = modifier
        .size(size)
        .clip(CircleShape)
        .let { m ->
            if (onClick != null) {
                m.clickable(role = Role.Button, onClick = onClick)
            } else m
        }
        .let { m ->
            if (contentDesc != null) {
                m.semantics { contentDescription = contentDesc }
            } else m
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier = baseModifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)

            val haloFraction = (0.72f + springAmplitude * 0.22f + activePulse * 0.06f).coerceIn(0.65f, 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.34f + springAmplitude * 0.22f),
                        secondaryColor.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * haloFraction
                ),
                radius = radius * haloFraction,
                center = center
            )

            val coreScale = when (state) {
                BabyOrbState.IDLE -> 0.68f + activePulse * 0.03f
                BabyOrbState.LISTENING -> 0.72f + springAmplitude * 0.12f
                BabyOrbState.THINKING -> 0.70f + activePulse * 0.05f
                BabyOrbState.SPEAKING -> 0.72f + activePulse * 0.07f
                BabyOrbState.ERROR -> 0.70f
            }
            val coreRadius = radius * coreScale

            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor, secondaryColor),
                    start = Offset(center.x - coreRadius, center.y - coreRadius),
                    end = Offset(center.x + coreRadius, center.y + coreRadius)
                ),
                radius = coreRadius,
                center = center
            )

            // Specular depth highlight
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(center.x - coreRadius * 0.32f, center.y - coreRadius * 0.35f),
                    radius = coreRadius * 0.65f
                ),
                radius = coreRadius,
                center = center
            )

            if ((state == BabyOrbState.THINKING || state == BabyOrbState.LISTENING || state == BabyOrbState.SPEAKING) && !reducedMotion) {
                rotate(degrees = rotation, pivot = center) {
                    val ringRadius = coreRadius + (radius - coreRadius) * 0.45f
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.9f),
                                secondaryColor.copy(alpha = 0.75f),
                                Color.Transparent
                            )
                        ),
                        startAngle = 0f,
                        sweepAngle = 220f,
                        useCenter = false,
                        topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                        size = androidx.compose.ui.geometry.Size(ringRadius * 2f, ringRadius * 2f),
                        style = Stroke(width = (radius * 0.06f).coerceAtLeast(2.5f), cap = StrokeCap.Round)
                    )
                }
            }
        }

        if (showIcon) {
            val icon = when (state) {
                BabyOrbState.IDLE -> Icons.Default.Mic
                BabyOrbState.LISTENING -> Icons.Default.GraphicEq
                BabyOrbState.THINKING -> Icons.Default.AutoAwesome
                BabyOrbState.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
                BabyOrbState.ERROR -> Icons.Default.ErrorOutline
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size((size.value * 0.36f).dp)
            )
        }
    }
}

@Composable
fun BabyWaveformStrip(
    isActive: Boolean,
    amplitude: Float,
    barCount: Int = 5,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val infinite = rememberInfiniteTransition(label = "waveStrip")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reducedMotion) 8000 else 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val targetFactor = if (!isActive) {
                0.28f
            } else if (reducedMotion) {
                0.55f + (i % 2) * 0.2f
            } else {
                val wave = (sin(phase + i * 0.95f) + 1f) * 0.5f
                (0.32f + wave * (0.45f + amplitude.coerceIn(0f, 1f) * 0.45f)).coerceIn(0.25f, 1f)
            }
            val barHeight by animateFloatAsState(
                targetValue = 18f * targetFactor,
                animationSpec = BabyMotionTokens.fastTween(reducedMotion),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (isActive) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outline
                    )
            )
        }
    }
}
