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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.security.EmotionIntensity
import com.example.ui.theme.BabyMood
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
    mood: BabyMood = BabyMood.CALM,
    faceModeEnabled: Boolean = false,
    emotionIntensity: EmotionIntensity = EmotionIntensity.SUBTLE,
    onClick: (() -> Unit)? = null,
    contentDesc: String? = null,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val ext = BabyThemeTokens.colors
    val effectiveMood = if (emotionIntensity == EmotionIntensity.OFF) BabyMood.CALM else mood

    val moodPrimary = when (effectiveMood) {
        BabyMood.CALM -> ext.orbPrimary
        BabyMood.HAPPY -> Color(0xFFFF9F43)
        BabyMood.EXCITED -> Color(0xFFFF6B6B)
        BabyMood.THINKING -> Color(0xFF6366F1)
        BabyMood.EMPATHETIC -> Color(0xFFA78BFA)
        BabyMood.CONCERNED -> Color(0xFFF59E0B)
        BabyMood.ALERT -> Color(0xFFDC2626) // Steady warm crimson (no strobe/flash)
    }

    val moodSecondary = when (effectiveMood) {
        BabyMood.CALM -> ext.orbSecondary
        BabyMood.HAPPY -> Color(0xFF2DD4BF)
        BabyMood.EXCITED -> Color(0xFFFBBF24)
        BabyMood.THINKING -> ext.orbSecondary
        BabyMood.EMPATHETIC -> Color(0xFF38BDF8)
        BabyMood.CONCERNED -> Color(0xFFFB7185)
        BabyMood.ALERT -> Color(0xFFF59E0B)
    }

    val primaryColor by animateColorAsState(
        targetValue = when (state) {
            BabyOrbState.IDLE -> moodPrimary
            BabyOrbState.LISTENING -> if (effectiveMood == BabyMood.ALERT) moodPrimary else ext.orbPrimary
            BabyOrbState.THINKING -> moodSecondary
            BabyOrbState.SPEAKING -> if (effectiveMood == BabyMood.CALM) ext.success else moodPrimary
            BabyOrbState.ERROR -> ext.orbError
        },
        animationSpec = BabyMotionTokens.standardTween(reducedMotion),
        label = "orbPrimaryColor"
    )
    val secondaryColor by animateColorAsState(
        targetValue = when (state) {
            BabyOrbState.IDLE -> moodSecondary
            BabyOrbState.LISTENING -> moodSecondary
            BabyOrbState.THINKING -> moodPrimary
            BabyOrbState.SPEAKING -> moodSecondary
            BabyOrbState.ERROR -> MaterialTheme.colorScheme.errorContainer
        },
        animationSpec = BabyMotionTokens.standardTween(reducedMotion),
        label = "orbSecondaryColor"
    )

    val springAmplitude by animateFloatAsState(
        targetValue = when (state) {
            BabyOrbState.LISTENING -> amplitude.coerceIn(0.08f, 1f)
            BabyOrbState.SPEAKING -> if (effectiveMood == BabyMood.EXCITED) 0.56f else 0.45f
            BabyOrbState.THINKING -> 0.28f
            BabyOrbState.ERROR -> 0.15f
            BabyOrbState.IDLE -> if (effectiveMood == BabyMood.EXCITED) 0.16f else 0.05f
        },
        animationSpec = BabyMotionTokens.orbSpring(reducedMotion),
        label = "orbSpringAmp"
    )

    val infinite = rememberInfiniteTransition(label = "orbInfinite")
    val breathDuration = when {
        reducedMotion -> 12000
        effectiveMood == BabyMood.ALERT -> 3200 // Steady calm glow for safety, never rapid flash
        emotionIntensity == EmotionIntensity.EXPRESSIVE && effectiveMood == BabyMood.EXCITED -> 1600
        else -> 2400
    }
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = breathDuration),
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

    val activePulse = if (reducedMotion || effectiveMood == BabyMood.ALERT) 0f else breath
    val drawFace = faceModeEnabled && size >= 64.dp && state != BabyOrbState.ERROR

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
                    colors = listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                    center = Offset(center.x - coreRadius * 0.32f, center.y - coreRadius * 0.35f),
                    radius = coreRadius * 0.65f
                ),
                radius = coreRadius,
                center = center
            )

            if ((state == BabyOrbState.THINKING || state == BabyOrbState.LISTENING || state == BabyOrbState.SPEAKING) &&
                !reducedMotion &&
                effectiveMood != BabyMood.ALERT
            ) {
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
                        size = Size(ringRadius * 2f, ringRadius * 2f),
                        style = Stroke(width = (radius * 0.06f).coerceAtLeast(2.5f), cap = StrokeCap.Round)
                    )
                }
            }

            if (drawFace) {
                drawMinimalOrbFace(
                    center = center,
                    coreRadius = coreRadius,
                    state = state,
                    mood = effectiveMood,
                    breath = activePulse
                )
            }
        }

        if (showIcon && !drawFace) {
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

private fun DrawScope.drawMinimalOrbFace(
    center: Offset,
    coreRadius: Float,
    state: BabyOrbState,
    mood: BabyMood,
    breath: Float
) {
    val eyeOffsetX = coreRadius * 0.28f
    val eyeOffsetY = -coreRadius * 0.12f
    val glanceX = if (state == BabyOrbState.THINKING || mood == BabyMood.THINKING) {
        (breath - 0.5f) * coreRadius * 0.14f
    } else 0f
    val eyeRadius = (coreRadius * 0.085f).coerceAtLeast(2.4f)
    val strokeW = (coreRadius * 0.075f).coerceAtLeast(2.2f)
    val faceColor = Color.White.copy(alpha = 0.94f)

    // Eyes
    if (mood == BabyMood.HAPPY || mood == BabyMood.EXCITED) {
        // Gentle happy curved eyes or bright eyes
        drawCircle(faceColor, radius = eyeRadius, center = Offset(center.x - eyeOffsetX + glanceX, center.y + eyeOffsetY))
        drawCircle(faceColor, radius = eyeRadius, center = Offset(center.x + eyeOffsetX + glanceX, center.y + eyeOffsetY))
    } else if (mood == BabyMood.EMPATHETIC) {
        // Soft reassuring oval eyes
        drawCircle(faceColor, radius = eyeRadius * 0.95f, center = Offset(center.x - eyeOffsetX, center.y + eyeOffsetY))
        drawCircle(faceColor, radius = eyeRadius * 0.95f, center = Offset(center.x + eyeOffsetX, center.y + eyeOffsetY))
    } else {
        drawCircle(faceColor, radius = eyeRadius, center = Offset(center.x - eyeOffsetX + glanceX, center.y + eyeOffsetY))
        drawCircle(faceColor, radius = eyeRadius, center = Offset(center.x + eyeOffsetX + glanceX, center.y + eyeOffsetY))
    }

    // Mouth arc
    val mouthWidth = coreRadius * 0.44f
    val mouthY = center.y + coreRadius * 0.22f
    when {
        state == BabyOrbState.SPEAKING -> {
            val openH = (coreRadius * (0.14f + breath * 0.08f)).coerceAtLeast(3f)
            drawArc(
                color = faceColor,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(center.x - mouthWidth / 2f, mouthY - openH / 2f),
                size = Size(mouthWidth, openH),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
        mood == BabyMood.HAPPY || mood == BabyMood.EXCITED || mood == BabyMood.EMPATHETIC -> {
            drawArc(
                color = faceColor,
                startAngle = 12f,
                sweepAngle = 156f,
                useCenter = false,
                topLeft = Offset(center.x - mouthWidth / 2f, mouthY - coreRadius * 0.14f),
                size = Size(mouthWidth, coreRadius * 0.28f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
        mood == BabyMood.CONCERNED || mood == BabyMood.ALERT -> {
            drawLine(
                color = faceColor,
                start = Offset(center.x - mouthWidth * 0.35f, mouthY),
                end = Offset(center.x + mouthWidth * 0.35f, mouthY),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
        else -> {
            // Calm gentle smile
            drawArc(
                color = faceColor,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(center.x - mouthWidth * 0.45f, mouthY - coreRadius * 0.09f),
                size = Size(mouthWidth * 0.9f, coreRadius * 0.18f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
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
