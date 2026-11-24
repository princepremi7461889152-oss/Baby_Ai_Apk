package com.example.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.core.security.SecureKeyStore
import com.example.core.security.TtsTierMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ResolvedTtsTier {
    TIER_A_ANDROID,
    TIER_B_GEMINI
}

/**
 * Two-Tier TTS Manager:
 * - Tier A (Android TextToSpeech): < 8 words, quick acks, offline, ANDROID_ONLY, or instant fallback.
 * - Tier B (Gemini 3.8 TTS): >= 8 words in AUTO or forced GEMINI_STANDARD / GEMINI_PREMIUM,
 *   with strict 1.5s timeout fallback per sentence to Tier A so Baby never hangs silently.
 * - Sentence-splitting queue starts speaking immediately on the first complete sentence.
 * - Notifies [MicPipelineManager] so 300ms post-TTS ignore window blocks self-triggering.
 */
@Singleton
class BabyTtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore?,
    private val geminiTtsClient: GeminiTtsClient?,
    private val micPipeline: MicPipelineManager?
) : TextToSpeech.OnInitListener {

    // Legacy 1-arg constructor preserved for JVM unit tests
    constructor(context: Context) : this(context, null, null, null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var playbackJob: Job? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _activeTier = MutableStateFlow(ResolvedTtsTier.TIER_A_ANDROID)
    val activeTier: StateFlow<ResolvedTtsTier> = _activeTier.asStateFlow()

    init {
        runCatching { tts = TextToSpeech(context, this) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            val speed = keyStore?.settings?.value?.ttsSpeed ?: 1.04f
            tts?.setSpeechRate(speed.coerceIn(0.6f, 1.8f))
            tts?.setPitch(1.03f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    updateSpeakingState(true)
                }

                override fun onDone(utteranceId: String?) {
                    updateSpeakingState(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    updateSpeakingState(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    updateSpeakingState(false)
                }
            })
        }
    }

    /**
     * Speaks full text, splitting into sentences so the first sentence starts speaking immediately.
     * Supports optional [styleDirective] passed only to Gemini `speechMetadata.style` (never spoken).
     */
    fun speak(
        text: String,
        languageHint: String = "hinglish",
        styleDirective: String? = null
    ) {
        val clean = cleanSpeakableText(text)
        if (clean.isEmpty()) return

        stop(cancelJobOnly = false)
        val sentences = splitSentences(clean)
        if (sentences.isEmpty()) return

        val settings = keyStore?.settings?.value
        val online = isOnline()
        val hasGeminiKey = !settings?.geminiApiKey.isNullOrBlank()
        val tierMode = settings?.ttsTierMode ?: TtsTierMode.AUTO
        val useGeminiAny = geminiTtsClient != null &&
            sentences.any { s ->
                resolveTierForSentence(
                    wordCount = countWords(s),
                    mode = tierMode,
                    hasGeminiKey = hasGeminiKey,
                    isOnline = online
                ) == ResolvedTtsTier.TIER_B_GEMINI
            }

        // Fast synchronous Tier A path when short / offline / no Gemini client
        if (!useGeminiAny) {
            _activeTier.value = ResolvedTtsTier.TIER_A_ANDROID
            if (sentences.size == 1) {
                speakWithAndroidTts(sentences.first(), languageHint, queueMode = TextToSpeech.QUEUE_FLUSH)
            } else {
                sentences.forEachIndexed { idx, s ->
                    speakWithAndroidTts(
                        s,
                        languageHint,
                        queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    )
                }
            }
            return
        }

        playbackJob = scope.launch {
            try {
                updateSpeakingState(true)
                for (sentence in sentences) {
                    val tier = resolveTierForSentence(
                        wordCount = countWords(sentence),
                        mode = tierMode,
                        hasGeminiKey = hasGeminiKey,
                        isOnline = online
                    )
                    if (tier == ResolvedTtsTier.TIER_B_GEMINI && settings != null && geminiTtsClient != null) {
                        val modelId = if (tierMode == TtsTierMode.GEMINI_PREMIUM) {
                            settings.modelConfig.ttsPremiumModel
                        } else {
                            settings.modelConfig.ttsModel
                        }
                        val style = if (settings.emotionalVoiceEnabled) styleDirective else null
                        val fetched = withTimeoutOrNull(GEMINI_TTS_TIMEOUT_MS) {
                            geminiTtsClient.synthesizeAudio(
                                text = sentence,
                                modelId = modelId,
                                voiceName = settings.ttsVoiceName,
                                styleDirective = style,
                                apiKey = settings.geminiApiKey
                            ).getOrNull()
                        }
                        if (fetched != null) {
                            _activeTier.value = ResolvedTtsTier.TIER_B_GEMINI
                            val played = geminiTtsClient.playAudio(
                                payload = fetched,
                                speed = settings.ttsSpeed,
                                volume = settings.ttsVolume
                            )
                            if (played) continue
                        }
                    }

                    // Tier A fallback (short sentence OR >1.5s timeout OR Gemini error)
                    _activeTier.value = ResolvedTtsTier.TIER_A_ANDROID
                    speakWithAndroidTts(sentence, languageHint, queueMode = TextToSpeech.QUEUE_ADD)
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                updateSpeakingState(false)
            }
        }
    }

    /**
     * Speaks a single streaming sentence immediately while appending if already speaking.
     */
    fun speakStreamingSentence(
        sentence: String,
        languageHint: String = "hinglish",
        flushFirst: Boolean = false
    ) {
        val clean = cleanSpeakableText(sentence)
        if (clean.isEmpty()) return
        _activeTier.value = ResolvedTtsTier.TIER_A_ANDROID
        speakWithAndroidTts(
            clean,
            languageHint,
            queueMode = if (flushFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        )
    }

    fun previewVoice(languageHint: String = "hinglish") {
        val sample = "Namaste! Main Baby hoon, aapki voice assistant. Batayein main kya madad karoon?"
        speak(sample, languageHint = languageHint, styleDirective = "warm friendly cheerful tone")
    }

    fun stop(cancelJobOnly: Boolean = false) {
        playbackJob?.cancel()
        playbackJob = null
        geminiTtsClient?.stopPlayback()
        if (!cancelJobOnly) {
            runCatching { tts?.stop() }
        }
        updateSpeakingState(false)
    }

    private fun speakWithAndroidTts(
        clean: String,
        languageHint: String,
        queueMode: Int
    ) {
        if (!isReady || clean.isEmpty()) return
        val engine = tts ?: return
        val settings = keyStore?.settings?.value
        if (settings != null) {
            engine.setSpeechRate(settings.ttsSpeed.coerceIn(0.6f, 1.8f))
        }

        val locale = when {
            languageHint.equals("english", ignoreCase = true) ||
                languageHint.startsWith("en", ignoreCase = true) ->
                Locale.Builder().setLanguage("en").setRegion("IN").build()
            else ->
                Locale.Builder().setLanguage("hi").setRegion("IN").build()
        }

        val availability = engine.isLanguageAvailable(locale)
        engine.language = if (availability >= TextToSpeech.LANG_AVAILABLE) locale else Locale.US

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (settings?.ttsVolume ?: 1.0f).coerceIn(0.1f, 1.0f))
        }
        val res = engine.speak(clean, queueMode, params, UUID.randomUUID().toString())
        if (res == TextToSpeech.SUCCESS) {
            updateSpeakingState(true)
        }
    }

    private fun updateSpeakingState(speaking: Boolean) {
        _isSpeaking.value = speaking
        micPipeline?.onTtsSpeakingChanged(speaking)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val SHORT_UTTERANCE_WORD_THRESHOLD = 8
        const val GEMINI_TTS_TIMEOUT_MS = 1_500L

        /**
         * Determines whether a sentence routes to fast local Tier A (Android TTS)
         * or expressive cloud Tier B (Gemini TTS).
         */
        fun resolveTierForSentence(
            wordCount: Int,
            mode: TtsTierMode,
            hasGeminiKey: Boolean,
            isOnline: Boolean
        ): ResolvedTtsTier {
            if (!hasGeminiKey || !isOnline || mode == TtsTierMode.ANDROID_ONLY) {
                return ResolvedTtsTier.TIER_A_ANDROID
            }
            return when (mode) {
                TtsTierMode.GEMINI_STANDARD,
                TtsTierMode.GEMINI_PREMIUM -> ResolvedTtsTier.TIER_B_GEMINI
                TtsTierMode.AUTO -> if (wordCount < SHORT_UTTERANCE_WORD_THRESHOLD) {
                    ResolvedTtsTier.TIER_A_ANDROID
                } else {
                    ResolvedTtsTier.TIER_B_GEMINI
                }
                TtsTierMode.ANDROID_ONLY -> ResolvedTtsTier.TIER_A_ANDROID
            }
        }

        /**
         * Splits text on sentence boundaries (., !, ?, ।, newlines) for immediate first-sentence speech.
         */
        fun splitSentences(text: String): List<String> {
            val normalized = text.trim()
            if (normalized.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            val current = StringBuilder()
            for (ch in normalized) {
                current.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '।' || ch == '\n') {
                    val piece = current.toString().trim()
                    if (piece.isNotEmpty() && piece.any { it.isLetterOrDigit() }) {
                        out += piece
                    }
                    current.clear()
                }
            }
            val tail = current.toString().trim()
            if (tail.isNotEmpty() && tail.any { it.isLetterOrDigit() }) {
                out += tail
            }
            return out
        }

        fun countWords(text: String): Int =
            text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

        fun cleanSpeakableText(text: String): String = text
            .replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""[*_`#]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        /**
         * Orchestrates Gemini Tier B fetch with strict timeout and automatic Tier A fallback.
         * Exposed cleanly for deterministic JVM testing without live Android AudioTrack/TTS.
         */
        suspend fun routeSentenceWithFallback(
            sentence: String,
            mode: TtsTierMode,
            hasGeminiKey: Boolean,
            isOnline: Boolean,
            timeoutMs: Long = GEMINI_TTS_TIMEOUT_MS,
            geminiFetcher: suspend (String) -> TtsAudioPayload?,
            onPlayGemini: suspend (TtsAudioPayload) -> Boolean,
            onPlayAndroid: suspend (String) -> Unit
        ): ResolvedTtsTier {
            val tier = resolveTierForSentence(countWords(sentence), mode, hasGeminiKey, isOnline)
            if (tier == ResolvedTtsTier.TIER_B_GEMINI) {
                val audio = withTimeoutOrNull(timeoutMs) {
                    runCatching { geminiFetcher(sentence) }.getOrNull()
                }
                if (audio != null && onPlayGemini(audio)) {
                    return ResolvedTtsTier.TIER_B_GEMINI
                }
            }
            onPlayAndroid(sentence)
            return ResolvedTtsTier.TIER_A_ANDROID
        }
    }
}
