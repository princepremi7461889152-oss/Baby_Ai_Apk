package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Base64
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiGenerationConfig
import com.example.data.remote.GeminiPart
import com.example.data.remote.GeminiPrebuiltVoiceConfig
import com.example.data.remote.GeminiSpeechConfig
import com.example.data.remote.GeminiSpeechMetadata
import com.example.data.remote.GeminiVoiceConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class TtsAudioPayload(
    val audioBytes: ByteArray,
    val mimeType: String
)

/**
 * Gemini 3.8 TTS client (`gemini-3.8-flash-lite-tts` / `gemini-3.8-flash-tts`) with local file cache.
 * Passes style directives in `speechMetadata.style` (never read aloud as text).
 */
@Singleton
class GeminiTtsClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geminiApi: GeminiApiService
) {
    private val cacheDir: File by lazy {
        File(context.cacheDir, "baby_tts_cache").apply { mkdirs() }
    }

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    suspend fun synthesizeAudio(
        text: String,
        modelId: String,
        voiceName: String,
        styleDirective: String?,
        apiKey: String
    ): Result<TtsAudioPayload> = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        val key = apiKey.trim()
        if (cleanText.isEmpty() || key.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Empty TTS text or Gemini API key"))
        }

        val cacheKey = hashKey("$modelId|$voiceName|${styleDirective.orEmpty()}|$cleanText")
        val cachedPcm = File(cacheDir, "$cacheKey.pcm")
        if (cachedPcm.exists() && cachedPcm.length() > 100) {
            return@withContext Result.success(
                TtsAudioPayload(cachedPcm.readBytes(), "audio/L16;codec=pcm;rate=24000")
            )
        }

        val part = GeminiPart(
            text = cleanText,
            speechMetadata = styleDirective?.takeIf { it.isNotBlank() }?.let {
                GeminiSpeechMetadata(style = it)
            }
        )
        val req = GeminiGenerateRequest(
            contents = listOf(GeminiContent(role = "user", parts = listOf(part))),
            generationConfig = GeminiGenerationConfig(
                maxOutputTokens = 2048,
                responseModalities = listOf("AUDIO"),
                speechConfig = GeminiSpeechConfig(
                    voiceConfig = GeminiVoiceConfig(
                        prebuiltVoiceConfig = GeminiPrebuiltVoiceConfig(
                            voiceName = voiceName.ifBlank { "Kore" }
                        )
                    )
                )
            )
        )

        val response = runCatching {
            geminiApi.generateContent(
                model = modelId.removePrefix("models/"),
                apiKey = key,
                request = req
            )
        }.getOrElse { return@withContext Result.failure(it) }

        if (!response.isSuccessful) {
            return@withContext Result.failure(
                IllegalStateException("Gemini TTS HTTP ${response.code()}")
            )
        }

        val parts = response.body()?.candidates?.firstOrNull()?.content?.parts.orEmpty()
        val inline = parts.firstNotNullOfOrNull { it.inlineData }
            ?: return@withContext Result.failure(IllegalStateException("No audio inlineData returned"))

        val decoded = runCatching { Base64.decode(inline.data, Base64.DEFAULT) }
            .getOrElse { return@withContext Result.failure(it) }

        if (decoded.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Empty decoded TTS audio"))
        }

        // Cache short/common phrases locally (up to 400 KB) to avoid repeated network fetches
        if (cleanText.length <= 160 && inline.mimeType.contains("pcm", ignoreCase = true)) {
            runCatching {
                trimCacheIfNeeded()
                cachedPcm.writeBytes(decoded)
            }
        }

        Result.success(TtsAudioPayload(decoded, inline.mimeType))
    }

    suspend fun playAudio(
        payload: TtsAudioPayload,
        speed: Float = 1.0f,
        volume: Float = 1.0f
    ): Boolean = withContext(Dispatchers.IO) {
        stopPlayback()
        val mime = payload.mimeType.lowercase()
        if (mime.contains("pcm") || mime.contains("l16")) {
            playPcmTrack(payload.audioBytes, parseSampleRate(mime), speed, volume)
        } else {
            playCompressedWithMediaPlayer(payload.audioBytes, mime, volume)
        }
    }

    fun stopPlayback() {
        runCatching {
            activeAudioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.stop()
                track.release()
            }
        }
        activeAudioTrack = null

        runCatching {
            activeMediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        activeMediaPlayer = null
    }

    private fun playPcmTrack(
        pcmBytes: ByteArray,
        sampleRate: Int,
        speed: Float,
        volume: Float
    ): Boolean {
        val adjustedRate = (sampleRate * speed.coerceIn(0.6f, 1.8f)).toInt().coerceIn(8_000, 48_000)
        val minBuf = AudioTrack.getMinBufferSize(
            adjustedRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(adjustedRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return false

        activeAudioTrack = track
        return try {
            track.setVolume(volume.coerceIn(0.1f, 1.0f))
            track.play()
            var offset = 0
            while (offset < pcmBytes.size && activeAudioTrack === track) {
                val chunk = minOf(4096, pcmBytes.size - offset)
                val written = track.write(pcmBytes, offset, chunk)
                if (written <= 0) break
                offset += written
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching {
                if (activeAudioTrack === track) activeAudioTrack = null
                track.stop()
                track.release()
            }
        }
    }

    private suspend fun playCompressedWithMediaPlayer(
        bytes: ByteArray,
        mime: String,
        volume: Float
    ): Boolean {
        val ext = when {
            mime.contains("wav") -> "wav"
            mime.contains("ogg") -> "ogg"
            else -> "mp3"
        }
        val tempFile = File.createTempFile("baby_tts_", ".$ext", context.cacheDir)
        tempFile.writeBytes(bytes)
        return try {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(tempFile.absolutePath)
                    setVolume(volume.coerceIn(0.1f, 1.0f), volume.coerceIn(0.1f, 1.0f))
                    setOnCompletionListener {
                        runCatching { it.release() }
                        if (activeMediaPlayer === it) activeMediaPlayer = null
                        if (cont.isActive) cont.resume(true)
                    }
                    setOnErrorListener { player, _, _ ->
                        runCatching { player.release() }
                        if (activeMediaPlayer === player) activeMediaPlayer = null
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    prepare()
                    activeMediaPlayer = this
                    start()
                }
                cont.invokeOnCancellation {
                    runCatching {
                        mp.stop()
                        mp.release()
                    }
                    if (activeMediaPlayer === mp) activeMediaPlayer = null
                }
            }
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun parseSampleRate(mime: String): Int {
        val match = Regex("""rate=(\d+)""").find(mime)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8_000, 48_000) ?: 24_000
    }

    private fun hashKey(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun trimCacheIfNeeded() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > 35) {
            files.take(files.size - 25).forEach { it.delete() }
        }
    }
}
