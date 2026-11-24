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
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class AudioRecorderManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val pcmBuffer = ByteArrayOutputStream()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording(
        maxDurationMs: Long = 18_000L,
        autoSilenceStop: Boolean = true,
        onSilenceDetected: (() -> Unit)? = null
    ): Boolean {
        synchronized(stateLock) {
            if (_isRecording.value) return false
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
            _isRecording.value = true

            recordingJob = scope.launch {
                val buf = ShortArray(1024)
                val startTime = System.currentTimeMillis()
                var hasHeardSpeech = false
                var lastSpeechMs = startTime
                var consecutiveErrors = 0

                while (isActive && _isRecording.value) {
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
                        synchronized(pcmBuffer) {
                            pcmBuffer.write(byteBuf.array())
                        }
                        val rms = sqrt(sumSq / read).toFloat()
                        val normalized = (rms / 6500f).coerceIn(0f, 1f)
                        _amplitude.value = normalized

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

    fun stopAndBuildWav(): ByteArray? = synchronized(stateLock) {
        _isRecording.value = false
        _amplitude.value = 0f
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
        return wrapPcmAsWav(pcmBytes, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
    }

    fun cancel() = synchronized(stateLock) {
        _isRecording.value = false
        _amplitude.value = 0f
        recordingJob?.cancel()
        recordingJob = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        synchronized(pcmBuffer) { pcmBuffer.reset() }
    }

    private fun wrapPcmAsWav(
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
        dos.write(intToLittleEndian(36 + pcmData.size))
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt ")
        dos.write(intToLittleEndian(16))
        dos.write(shortToLittleEndian(1))
        dos.write(shortToLittleEndian(channels.toShort()))
        dos.write(intToLittleEndian(sampleRate))
        dos.write(intToLittleEndian(byteRate))
        dos.write(shortToLittleEndian(blockAlign.toShort()))
        dos.write(shortToLittleEndian(bitsPerSample.toShort()))
        dos.writeBytes("data")
        dos.write(intToLittleEndian(pcmData.size))
        dos.write(pcmData)
        dos.flush()
        return out.toByteArray()
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortToLittleEndian(value: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
}
