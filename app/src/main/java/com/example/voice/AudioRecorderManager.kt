package com.example.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Unified voice capture facade backed by [MicPipelineManager] so only ONE 16 kHz AudioRecord
 * instance holds the microphone, seamlessly prepending the 1s pre-roll buffer when active.
 */
@Singleton
class AudioRecorderManager @Inject constructor(
    private val micPipeline: MicPipelineManager?
) {
    // Legacy zero-arg constructor for unit tests
    constructor() : this(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val pcmBuffer = ByteArrayOutputStream()

    private val fallbackIsRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean>
        get() = micPipeline?.isCapturing ?: fallbackIsRecording.asStateFlow()

    private val fallbackAmplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float>
        get() = micPipeline?.amplitude ?: fallbackAmplitude.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording(
        maxDurationMs: Long = 18_000L,
        autoSilenceStop: Boolean = true,
        onSilenceDetected: (() -> Unit)? = null
    ): Boolean {
        if (micPipeline != null) {
            return micPipeline.beginCommandCapture(
                maxDurationMs = maxDurationMs,
                autoSilenceStop = autoSilenceStop,
                includePreRoll = true,
                onSilenceDetected = onSilenceDetected
            )
        }

        synchronized(stateLock) {
            if (fallbackIsRecording.value) return false
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (_: Exception) {
                return false
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }

            synchronized(pcmBuffer) { pcmBuffer.reset() }
            audioRecord = recorder
            runCatching { recorder.startRecording() }.onFailure {
                runCatching { recorder.release() }
                audioRecord = null
                return false
            }
            fallbackIsRecording.value = true

            recordingJob = scope.launch {
                val buf = ShortArray(1024)
                val startTime = System.currentTimeMillis()
                var hasHeardSpeech = false
                var lastSpeechMs = startTime
                var consecutiveErrors = 0

                while (isActive && fallbackIsRecording.value) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        consecutiveErrors = 0
                        var sumSq = 0.0
                        val byteBuf = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) {
                            val s = buf[i]
                            byteBuf.putShort(s)
                            sumSq += s.toDouble() * s.toDouble()
                        }
                        synchronized(pcmBuffer) { pcmBuffer.write(byteBuf.array()) }
                        val rms = sqrt(sumSq / read).toFloat()
                        fallbackAmplitude.value = (rms / 6500f).coerceIn(0f, 1f)

                        val now = System.currentTimeMillis()
                        if (rms > 650f) {
                            hasHeardSpeech = true
                            lastSpeechMs = now
                        }
                        if (now - startTime > maxDurationMs) {
                            onSilenceDetected?.invoke()
                            break
                        }
                        if (autoSilenceStop && hasHeardSpeech && (now - lastSpeechMs > 1550L)) {
                            onSilenceDetected?.invoke()
                            break
                        }
                    } else {
                        consecutiveErrors++
                        if (consecutiveErrors >= 10) {
                            onSilenceDetected?.invoke()
                            break
                        }
                        try {
                            Thread.sleep(25L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
            return true
        }
    }

    fun stopAndBuildWav(): ByteArray? {
        if (micPipeline != null) {
            return micPipeline.finishCaptureAndBuildWav()
        }
        return synchronized(stateLock) {
            fallbackIsRecording.value = false
            fallbackAmplitude.value = 0f
            recordingJob?.cancel()
            recordingJob = null
            runCatching {
                audioRecord?.stop()
                audioRecord?.release()
            }
            audioRecord = null

            val pcmBytes = synchronized(pcmBuffer) {
                pcmBuffer.toByteArray().also { pcmBuffer.reset() }
            }
            if (pcmBytes.size < 3200) return null
            MicPipelineManager.wrapPcmAsWav(pcmBytes, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
        }
    }

    fun cancel() {
        if (micPipeline != null) {
            micPipeline.cancelCapture()
            return
        }
        synchronized(stateLock) {
            fallbackIsRecording.value = false
            fallbackAmplitude.value = 0f
            recordingJob?.cancel()
            recordingJob = null
            runCatching {
                audioRecord?.stop()
                audioRecord?.release()
            }
            audioRecord = null
            synchronized(pcmBuffer) { pcmBuffer.reset() }
        }
    }
}
