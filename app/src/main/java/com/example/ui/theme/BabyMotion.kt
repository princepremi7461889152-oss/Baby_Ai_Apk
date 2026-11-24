package com.example.ui.theme

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object BabyMotionTokens {
    const val DURATION_FAST = 180
    const val DURATION_STANDARD = 240
    const val DURATION_EMPHASIZED = 300

    val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    fun <T> standardTween(reducedMotion: Boolean) = tween<T>(
        durationMillis = if (reducedMotion) 0 else DURATION_STANDARD,
        easing = EaseOut
    )

    fun <T> fastTween(reducedMotion: Boolean) = tween<T>(
        durationMillis = if (reducedMotion) 0 else DURATION_FAST,
        easing = EaseOut
    )

    fun slideOffsetTween(reducedMotion: Boolean) = tween<IntOffset>(
        durationMillis = if (reducedMotion) 0 else DURATION_EMPHASIZED,
        easing = EaseOut
    )

    fun orbSpring(reducedMotion: Boolean) = if (reducedMotion) {
        spring<Float>(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy)
    } else {
        spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.58f)
    }
}

@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        isSystemReducedMotion(context)
    }
}

fun isSystemReducedMotion(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return scale == 0f
}

object BabyHaptics {
    fun tap(haptic: HapticFeedback) {
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    fun voiceStart(haptic: HapticFeedback) {
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    fun voiceStop(haptic: HapticFeedback) {
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}

object BabySoundCues {
    fun playStartListening(enabled: Boolean) {
        if (!enabled) return
        playToneAsync(ToneGenerator.TONE_PROP_BEEP, 70)
    }

    fun playStopListening(enabled: Boolean) {
        if (!enabled) return
        playToneAsync(ToneGenerator.TONE_PROP_ACK, 65)
    }

    private fun playToneAsync(toneType: Int, durationMs: Int) {
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
                generator.startTone(toneType, durationMs)
                kotlinx.coroutines.delay(durationMs + 40L)
                generator.release()
            }
        }
    }
}
