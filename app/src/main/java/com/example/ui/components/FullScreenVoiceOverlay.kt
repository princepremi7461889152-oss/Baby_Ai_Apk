package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.security.EmotionIntensity
import com.example.domain.ai.AgentExecutionState
import com.example.ui.theme.BabyMood
import com.example.ui.theme.BabyMotionTokens
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.rememberReducedMotionEnabled
import com.example.ui.theme.withDevanagariFallback
import kotlin.math.roundToInt

@Composable
fun FullScreenVoiceOverlay(
    visible: Boolean,
    isRecording: Boolean,
    isSpeaking: Boolean,
    micAmplitude: Float,
    agentState: AgentExecutionState,
    transcriptOrReply: String,
    isOffline: Boolean,
    faceModeEnabled: Boolean = false,
    emotionIntensity: EmotionIntensity = EmotionIntensity.SUBTLE,
    hapticsEnabled: Boolean = true,
    onTapOrb: () -> Unit,
    onStopTask: () -> Unit,
    onDismiss: () -> Unit
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val haptic = LocalHapticFeedback.current
    val spacing = BabyThemeTokens.spacing

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(BabyMotionTokens.standardTween(reducedMotion)) +
            slideInVertically(BabyMotionTokens.slideOffsetTween(reducedMotion)) { it / 4 },
        exit = fadeOut(BabyMotionTokens.fastTween(reducedMotion)) +
            slideOutVertically(BabyMotionTokens.slideOffsetTween(reducedMotion)) { it / 4 }
    ) {
        BackHandler(enabled = visible) { onDismiss() }

        var dragOffsetY by remember { mutableFloatStateOf(0f) }

        val orbState = when {
            isRecording -> BabyOrbState.LISTENING
            agentState.isRunning -> BabyOrbState.THINKING
            isSpeaking -> BabyOrbState.SPEAKING
            agentState.statusText.contains("error", ignoreCase = true) ||
                agentState.statusText.contains("fail", ignoreCase = true) -> BabyOrbState.ERROR
            else -> BabyOrbState.IDLE
        }

        val stateLabel = when (orbState) {
            BabyOrbState.LISTENING -> "Sun rahi hoon… bolo"
            BabyOrbState.THINKING -> "Soch rahi hoon (${agentState.currentStep}/${agentState.maxSteps})"
            BabyOrbState.SPEAKING -> "Baby bol rahi hai…"
            BabyOrbState.ERROR -> "Kuch gadbad hui — phir try karo"
            BabyOrbState.IDLE -> if (isOffline) "Offline mode — local commands ready" else "Tap orb to speak"
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, dragOffsetY.roundToInt().coerceAtLeast(0)) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY > 180f) {
                                onDismiss()
                            }
                            dragOffsetY = 0f
                        },
                        onDragCancel = { dragOffsetY = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    )
                }
                .testTag("fullscreen_voice_overlay")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = spacing.maxContentWidth)
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Drag pill + Top bar
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(99.dp),
                            modifier = Modifier
                                .width(42.dp)
                                .height(4.dp)
                        ) {}
                        Spacer(Modifier.height(spacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(spacing.chipRadius)
                            ) {
                                val moodBadge = if (emotionIntensity != EmotionIntensity.OFF && agentState.activeMood != BabyMood.CALM) {
                                    " • ${agentState.activeMood.label}"
                                } else ""
                                Text(
                                    text = (agentState.activeBrain?.label ?: "Baby Voice Mode") + moodBadge,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("close_voice_overlay_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close voice mode"
                                )
                            }
                        }
                    }

                    // Center Reactive Orb + Status + Transcript
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        BabyOrb(
                            state = orbState,
                            amplitude = micAmplitude,
                            size = 188.dp,
                            mood = agentState.activeMood,
                            faceModeEnabled = faceModeEnabled,
                            emotionIntensity = emotionIntensity,
                            onClick = {
                                if (hapticsEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                onTapOrb()
                            },
                            contentDesc = "Voice mode orb, $stateLabel. Tap to interrupt or toggle.",
                            modifier = Modifier.testTag("fullscreen_voice_orb")
                        )

                        Spacer(Modifier.height(spacing.lg))

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(99.dp)
                        ) {
                            Text(
                                text = stateLabel.withDevanagariFallback(),
                                style = MaterialTheme.typography.titleSmall,
                                color = when (orbState) {
                                    BabyOrbState.ERROR -> MaterialTheme.colorScheme.error
                                    BabyOrbState.SPEAKING -> BabyThemeTokens.colors.success
                                    BabyOrbState.THINKING -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        Spacer(Modifier.height(spacing.md))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = (transcriptOrReply.ifBlank {
                                    "\"Hey Baby, flashlight on karo\" ya koi bhi phone command bolo."
                                }).withDevanagariFallback(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(spacing.sm))
                        Text(
                            text = stringResource(R.string.voice_tap_interrupt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Bottom Action Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Type instead")
                        }
                        if (agentState.isRunning) {
                            Button(
                                onClick = onStopTask,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("voice_overlay_stop_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("STOP")
                            }
                        }
                    }
                }
            }
        }
    }
}
