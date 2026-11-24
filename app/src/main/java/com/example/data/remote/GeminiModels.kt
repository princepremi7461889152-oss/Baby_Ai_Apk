package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeminiGenerateRequest(
    val systemInstruction: GeminiContent? = null,
    val contents: List<GeminiContent>,
    val tools: List<GeminiTool>? = null,
    val generationConfig: GeminiGenerationConfig? = GeminiGenerationConfig()
)

/**
 * Note: Per Gemini 3.8 Flash official API guidelines:
 * - Use thinkingConfig.thinkingLevel ("low" for normal, "medium" for multi-step planning; never "minimal").
 * - Do NOT send temperature, topP, topK, or candidateCount.
 * - For Gemini TTS (gemini-3.8-flash-lite-tts / gemini-3.8-flash-tts), set responseModalities=["AUDIO"] and speechConfig.
 */
@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    val maxOutputTokens: Int? = 1024,
    val thinkingConfig: GeminiThinkingConfig? = null,
    val responseModalities: List<String>? = null,
    val speechConfig: GeminiSpeechConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiThinkingConfig(
    val thinkingLevel: String = "low",
    val includeThoughts: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GeminiSpeechConfig(
    val voiceConfig: GeminiVoiceConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiVoiceConfig(
    val prebuiltVoiceConfig: GeminiPrebuiltVoiceConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiPrebuiltVoiceConfig(
    val voiceName: String = "Kore"
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String? = null,
    val thought: Boolean? = null,
    val inlineData: GeminiInlineData? = null,
    val functionCall: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null,
    @Json(name = "speechMetadata") val speechMetadata: GeminiSpeechMetadata? = null
)

@JsonClass(generateAdapter = true)
data class GeminiSpeechMetadata(
    val style: String? = null,
    val speaker: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionCall(
    val name: String,
    val args: Map<String, Any?>? = emptyMap()
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionResponse(
    val name: String,
    val response: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>? = null,
    val googleSearch: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class GeminiGenerateResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiErrorBody? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent? = null,
    @Json(name = "finishReason") val finishReason: String? = null,
    val groundingMetadata: GeminiGroundingMetadata? = null
)

@JsonClass(generateAdapter = true)
data class GeminiGroundingMetadata(
    val groundingChunks: List<GeminiGroundingChunk>? = null,
    val webSearchQueries: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiGroundingChunk(
    val web: GeminiWebSource? = null
)

@JsonClass(generateAdapter = true)
data class GeminiWebSource(
    val uri: String? = null,
    val title: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiErrorBody(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiModelsListResponse(
    val models: List<GeminiModelInfo>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiModelInfo(
    val name: String? = null,
    val displayName: String? = null,
    val supportedGenerationMethods: List<String>? = null
)
