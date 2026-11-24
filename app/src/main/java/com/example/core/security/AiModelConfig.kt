package com.example.core.security

enum class GroqModelRole {
    CHAT,
    FAST,
    STT
}

/**
 * Single source of truth for all AI model IDs used across Baby.
 */
data class AiModelConfig(
    val chatModel: String = DEFAULT_CHAT_MODEL,
    val fastModel: String = DEFAULT_FAST_MODEL,
    val sttModel: String = DEFAULT_STT_MODEL,
    val geminiModel: String = DEFAULT_GEMINI_MODEL
) {
    fun normalized(): AiModelConfig = AiModelConfig(
        chatModel = chatModel.trim().ifBlank { DEFAULT_CHAT_MODEL },
        fastModel = fastModel.trim().ifBlank { DEFAULT_FAST_MODEL },
        sttModel = sttModel.trim().ifBlank { DEFAULT_STT_MODEL },
        geminiModel = geminiModel.trim().ifBlank { DEFAULT_GEMINI_MODEL }
    )

    companion object {
        const val DEFAULT_CHAT_MODEL = "openai/gpt-oss-120b"
        const val ALT_CHAT_MODEL = "qwen/qwen3.6-27b"
        const val DEFAULT_FAST_MODEL = "openai/gpt-oss-20b"
        const val DEFAULT_STT_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

        val CHAT_PRIORITY_LIST = listOf(
            "openai/gpt-oss-120b",
            "qwen/qwen3.6-27b",
            "openai/gpt-oss-20b",
            "qwen/qwen3-32b",
            "llama-3.3-70b-specdec",
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
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite"
        )

        fun isDeprecatedGroqDefault(model: String?): Boolean {
            val clean = model?.trim()?.lowercase().orEmpty()
            return clean == "llama-3.3-70b-versatile"
        }

        fun isDeprecatedGeminiDefault(model: String?): Boolean {
            val clean = model?.trim()?.lowercase().orEmpty()
            return clean == "gemini-3.8-flash"
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
