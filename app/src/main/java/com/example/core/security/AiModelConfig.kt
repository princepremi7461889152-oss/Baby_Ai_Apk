package com.example.core.security

enum class GroqModelRole {
    CHAT,
    FAST,
    STT
}

enum class AiRole {
    MIND,
    VISION_SEARCH,
    STT,
    TTS,
    FALLBACK_CHAT
}

/**
 * Single editable source of truth for all AI model IDs and provider base URLs used across Baby.
 * Never hardcode model names or URLs inside callers.
 */
data class AiModelConfig(
    val mindModel: String = DEFAULT_MIND_MODEL,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val chatModel: String = DEFAULT_CHAT_MODEL,
    val fastModel: String = DEFAULT_FAST_MODEL,
    val sttModel: String = DEFAULT_STT_MODEL,
    val ttsModel: String = DEFAULT_TTS_MODEL,
    val ttsPremiumModel: String = DEFAULT_TTS_PREMIUM_MODEL,
    val inceptionBaseUrl: String = DEFAULT_INCEPTION_BASE_URL,
    val groqBaseUrl: String = DEFAULT_GROQ_BASE_URL,
    val geminiBaseUrl: String = DEFAULT_GEMINI_BASE_URL
) {
    fun normalized(): AiModelConfig = AiModelConfig(
        mindModel = mindModel.trim().ifBlank { DEFAULT_MIND_MODEL },
        geminiModel = geminiModel.trim().ifBlank { DEFAULT_GEMINI_MODEL },
        chatModel = chatModel.trim().ifBlank { DEFAULT_CHAT_MODEL },
        fastModel = fastModel.trim().ifBlank { DEFAULT_FAST_MODEL },
        sttModel = sttModel.trim().ifBlank { DEFAULT_STT_MODEL },
        ttsModel = ttsModel.trim().ifBlank { DEFAULT_TTS_MODEL },
        ttsPremiumModel = ttsPremiumModel.trim().ifBlank { DEFAULT_TTS_PREMIUM_MODEL },
        inceptionBaseUrl = inceptionBaseUrl.trim().trimEnd('/').ifBlank { DEFAULT_INCEPTION_BASE_URL },
        groqBaseUrl = groqBaseUrl.trim().trimEnd('/').ifBlank { DEFAULT_GROQ_BASE_URL },
        geminiBaseUrl = geminiBaseUrl.trim().trimEnd('/').ifBlank { DEFAULT_GEMINI_BASE_URL }
    )

    companion object {
        const val DEFAULT_MIND_MODEL = "mercury-3"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        const val DEFAULT_CHAT_MODEL = "openai/gpt-oss-120b"
        const val ALT_CHAT_MODEL = "qwen/qwen3.6-27b"
        const val DEFAULT_FAST_MODEL = "openai/gpt-oss-20b"
        const val DEFAULT_STT_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_TTS_MODEL = "gemini-3.8-flash-lite-tts"
        const val DEFAULT_TTS_PREMIUM_MODEL = "gemini-3.8-flash-tts"

        const val DEFAULT_INCEPTION_BASE_URL = "https://api.inceptionlabs.ai/v1"
        const val DEFAULT_GROQ_BASE_URL = "https://api.groq.com/openai/v1"
        const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        val MERCURY_PRESETS = listOf(
            DEFAULT_MIND_MODEL,
            "mercury-coder",
            "mercury-small"
        )

        val CHAT_PRIORITY_LIST = listOf(
            "openai/gpt-oss-120b",
            "qwen/qwen3.6-27b",
            "openai/gpt-oss-20b",
            "qwen/qwen3-32b",
            "llama-3.1-8b-instant"
        )

        val FAST_PRIORITY_LIST = listOf(
            "openai/gpt-oss-20b",
            "llama-3.1-8b-instant",
            "qwen/qwen3.6-27b",
            "openai/gpt-oss-120b"
        )

        val STT_PRIORITY_LIST = listOf(
            "whisper-large-v3-turbo",
            "whisper-large-v3"
        )

        val GROQ_CHAT_PRESETS = listOf(
            DEFAULT_CHAT_MODEL,
            ALT_CHAT_MODEL,
            DEFAULT_FAST_MODEL
        )

        val GROQ_STT_PRESETS = listOf(
            DEFAULT_STT_MODEL,
            "whisper-large-v3"
        )

        val GEMINI_PRESETS = listOf(
            DEFAULT_GEMINI_MODEL,
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite"
        )

        val GEMINI_TTS_PRESETS = listOf(
            DEFAULT_TTS_MODEL,
            DEFAULT_TTS_PREMIUM_MODEL
        )

        val GEMINI_TTS_VOICES = listOf(
            "Kore",
            "Aoede",
            "Leda",
            "Puck",
            "Charon",
            "Zephyr"
        )

        fun isDeprecatedGroqDefault(model: String?): Boolean {
            val clean = model?.trim()?.lowercase().orEmpty()
            return clean == "llama-3.3-70b-versatile"
        }

        fun isDeprecatedGeminiDefault(model: String?): Boolean {
            val clean = model?.trim()?.lowercase().orEmpty()
            return clean == "gemini-1.5-flash" || clean == "gemini-1.5-pro"
        }

        fun pickBestReplacement(
            liveModels: List<String>,
            failedModel: String,
            role: GroqModelRole
        ): String? {
            val failedNorm = failedModel.trim().lowercase()
            val candidates = liveModels
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.lowercase() != failedNorm }
                .distinctBy { it.lowercase() }

            if (candidates.isEmpty()) return null

            val lookup = candidates.associateBy { it.lowercase() }

            return when (role) {
                GroqModelRole.STT -> {
                    STT_PRIORITY_LIST.firstNotNullOfOrNull { lookup[it.lowercase()] }
                        ?: candidates.firstOrNull { it.lowercase().contains("whisper") }
                }
                GroqModelRole.FAST -> {
                    val textCandidates = candidates.filter { isChatCapableModel(it) }
                    val textLookup = textCandidates.associateBy { it.lowercase() }
                    FAST_PRIORITY_LIST.firstNotNullOfOrNull { textLookup[it.lowercase()] }
                        ?: textCandidates.firstOrNull()
                }
                GroqModelRole.CHAT -> {
                    val textCandidates = candidates.filter { isChatCapableModel(it) }
                    val textLookup = textCandidates.associateBy { it.lowercase() }
                    CHAT_PRIORITY_LIST.firstNotNullOfOrNull { textLookup[it.lowercase()] }
                        ?: textCandidates.firstOrNull()
                }
            }
        }

        fun pickBestGeminiReplacement(
            liveModels: List<String>,
            failedModel: String
        ): String? {
            val failedNorm = failedModel.trim().lowercase().removePrefix("models/")
            val candidates = liveModels
                .map { it.trim().removePrefix("models/") }
                .filter { it.isNotEmpty() && it.lowercase() != failedNorm && !it.lowercase().contains("embedding") }
                .distinctBy { it.lowercase() }
            if (candidates.isEmpty()) return null
            val lookup = candidates.associateBy { it.lowercase() }
            return GEMINI_PRESETS.firstNotNullOfOrNull { lookup[it.lowercase()] }
                ?: candidates.firstOrNull { it.lowercase().contains("flash") }
                ?: candidates.firstOrNull()
        }

        fun pickBestMercuryReplacement(
            liveModels: List<String>,
            failedModel: String
        ): String? {
            val failedNorm = failedModel.trim().lowercase()
            val candidates = liveModels
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.lowercase() != failedNorm }
                .distinctBy { it.lowercase() }
            if (candidates.isEmpty()) return null
            val lookup = candidates.associateBy { it.lowercase() }
            return MERCURY_PRESETS.firstNotNullOfOrNull { lookup[it.lowercase()] }
                ?: candidates.firstOrNull()
        }

        fun isChatCapableModel(modelId: String): Boolean {
            val l = modelId.lowercase()
            return !l.contains("whisper") &&
                !l.contains("tts") &&
                !l.contains("orpheus") &&
                !l.contains("guard") &&
                !l.contains("safeguard") &&
                !l.contains("embed")
        }
    }
}
