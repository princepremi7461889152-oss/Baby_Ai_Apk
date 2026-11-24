package com.example.voice

import android.content.Context
import com.example.core.security.SecureKeyStore
import com.example.domain.tools.StopSignalBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class WakeDetectionEvent(
    val phraseMatched: String,
    val trailingCommand: String?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Wake-word coordinator powered by [MicPipelineManager] + replaceable [KeywordSpotter].
 * Uses NO SpeechRecognizer — single 16 kHz AudioRecord loop with 1s circular pre-roll.
 */
@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val micPipeline: MicPipelineManager,
    private val stopSignalBus: StopSignalBus
) {
    private val _lastHeardSnippet = MutableStateFlow("")
    val lastHeardSnippet: StateFlow<String> = _lastHeardSnippet.asStateFlow()

    val isListening: StateFlow<Boolean>
        get() = micPipeline.isListening

    val pipelineState: StateFlow<MicPipelineState>
        get() = micPipeline.state

    val rmsLevel: StateFlow<Float>
        get() = micPipeline.rmsLevel

    val wakeScore: StateFlow<Float>
        get() = micPipeline.wakeScore

    val watchdogRestarts: StateFlow<Int>
        get() = micPipeline.watchdogRestarts

    val watchdogLogs: StateFlow<List<String>>
        get() = micPipeline.watchdogLogs

    val spotterEngineName: String
        get() = micPipeline.spotterEngineName

    fun start(onWake: (WakeDetectionEvent) -> Unit) {
        if (!keyStore.settings.value.wakeWordEnabled) {
            micPipeline.stopWakePipeline()
            return
        }
        micPipeline.startWakePipeline { event ->
            _lastHeardSnippet.value = event.phraseMatched
            if (isStopCommand(event.trailingCommand.orEmpty())) {
                stopSignalBus.requestStop("Voice command: Baby stop")
            } else {
                onWake(event)
            }
        }
    }

    fun pauseForRecording() {
        // Handled cleanly inside MicPipelineManager when beginCommandCapture() transitions to CAPTURING
    }

    fun resumeAfterRecording() {
        // Handled cleanly inside MicPipelineManager when finishCaptureAndBuildWav() returns to LISTENING
    }

    fun stop() {
        micPipeline.stopWakePipeline()
    }

    fun simulateManualWakeTrigger() {
        micPipeline.simulateManualWakeTrigger()
    }

    companion object {
        const val COOLDOWN_MS = 2_000L

        fun computeMinConfidence(sensitivity: Float): Float =
            (0.85f - (sensitivity * 0.55f)).coerceIn(0.20f, 0.80f)

        fun isVoiceEnergyAboveThreshold(rms: Float, sensitivity: Float): Boolean {
            val threshold = (1_450f - (sensitivity.coerceIn(0.1f, 1.0f) * 950f)).coerceIn(360f, 1_400f)
            return rms >= threshold
        }

        fun isStopCommand(text: String): Boolean {
            val t = text.lowercase().trim()
            return t.contains("baby stop") ||
                t.contains("stop baby") ||
                t.contains("hey baby stop") ||
                t.contains("baby ruk") ||
                t.contains("baby bolna band") ||
                t.contains("bolna band") ||
                t.contains("बेबी स्टॉप") ||
                t.contains("बेबी रुक")
        }

        fun extractWakeAndCommand(rawText: String): WakeDetectionEvent? {
            val lower = rawText.lowercase().trim()
            val triggers = listOf(
                "hey baby",
                "ok baby",
                "hi baby",
                "hello baby",
                "हे बेबी",
                "बेबी",
                "baby"
            )
            for (trigger in triggers) {
                val idx = lower.indexOf(trigger)
                if (idx >= 0) {
                    val after = rawText.substring((idx + trigger.length).coerceAtMost(rawText.length))
                        .trim(' ', ',', '.', ':', '!', '?')
                        .takeIf { it.length >= 2 }
                    return WakeDetectionEvent(
                        phraseMatched = trigger,
                        trailingCommand = after
                    )
                }
            }
            return null
        }
    }
}
