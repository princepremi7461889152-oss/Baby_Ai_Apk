package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
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

@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val ttsManager: BabyTtsManager,
    private val audioRecorderManager: AudioRecorderManager,
    private val stopSignalBus: StopSignalBus
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var shouldBeListening = false
    private var isRestartScheduled = false
    private var lastTriggerMs = 0L
    private var onWakeCallback: ((WakeDetectionEvent) -> Unit)? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastHeardSnippet = MutableStateFlow("")
    val lastHeardSnippet: StateFlow<String> = _lastHeardSnippet.asStateFlow()

    fun start(onWake: (WakeDetectionEvent) -> Unit) {
        onWakeCallback = onWake
        shouldBeListening = true
        mainHandler.post { startInternal() }
    }

    fun pauseForRecording() {
        mainHandler.post {
            _isListening.value = false
            runCatching {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }
    }

    fun resumeAfterRecording() {
        if (shouldBeListening && keyStore.settings.value.wakeWordEnabled) {
            scheduleRestart(450L)
        }
    }

    fun stop() {
        shouldBeListening = false
        mainHandler.post {
            _isListening.value = false
            runCatching {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }
    }

    private fun startInternal() {
        if (!shouldBeListening || !keyStore.settings.value.wakeWordEnabled) {
            _isListening.value = false
            return
        }
        if (ttsManager.isSpeaking.value || audioRecorderManager.isRecording.value) {
            scheduleRestart(700L)
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic || !SpeechRecognizer.isRecognitionAvailable(context)) {
            _isListening.value = false
            return
        }

        runCatching {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 4000L
                        else -> 500L
                    }
                    scheduleRestart(delay)
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    handleHypotheses(matches, confidences, isPartial = false)
                    scheduleRestart(350L)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    handleHypotheses(matches, null, isPartial = true)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            }
            speechRecognizer = recognizer
            recognizer.startListening(intent)
        }.onFailure {
            _isListening.value = false
            scheduleRestart(1500L)
        }
    }

    private fun handleHypotheses(
        matches: List<String>,
        confidences: FloatArray?,
        isPartial: Boolean
    ) {
        if (matches.isEmpty() || ttsManager.isSpeaking.value || audioRecorderManager.isRecording.value) return
        val top = matches.first().trim()
        if (top.isBlank()) return
        _lastHeardSnippet.value = top

        val sensitivity = keyStore.settings.value.wakeWordSensitivity
        // Higher sensitivity allows lower confidence threshold & partial matches
        val minConfidence = (0.85f - (sensitivity * 0.55f)).coerceIn(0.20f, 0.80f)
        val topConfidence = confidences?.firstOrNull() ?: 0.75f

        for (candidate in matches) {
            val lower = candidate.lowercase().trim()
            if (isStopCommand(lower)) {
                stopSignalBus.requestStop("Voice command: Baby stop")
                return
            }

            if (isPartial && sensitivity < 0.45f) continue
            if (!isPartial && topConfidence in 0.01f..minConfidence && !lower.contains("hey baby")) continue

            val match = extractWakeAndCommand(candidate)
            if (match != null) {
                val now = System.currentTimeMillis()
                if (now - lastTriggerMs < COOLDOWN_MS) return
                lastTriggerMs = now
                pauseForRecording()
                onWakeCallback?.invoke(match)
                return
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!shouldBeListening || isRestartScheduled) return
        isRestartScheduled = true
        mainHandler.postDelayed({
            isRestartScheduled = false
            startInternal()
        }, delayMs)
    }

    companion object {
        const val COOLDOWN_MS = 2000L

        fun isStopCommand(text: String): Boolean {
            val t = text.lowercase().trim()
            return t.contains("baby stop") ||
                t.contains("stop baby") ||
                t.contains("hey baby stop") ||
                t.contains("baby ruk") ||
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
