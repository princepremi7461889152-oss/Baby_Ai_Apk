package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.core.security.SecureKeyStore
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Single long-lived 16 kHz PCM16 AudioRecord pipeline serving both wake-word detection
 * (via pluggable [KeywordSpotter]) and command voice capture with a 1-second circular pre-roll buffer.
 *
 * Guarantees:
 * - Only ONE AudioRecord loop opens the mic at any time.
 * - 1s pre-roll PCM ring buffer is prepended into command WAV capture so the command start isn't clipped.
 * - 300ms post-TTS ignore window prevents Baby from waking on her own TTS output.
 * - Exponential retry backoff (1s -> 2s -> 4s -> ... -> max 30s) + MIC_BUSY state & watchdog logs.
 */
@Singleton
class MicPipelineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val keywordSpotter: KeywordSpotter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureLock = Any()

    private var pipelineJob: Job? = null
    private var shouldRunWakeLoop = false
    private var onWakeDetectedCallback: ((WakeDetectionEvent) -> Unit)? = null

    // 1-second circular pre-roll buffer at 16 kHz (16,000 PCM16 samples)
    private val preRollRing = ShortArray(PRE_ROLL_SAMPLES)
    private var preRollWritePos = 0
    private var preRollCount = 0

    // Command capture state (either woken or manual)
    private val capturePcmStream = ByteArrayOutputStream()
    private var captureMaxDurationMs = 14_000L
    private var captureAutoSilenceStop = true
    private var captureStartMs = 0L
    private var captureHasSpeech = false
    private var captureLastSpeechMs = 0L
    private var onAutoSilenceCallback: (() -> Unit)? = null

    @Volatile
    private var isTtsSpeakingNow = false

    @Volatile
    private var postTtsIgnoreUntilMs = 0L

    private val _state = MutableStateFlow(MicPipelineState.STOPPED)
    val state: StateFlow<MicPipelineState> = _state.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _wakeScore = MutableStateFlow(0f)
    val wakeScore: StateFlow<Float> = _wakeScore.asStateFlow()

    private val _watchdogRestarts = MutableStateFlow(0)
    val watchdogRestarts: StateFlow<Int> = _watchdogRestarts.asStateFlow()

    private val _watchdogLogs = MutableStateFlow<List<String>>(emptyList())
    val watchdogLogs: StateFlow<List<String>> = _watchdogLogs.asStateFlow()

    val spotterEngineName: String
        get() = keywordSpotter.engineName

    fun startWakePipeline(onWake: (WakeDetectionEvent) -> Unit) {
        onWakeDetectedCallback = onWake
        shouldRunWakeLoop = true
        ensurePipelineLoopRunning()
    }

    fun stopWakePipeline() {
        shouldRunWakeLoop = false
        if (!_isCapturing.value) {
            pipelineJob?.cancel()
            pipelineJob = null
            _state.value = MicPipelineState.STOPPED
            _isListening.value = false
            _rmsLevel.value = 0f
            _wakeScore.value = 0f
        }
    }

    /**
     * Called by TTS manager whenever speech starts/stops so wake-word evaluation ignores
     * TTS output plus a 300 ms acoustic tail window.
     */
    fun onTtsSpeakingChanged(speaking: Boolean) {
        isTtsSpeakingNow = speaking
        if (speaking) {
            postTtsIgnoreUntilMs = System.currentTimeMillis() + POST_TTS_IGNORE_WINDOW_MS
            keywordSpotter.reset()
            if (shouldRunWakeLoop && !_isCapturing.value) {
                _state.value = MicPipelineState.PAUSED_FOR_TTS
                _isListening.value = false
            }
        } else {
            postTtsIgnoreUntilMs = System.currentTimeMillis() + POST_TTS_IGNORE_WINDOW_MS
        }
    }

    /**
     * Begins capturing a spoken command (manual mic tap, notification action, or wake follow-up).
     * Automatically prepends the 1-second circular pre-roll buffer when audio is already flowing.
     */
    fun beginCommandCapture(
        maxDurationMs: Long = 16_000L,
        autoSilenceStop: Boolean = true,
        includePreRoll: Boolean = true,
        onSilenceDetected: (() -> Unit)? = null
    ): Boolean {
        if (!hasMicPermission()) {
            appendLog("MIC permission missing; cannot start capture.")
            return false
        }
        synchronized(captureLock) {
            if (_isCapturing.value) return false
            capturePcmStream.reset()
            if (includePreRoll && preRollCount > 0) {
                val preRollSamples = drainPreRollSamplesLocked()
                writeShortsLittleEndian(preRollSamples, preRollSamples.size, capturePcmStream)
            }
            val now = System.currentTimeMillis()
            captureMaxDurationMs = maxDurationMs
            captureAutoSilenceStop = autoSilenceStop
            captureStartMs = now
            captureHasSpeech = false
            captureLastSpeechMs = now
            onAutoSilenceCallback = onSilenceDetected
            _isCapturing.value = true
            _state.value = MicPipelineState.CAPTURING
            _isListening.value = false
        }
        ensurePipelineLoopRunning()
        return true
    }

    fun finishCaptureAndBuildWav(): ByteArray? {
        val pcmBytes: ByteArray
        synchronized(captureLock) {
            _isCapturing.value = false
            onAutoSilenceCallback = null
            _amplitude.value = 0f
            pcmBytes = capturePcmStream.toByteArray().also { capturePcmStream.reset() }
        }
        if (!shouldRunWakeLoop) {
            pipelineJob?.cancel()
            pipelineJob = null
            _state.value = MicPipelineState.STOPPED
            _isListening.value = false
        } else {
            _state.value = MicPipelineState.LISTENING
            _isListening.value = true
        }
        if (pcmBytes.size < 3_200) return null
        return wrapPcmAsWav(pcmBytes, sampleRate = SAMPLE_RATE, channels = 1, bitsPerSample = 16)
    }

    fun cancelCapture() {
        synchronized(captureLock) {
            _isCapturing.value = false
            onAutoSilenceCallback = null
            _amplitude.value = 0f
            capturePcmStream.reset()
        }
        if (!shouldRunWakeLoop) {
            pipelineJob?.cancel()
            pipelineJob = null
            _state.value = MicPipelineState.STOPPED
            _isListening.value = false
        } else {
            _state.value = MicPipelineState.LISTENING
            _isListening.value = true
        }
    }

    /**
     * Triggers the exact same wake event path for WakeWordTestScreen diagnostics.
     */
    fun simulateManualWakeTrigger() {
        appendLog("Manual test wake trigger invoked.")
        onWakeDetectedCallback?.invoke(
            WakeDetectionEvent(
                phraseMatched = "hey baby (test)",
                trailingCommand = null
            )
        )
    }

    suspend fun captureUtteranceForCallScreening(
        maxDurationMs: Long = 30_000L,
        silenceWindowMs: Long = 3_000L
    ): java.io.File? {
        if (!hasMicPermission()) return null
        val doneSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = beginCommandCapture(
            maxDurationMs = maxDurationMs,
            autoSilenceStop = true,
            includePreRoll = false,
            onSilenceDetected = { doneSignal.complete(Unit) }
        )
        if (!started) return null

        kotlinx.coroutines.withTimeoutOrNull(maxDurationMs + 500L) {
            var waitedNoSpeech = 0L
            while (!doneSignal.isCompleted) {
                delay(250L)
                if (!captureHasSpeech) {
                    waitedNoSpeech += 250L
                    if (waitedNoSpeech >= silenceWindowMs) {
                        doneSignal.complete(Unit)
                    }
                }
            }
        }

        val wavBytes = finishCaptureAndBuildWav() ?: return null
        val file = java.io.File(context.cacheDir, "call_screen_${System.currentTimeMillis()}.wav")
        return runCatching {
            file.writeBytes(wavBytes)
            file
        }.getOrNull()
    }

    private fun ensurePipelineLoopRunning() {
        if (pipelineJob?.isActive == true) return
        pipelineJob = scope.launch {
            runPipelineLoop()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runPipelineLoop() {
        var retryAttempt = 0
        while (scope.isActive && (shouldRunWakeLoop || _isCapturing.value)) {
            if (!hasMicPermission()) {
                _state.value = MicPipelineState.MIC_BUSY
                _isListening.value = false
                appendLog("RECORD_AUDIO permission missing; waiting...")
                delay(2_000L)
                continue
            }

            val minBuf = runCatching {
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            }.getOrDefault(-1)

            if (minBuf <= 0) {
                retryAttempt++
                _state.value = MicPipelineState.MIC_BUSY
                _isListening.value = false
                val waitMs = nextRetryDelayMs(retryAttempt)
                _watchdogRestarts.value = _watchdogRestarts.value + 1
                appendLog("AudioRecord minBuffer invalid; retry #$retryAttempt in ${waitMs / 1000}s")
                delay(waitMs)
                continue
            }

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf.coerceAtLeast(FRAME_SAMPLES * 4) * 2
                )
            }.getOrNull()

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                retryAttempt++
                _state.value = MicPipelineState.MIC_BUSY
                _isListening.value = false
                val waitMs = nextRetryDelayMs(retryAttempt)
                _watchdogRestarts.value = _watchdogRestarts.value + 1
                appendLog("Mic busy / init failed; backoff retry #$retryAttempt in ${waitMs / 1000}s")
                delay(waitMs)
                continue
            }

            val startOk = runCatching {
                recorder.startRecording()
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

            if (!startOk) {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
                retryAttempt++
                _state.value = MicPipelineState.MIC_BUSY
                _isListening.value = false
                val waitMs = nextRetryDelayMs(retryAttempt)
                _watchdogRestarts.value = _watchdogRestarts.value + 1
                appendLog("AudioRecord startRecording blocked (MIC_BUSY); retry #$retryAttempt in ${waitMs / 1000}s")
                delay(waitMs)
                continue
            }

            retryAttempt = 0
            appendLog("16kHz AudioRecord active (${keywordSpotter.engineName})")

            val frame = ShortArray(FRAME_SAMPLES)
            var consecutiveReadErrors = 0
            try {
                while (scope.isActive && (shouldRunWakeLoop || _isCapturing.value)) {
                    val read = recorder.read(frame, 0, frame.size)
                    if (read <= 0) {
                        consecutiveReadErrors++
                        if (consecutiveReadErrors >= 8) {
                            _watchdogRestarts.value = _watchdogRestarts.value + 1
                            appendLog("AudioRecord read stalled ($read); restarting mic watchdog")
                            break
                        }
                        delay(30L)
                        continue
                    }
                    consecutiveReadErrors = 0

                    val now = System.currentTimeMillis()
                    val rms = computeRms(frame, read)
                    _rmsLevel.value = rms
                    _amplitude.value = (rms / 6_500f).coerceIn(0f, 1f)

                    if (_isCapturing.value) {
                        _state.value = MicPipelineState.CAPTURING
                        _isListening.value = false
                        var silenceCb: (() -> Unit)? = null
                        synchronized(captureLock) {
                            if (_isCapturing.value) {
                                writeShortsLittleEndian(frame, read, capturePcmStream)
                                if (rms > 620f) {
                                    captureHasSpeech = true
                                    captureLastSpeechMs = now
                                }
                                val timedOut = (now - captureStartMs) > captureMaxDurationMs
                                val silentEnded = captureAutoSilenceStop &&
                                    captureHasSpeech &&
                                    (now - captureLastSpeechMs > 1_550L)
                                if (timedOut || silentEnded) {
                                    silenceCb = onAutoSilenceCallback
                                    onAutoSilenceCallback = null
                                }
                            }
                        }
                        silenceCb?.invoke()
                        continue
                    }

                    // Always keep pre-roll buffer warm while listening
                    synchronized(captureLock) {
                        appendPreRollLocked(frame, read)
                    }

                    if (shouldIgnoreForTts(isTtsSpeakingNow, postTtsIgnoreUntilMs, now)) {
                        _state.value = MicPipelineState.PAUSED_FOR_TTS
                        _isListening.value = false
                        _wakeScore.value = 0f
                        continue
                    }

                    _state.value = MicPipelineState.LISTENING
                    _isListening.value = true

                    val settings = keyStore.settings.value
                    val eval = keywordSpotter.evaluateFrame(
                        pcm16 = frame,
                        length = read,
                        sampleRate = SAMPLE_RATE,
                        sensitivity = settings.wakeWordSensitivity
                    )
                    _wakeScore.value = eval.confidence

                    if (eval.detected) {
                        appendLog("Wake detected (${(eval.confidence * 100).toInt()}% conf, RMS=${eval.rmsLevel.toInt()})")
                        onWakeDetectedCallback?.invoke(
                            WakeDetectionEvent(
                                phraseMatched = "hey baby",
                                trailingCommand = null,
                                timestamp = now
                            )
                        )
                    } else if (settings.wakeWordBatterySaver && rms < 320f) {
                        delay(80L)
                    }
                }
            } catch (e: Exception) {
                appendLog("Mic loop exception: ${e.message ?: e::class.java.simpleName}")
                retryAttempt++
                _state.value = MicPipelineState.MIC_BUSY
                _isListening.value = false
                delay(nextRetryDelayMs(retryAttempt))
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
            }
        }

        _state.value = MicPipelineState.STOPPED
        _isListening.value = false
        _rmsLevel.value = 0f
        _wakeScore.value = 0f
    }

    private fun appendPreRollLocked(samples: ShortArray, count: Int) {
        val len = count.coerceAtMost(samples.size)
        for (i in 0 until len) {
            preRollRing[preRollWritePos] = samples[i]
            preRollWritePos = (preRollWritePos + 1) % preRollRing.size
            if (preRollCount < preRollRing.size) preRollCount++
        }
    }

    private fun drainPreRollSamplesLocked(): ShortArray {
        val count = preRollCount.coerceIn(0, preRollRing.size)
        if (count == 0) return ShortArray(0)
        val out = ShortArray(count)
        val startIdx = (preRollWritePos - count + preRollRing.size) % preRollRing.size
        for (i in 0 until count) {
            out[i] = preRollRing[(startIdx + i) % preRollRing.size]
        }
        return out
    }

    private fun writeShortsLittleEndian(samples: ShortArray, count: Int, out: ByteArrayOutputStream) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            bb.putShort(samples[i])
        }
        out.write(bb.array())
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun appendLog(entry: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $entry"
        val updated = (_watchdogLogs.value + line).takeLast(40)
        _watchdogLogs.value = updated
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms @ 16 kHz
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE // 1.0 second circular buffer
        const val POST_TTS_IGNORE_WINDOW_MS = 300L
        const val MAX_RETRY_DELAY_MS = 30_000L

        /**
         * Exponential backoff schedule for AudioRecord failures:
         * attempt 1 -> 1s, 2 -> 2s, 3 -> 4s, 4 -> 8s, 5 -> 16s, >=6 -> 30s.
         */
        fun nextRetryDelayMs(attempt: Int): Long {
            if (attempt <= 1) return 1_000L
            val shift = (attempt - 1).coerceAtMost(5)
            return (1_000L shl shift).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        fun shouldIgnoreForTts(
            isSpeaking: Boolean,
            postTtsIgnoreUntilMs: Long,
            nowMs: Long
        ): Boolean = isSpeaking || nowMs < postTtsIgnoreUntilMs

        fun computeRms(samples: ShortArray, length: Int): Float {
            if (length <= 0) return 0f
            var sumSq = 0.0
            for (i in 0 until length) {
                val s = samples[i].toDouble()
                sumSq += s * s
            }
            return sqrt(sumSq / length).toFloat()
        }

        fun wrapPcmAsWav(
            pcmData: ByteArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int
        ): ByteArray {
            val out = ByteArrayOutputStream(44 + pcmData.size)
            val dos = DataOutputStream(out)
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8

            dos.writeBytes("RIFF")
            dos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(36 + pcmData.size).array())
            dos.writeBytes("WAVE")
            dos.writeBytes("fmt ")
            dos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
            dos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array())
            dos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(channels.toShort()).array())
            dos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).array())
            dos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate).array())
            dos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(blockAlign.toShort()).array())
            dos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(bitsPerSample.toShort()).array())
            dos.writeBytes("data")
            dos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pcmData.size).array())
            dos.write(pcmData)
            dos.flush()
            return out.toByteArray()
        }
    }
}
