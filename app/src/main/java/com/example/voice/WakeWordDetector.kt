package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

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
    private val vadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vadGateJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var shouldBeListening = false
    private var isRestartScheduled = false
    private var lastTriggerMs = 0L
    private var quietStreak = 0
    private var onWakeCallback: ((WakeDetectionEvent) -> Unit)? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastHeardSnippet = MutableStateFlow("")
    val lastHeardSnippet: StateFlow<String> = _lastHeardSnippet.asStateFlow()

    fun start(onWake: (WakeDetectionEvent) -> Unit) {
        onWakeCallback = onWake
        if (shouldBeListening && (speechRecognizer != null || isRestartScheduled || vadGateJob?.isActive == true)) {
            return
        }
        shouldBeListening = true
        quietStreak = 0
        mainHandler.post { startInternal() }
    }

    fun pauseForRecording() {
        vadGateJob?.cancel()
        vadGateJob = null
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
            quietStreak = 0
            scheduleRestart(450L)
        }
    }

    fun stop() {
        shouldBeListening = false
        quietStreak = 0
        vadGateJob?.cancel()
        vadGateJob = null
        mainHandler.removeCallbacksAndMessages(null)
        isRestartScheduled = false
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

        // After 3 consecutive silent timeouts, drop into a low-power 8kHz VAD gate
        // until ambient speech crosses the sensitivity threshold.
        if (quietStreak >= 3) {
            startLowPowerVadGate()
            return
        }

        runCatching {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {
                    quietStreak = 0
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        quietStreak = (quietStreak + 1).coerceAtMost(10)
                    } else {
                        quietStreak = 0
                    }
                    val delayMs = nextAdaptiveDelayMs(quietStreak, error)
                    scheduleRestart(delayMs)
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    quietStreak = 0
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    handleHypotheses(matches, confidences, isPartial = false)
                    scheduleRestart(350L)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    quietStreak = 0
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

    @SuppressLint("MissingPermission")
    private fun startLowPowerVadGate() {
        if (vadGateJob?.isActive == true) return
        _isListening.value = false
        vadGateJob = vadScope.launch {
            val sampleRate = 8_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                delay(1800L)
                quietStreak = 0
                mainHandler.post { startInternal() }
                return@launch
            }

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf.coerceAtLeast(1600)
                )
            }.getOrNull()

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                delay(1800L)
                quietStreak = 0
                mainHandler.post { startInternal() }
                return@launch
            }

            val frame = ShortArray(400) // 50ms @ 8kHz
            var consecutiveErrors = 0
            try {
                recorder.startRecording()
                while (isActive && shouldBeListening && keyStore.settings.value.wakeWordEnabled) {
                    if (ttsManager.isSpeaking.value || audioRecorderManager.isRecording.value) {
                        delay(400L)
                        continue
                    }
                    val read = recorder.read(frame, 0, frame.size)
                    if (read <= 0) {
                        consecutiveErrors++
                        if (consecutiveErrors >= 10) break
                        delay(150L)
                        continue
                    }
                    consecutiveErrors = 0
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = frame[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / read).toFloat()
                    if (isVoiceEnergyAboveThreshold(rms, keyStore.settings.value.wakeWordSensitivity)) {
                        break
                    }
                    delay(120L)
                }
            } catch (_: Exception) {
                delay(1500L)
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
            }

            if (shouldBeListening && keyStore.settings.value.wakeWordEnabled) {
                quietStreak = 0
                mainHandler.post { startInternal() }
            }
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
        val minConfidence = computeMinConfidence(sensitivity)
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

        fun computeMinConfidence(sensitivity: Float): Float =
            (0.85f - (sensitivity * 0.55f)).coerceIn(0.20f, 0.80f)

        fun isVoiceEnergyAboveThreshold(rms: Float, sensitivity: Float): Boolean {
            val threshold = (950f - (sensitivity.coerceIn(0.1f, 1.0f) * 600f)).coerceIn(280f, 900f)
            return rms >= threshold
        }

        fun nextAdaptiveDelayMs(quietStreak: Int, errorCode: Int): Long = when (errorCode) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 4000L
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2200L
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> when {
                quietStreak <= 1 -> 450L
                quietStreak == 2 -> 900L
                else -> 1500L
            }
            else -> 700L
        }

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
