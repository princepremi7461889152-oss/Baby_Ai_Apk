package com.example.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

enum class MicPipelineState(val label: String) {
    STOPPED("Stopped"),
    LISTENING("Listening"),
    CAPTURING("Capturing"),
    PAUSED_FOR_TTS("Paused (TTS)"),
    MIC_BUSY("Mic Busy")
}

data class WakeSpotterResult(
    val detected: Boolean,
    val confidence: Float,
    val rmsLevel: Float,
    val label: String = "Hey Baby"
)

/**
 * Pluggable wake-word interface so the acoustic energy/cadence engine can be swapped cleanly
 * for Porcupine, Vosk, or a custom TFLite KWS model without modifying the mic service loop.
 */
interface KeywordSpotter {
    val engineName: String
    val cooldownMs: Long
        get() = 2_000L

    fun evaluateFrame(
        pcm16: ShortArray,
        length: Int,
        sampleRate: Int,
        sensitivity: Float
    ): WakeSpotterResult

    fun reset()
}

/**
 * Lightweight, zero-SpeechRecognizer 16 kHz PCM16 acoustic wake gate.
 *
 * Uses short-term RMS speech energy + zero-crossing speech-band cadence + multi-frame onset
 * consistency so pure background rumble/clicks do not trigger, while keeping CPU overhead tiny.
 * Swap this binding in DI for an exact phrase KWS engine (Porcupine/Vosk/TFLite) when bundled.
 */
@Singleton
class AcousticWakeSpotter @Inject constructor() : KeywordSpotter {

    override val engineName: String = "Acoustic Speech-Band KWS (Replaceable)"

    private var onsetStreak = 0
    private var lastTriggerMs = 0L

    override fun evaluateFrame(
        pcm16: ShortArray,
        length: Int,
        sampleRate: Int,
        sensitivity: Float
    ): WakeSpotterResult {
        if (length <= 0) return WakeSpotterResult(false, 0f, 0f)

        var sumSq = 0.0
        var zeroCrossings = 0
        var prevSign = pcm16[0] >= 0
        for (i in 0 until length) {
            val sample = pcm16[i]
            sumSq += sample.toDouble() * sample.toDouble()
            val curSign = sample >= 0
            if (i > 0 && curSign != prevSign) {
                zeroCrossings++
            }
            prevSign = curSign
        }

        val rms = sqrt(sumSq / length).toFloat()
        val durationSec = length.toFloat() / sampleRate.coerceAtLeast(8_000)
        val zcrHz = zeroCrossings / (2f * durationSec.coerceAtLeast(0.005f))

        // Speech-like ZCR typically sits roughly between 180 Hz and 2,400 Hz
        val speechBandMatch = if (zcrHz in 160f..2600f) 1.0f else 0.35f

        val clampedSens = sensitivity.coerceIn(0.1f, 1.0f)
        val rmsThreshold = (1_450f - (clampedSens * 950f)).coerceIn(360f, 1_400f)
        val rmsRatio = (rms / rmsThreshold).coerceIn(0f, 1.8f)

        val frameScore = (rmsRatio * 0.65f * speechBandMatch + (if (rms >= rmsThreshold) 0.35f else 0f))
            .coerceIn(0f, 1f)

        val isVoiceFrame = rms >= rmsThreshold && speechBandMatch >= 0.9f
        onsetStreak = if (isVoiceFrame) (onsetStreak + 1).coerceAtMost(8) else (onsetStreak - 1).coerceAtLeast(0)

        val requiredStreak = when {
            clampedSens >= 0.85f -> 2
            clampedSens >= 0.55f -> 3
            else -> 4
        }

        val now = System.currentTimeMillis()
        val cooldownPassed = (now - lastTriggerMs) >= cooldownMs
        val detected = onsetStreak >= requiredStreak && cooldownPassed && frameScore >= 0.62f

        if (detected) {
            lastTriggerMs = now
            onsetStreak = 0
        }

        return WakeSpotterResult(
            detected = detected,
            confidence = frameScore,
            rmsLevel = rms,
            label = "Hey Baby / Baby"
        )
    }

    override fun reset() {
        onsetStreak = 0
    }
}
