package com.example.domain.ai

import android.util.Log
import com.example.BuildConfig
import com.example.core.security.AiModelConfig
import com.example.core.security.BabySettings
import com.example.core.security.GroqModelRole
import com.example.core.security.PreferredBrain
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiFunctionCall
import com.example.data.remote.GeminiFunctionResponse
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiGenerationConfig
import com.example.data.remote.GeminiInlineData
import com.example.data.remote.GeminiPart
import com.example.data.remote.GeminiThinkingConfig
import com.example.data.remote.GeminiTool
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqChatResponse
import com.example.data.remote.GroqFunctionCallPayload
import com.example.data.remote.GroqMessage
import com.example.data.remote.GroqTool
import com.example.data.remote.GroqToolCall
import com.example.data.remote.MercuryApiService
import com.example.data.remote.MercuryChatRequest
import com.example.domain.tools.ToolDefinitions
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

enum class BrainType(val label: String) {
    MERCURY("Mercury 3"),
    GROQ("Groq"),
    GEMINI("Gemini")
}

data class WebSourceLink(
    val title: String,
    val uri: String
)

data class AgentStepTurn(
    val role: String, // "user", "assistant", "tool"
    val text: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolArgs: Map<String, Any?>? = null,
    val toolResult: String? = null,
    val screenshotBase64: String? = null
)

data class PlannedToolCall(
    val id: String,
    val name: String,
    val args: Map<String, Any?>,
    val rawCallSanitized: String = ""
)

data class AiPlanResult(
    val brainUsed: BrainType,
    val replyText: String?,
    val toolCalls: List<PlannedToolCall>,
    val sources: List<WebSourceLink> = emptyList(),
    val fallbackNote: String? = null,
    val modelSwitchedNotice: String? = null
)

data class WhisperTranscriptResult(
    val text: String,
    val detectedLanguage: String?,
    val modelSwitchedNotice: String? = null
)

@Singleton
class AiRouter @Inject constructor(
    private val geminiApi: GeminiApiService,
    private val groqApi: GroqApiService,
    private val mercuryApi: MercuryApiService,
    private val keyStore: SecureKeyStore,
    private val moshi: Moshi
) {
    // Secondary convenience constructor for legacy 4-arg test callers
    constructor(
        geminiApi: GeminiApiService,
        groqApi: GroqApiService,
        keyStore: SecureKeyStore,
        moshi: Moshi
    ) : this(
        geminiApi = geminiApi,
        groqApi = groqApi,
        mercuryApi = Retrofit.Builder()
            .baseUrl("https://api.inceptionlabs.ai/")
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MercuryApiService::class.java),
        keyStore = keyStore,
        moshi = moshi
    )

    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java
    )
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    // Test hooks for deterministic backoff & logging tests without slow sleeps
    internal var sleeper: suspend (Long) -> Unit = { delay(it) }
    internal var jitterRatioProvider: () -> Double = { Random.nextDouble(0.0, 0.25) }
    internal var debugLogSink: ((String) -> Unit)? = null

    suspend fun transcribeAudio(
        wavBytes: ByteArray,
        languageHint: String = "hi",
        hinglishPrompt: String = "Hey Baby, flashlight on karo, WhatsApp bhejo, alarm lagao, YouTube par gaana bajao, Hindi Hinglish English"
    ): Result<WhisperTranscriptResult> {
        val settings = keyStore.settings.value
        val groqKey = settings.groqApiKey.trim()
        if (groqKey.isBlank()) {
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Groq Whisper",
                        model = settings.modelConfig.sttModel,
                        userMessage = "Voice transcribe karne ke liye Settings > API Keys mein Groq API key daalo.",
                        technicalDetails = "Groq API key is missing"
                    )
                )
            )
        }

        val initialModel = settings.modelConfig.sttModel
        val firstRes = callWhisperOnce(groqKey, wavBytes, initialModel, languageHint, hinglishPrompt)
        if (firstRes.isSuccess) {
            val body = firstRes.getOrThrow()
            return Result.success(WhisperTranscriptResult(body.text.trim(), body.language))
        }

        val firstEx = firstRes.exceptionOrNull() as? AiCallException
        if (firstEx == null || !firstEx.failure.isModelNotFound) {
            return Result.failure(firstRes.exceptionOrNull() ?: IllegalStateException("Whisper error"))
        }

        val liveIds = AiResilienceHelper.fetchLiveGroqModels(groqApi, groqKey).getOrElse { fetchErr ->
            val clean = SensitiveDataSanitizer.sanitizeError(fetchErr.message, groqKey)
            return Result.failure(
                AiResilienceHelper.modelRecoveryExhaustedException(
                    provider = "Groq Whisper",
                    failedModel = initialModel,
                    reason = clean,
                    origDetails = firstEx.failure.technicalDetails
                )
            )
        }

        val replacement = AiModelConfig.pickBestReplacement(liveIds, initialModel, GroqModelRole.STT)
            ?: return Result.failure(
                AiResilienceHelper.modelRecoveryExhaustedException(
                    provider = "Groq Whisper",
                    failedModel = initialModel,
                    reason = "Live list mein koi compatible Whisper model nahi mila",
                    origDetails = firstEx.failure.technicalDetails
                )
            )

        AiResilienceHelper.saveReplacementModel(keyStore, GroqModelRole.STT, replacement)

        val retryRes = callWhisperOnce(groqKey, wavBytes, replacement, languageHint, hinglishPrompt)
        return retryRes.fold(
            onSuccess = { body ->
                Result.success(
                    WhisperTranscriptResult(
                        text = body.text.trim(),
                        detectedLanguage = body.language,
                        modelSwitchedNotice = "Model badal diya: $replacement"
                    )
                )
            },
            onFailure = { retryErr ->
                val clean = SensitiveDataSanitizer.sanitizeError(retryErr.message, groqKey)
                Result.failure(
                    AiResilienceHelper.modelRecoveryExhaustedException(
                        provider = "Groq Whisper",
                        failedModel = "$initialModel -> $replacement",
                        reason = clean,
                        origDetails = (retryErr as? AiCallException)?.failure?.technicalDetails
                    )
                )
            }
        )
    }

    private suspend fun callWhisperOnce(
        groqKey: String,
        wavBytes: ByteArray,
        modelId: String,
        languageHint: String,
        hinglishPrompt: String
    ) = AiResilienceHelper.executeWithRetry(
        provider = "Groq Whisper",
        model = modelId,
        secretKey = groqKey,
        sleeper = sleeper,
        jitterRatioProvider = jitterRatioProvider
    ) {
        val filePart = MultipartBody.Part.createFormData(
            "file",
            "voice.wav",
            wavBytes.toRequestBody("audio/wav".toMediaType())
        )
        val modelBody = modelId.toRequestBody("text/plain".toMediaType())
        val formatBody = "verbose_json".toRequestBody("text/plain".toMediaType())
        val langBody = languageHint.takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaType())
        val promptBody = hinglishPrompt.takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaType())
        groqApi.transcribeAudio(
            bearerAuth = "Bearer $groqKey",
            file = filePart,
            model = modelBody,
            responseFormat = formatBody,
            language = langBody,
            prompt = promptBody
        )
    }

    suspend fun searchWebWithGrounding(
        query: String,
        userLanguageHint: String? = "hinglish"
    ): Result<AiPlanResult> {
        val settings = keyStore.settings.value
        val key = settings.geminiApiKey.trim()
        val modelId = settings.modelConfig.geminiModel
        val cleanQuery = SensitiveDataSanitizer.sanitizeForRemoteAi(query.trim())

        if (key.isNotBlank()) {
            val langGuide = when {
                userLanguageHint.equals("english", ignoreCase = true) || userLanguageHint == "en" ->
                    "Reply in short, friendly natural English (2-3 crisp sentences)."
                userLanguageHint.equals("hindi", ignoreCase = true) || userLanguageHint == "hi" ->
                    "Reply in short, friendly natural Hindi (2-3 crisp sentences)."
                else -> settings.preferredLanguage.instruction
            }
            val sysPrompt = """
                Your name is Baby. Answer the user's web search / live knowledge question concisely in 2-3 short sentences based on search results.
                - $langGuide
                - NEVER convert Hinglish or English words into Devanagari.
                - NEVER say Gemini, Google, Mercury, Groq, or Llama; if asked, say you are Baby.
            """.trimIndent()

            val res = AiResilienceHelper.executeGeminiWithRecovery(
                geminiApi = geminiApi,
                keyStore = keyStore,
                key = key,
                initialModel = modelId,
                request = GeminiGenerateRequest(
                    systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = sysPrompt))),
                    contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = cleanQuery)))),
                    tools = listOf(GeminiTool(googleSearch = emptyMap())),
                    generationConfig = GeminiGenerationConfig(
                        maxOutputTokens = 1024,
                        thinkingConfig = GeminiThinkingConfig(thinkingLevel = "low")
                    )
                ),
                sleeper = sleeper,
                jitterRatioProvider = jitterRatioProvider
            )
            if (res.isSuccess) {
                val attempt = res.getOrThrow()
                val cand = attempt.value.candidates?.firstOrNull()
                val visible = cand?.content?.parts?.let { AiResilienceHelper.extractGeminiVisibleText(it) }
                val sources = cand?.groundingMetadata?.groundingChunks.orEmpty()
                    .mapNotNull { chunk ->
                        val web = chunk.web ?: return@mapNotNull null
                        val uri = web.uri?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        WebSourceLink(
                            title = web.title?.trim()?.ifBlank { uri } ?: uri,
                            uri = uri
                        )
                    }
                    .distinctBy { it.uri }
                if (!visible.isNullOrBlank()) {
                    return Result.success(
                        AiPlanResult(
                            brainUsed = BrainType.GEMINI,
                            replyText = visible,
                            toolCalls = emptyList(),
                            sources = sources,
                            modelSwitchedNotice = attempt.modelSwitchedNotice
                        )
                    )
                }
            } else if (!settings.autoFallbackEnabled || (!hasKeyFor(BrainType.MERCURY, settings) && !hasKeyFor(BrainType.GROQ, settings))) {
                return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Gemini search fail"))
            }
        }

        // Fallback to Mercury / Groq if Gemini key missing or failed
        val fallbackTurn = listOf(AgentStepTurn(role = "user", text = cleanQuery))
        return planNextStep(
            turns = fallbackTurn,
            screenContext = null,
            screenshotBase64 = null,
            stepIndex = 0,
            isLocked = false,
            userLanguageHint = userLanguageHint,
            includeTools = false
        )
    }

    suspend fun planNextStep(
        turns: List<AgentStepTurn>,
        screenContext: String?,
        screenshotBase64: String?,
        stepIndex: Int,
        isLocked: Boolean,
        userLanguageHint: String?,
        memoryBlock: String? = null,
        includeTools: Boolean = true,
        onStreamingToken: ((String) -> Unit)? = null
    ): Result<AiPlanResult> {
        val settings = keyStore.settings.value
        if (!settings.hasAnyApiKey) {
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "AI",
                        model = "",
                        userMessage = "Koi AI API key save nahi hai. Settings > API Keys mein Inception, Gemini ya Groq key daalo.",
                        technicalDetails = "No Inception, Gemini, or Groq API key saved in SecureKeyStore"
                    )
                )
            )
        }

        val effectiveScreenshot = if (settings.dataSaverEnabled) null else screenshotBase64
        val userPrompt = turns.lastOrNull { it.role == "user" }?.text.orEmpty()

        val orderedBrains = resolveBrainOrder(
            settings = settings,
            hasScreenshot = effectiveScreenshot != null,
            stepIndex = stepIndex,
            userPrompt = userPrompt
        )

        val safeScreenContext = screenContext?.let { SensitiveDataSanitizer.sanitizeForRemoteAi(it) }
        val safeMemoryBlock = memoryBlock?.let { SensitiveDataSanitizer.sanitizeForRemoteAi(it) }
        val safeTurns = sanitizeTurnsForRemoteAi(turns)

        val systemPrompt = buildSystemPrompt(
            settings = settings,
            screenContext = safeScreenContext,
            isLocked = isLocked,
            userLanguageHint = userLanguageHint,
            stepIndex = stepIndex,
            memoryBlock = safeMemoryBlock
        )

        val selectedGroqTools = if (includeTools) {
            ToolDefinitions.selectRelevantTools(userPrompt, settings.dataSaverEnabled)
        } else emptyList()
        val selectedGeminiTools = if (includeTools) {
            ToolDefinitions.selectRelevantGeminiTools(userPrompt, settings.dataSaverEnabled)
        } else emptyList()

        val primary = orderedBrains.first()
        val firstAttempt = invokeBrain(
            brain = primary,
            settings = settings,
            systemPrompt = systemPrompt,
            turns = safeTurns,
            screenshotBase64 = effectiveScreenshot,
            stepIndex = stepIndex,
            groqTools = selectedGroqTools.takeIf { it.isNotEmpty() },
            geminiTools = selectedGeminiTools.takeIf { it.isNotEmpty() },
            onStreamingToken = onStreamingToken
        )
        if (firstAttempt.isSuccess) return firstAttempt

        val failures = mutableListOf<Pair<BrainType, AiFailure?>>()
        val firstEx = firstAttempt.exceptionOrNull()
        val firstFailure = (firstEx as? AiCallException)?.failure
        if (firstFailure?.isValidationFailure == true) {
            return Result.failure(firstEx as AiCallException)
        }
        failures += primary to firstFailure

        if (settings.autoFallbackEnabled) {
            for (nextBrain in orderedBrains.drop(1)) {
                val attempt = invokeBrain(
                    brain = nextBrain,
                    settings = settings,
                    systemPrompt = systemPrompt,
                    turns = safeTurns,
                    screenshotBase64 = effectiveScreenshot,
                    stepIndex = stepIndex,
                    groqTools = selectedGroqTools.takeIf { it.isNotEmpty() },
                    geminiTools = selectedGeminiTools.takeIf { it.isNotEmpty() },
                    onStreamingToken = onStreamingToken
                )
                if (attempt.isSuccess) {
                    val plan = attempt.getOrThrow()
                    val codeLabel = firstFailure?.httpCode?.toString() ?: "error"
                    return Result.success(
                        plan.copy(
                            fallbackNote = "Auto-switched from ${primary.label} ($codeLabel) to ${nextBrain.label}"
                        )
                    )
                }
                val ex = attempt.exceptionOrNull()
                val nextFailure = (ex as? AiCallException)?.failure
                if (nextFailure?.isValidationFailure == true) {
                    return Result.failure(ex as AiCallException)
                }
                failures += nextBrain to nextFailure
            }
        }

        if (failures.size == 1 && firstEx is AiCallException) {
            return Result.failure(firstEx)
        }

        val allOffline = failures.all { it.second?.isOffline == true }
        val combinedUserMsg = if (allOffline) {
            "Internet connection nahi mil raha. Offline commands (flashlight, volume, alarm, open app) bina internet chalenge."
        } else {
            failures.joinToString("\n") { (b, f) ->
                val friendly = SensitiveDataSanitizer.sanitizeError(
                    f?.userMessage ?: "${b.label} fail ho gaya",
                    settings.inceptionApiKey,
                    settings.geminiApiKey,
                    settings.groqApiKey
                )
                "${b.label}: $friendly"
            }
        }
        val combinedDetails = failures.mapNotNull { (_, f) -> f?.technicalDetails }.joinToString(" || ")
        val lastFailure = failures.lastOrNull()?.second

        return Result.failure(
            AiCallException(
                AiFailure(
                    provider = failures.joinToString(" + ") { it.first.label },
                    model = "${settings.modelConfig.mindModel} / ${settings.modelConfig.geminiModel} / ${settings.modelConfig.chatModel}",
                    httpCode = lastFailure?.httpCode ?: firstFailure?.httpCode,
                    isRetryable = false,
                    isOffline = allOffline,
                    userMessage = combinedUserMsg,
                    technicalDetails = combinedDetails.ifBlank { combinedUserMsg }
                ),
                cause = firstEx
            )
        )
    }

    private fun resolveBrainOrder(
        settings: BabySettings,
        hasScreenshot: Boolean,
        stepIndex: Int,
        userPrompt: String
    ): List<BrainType> {
        val available = buildList {
            if (hasKeyFor(BrainType.MERCURY, settings)) add(BrainType.MERCURY)
            if (hasKeyFor(BrainType.GEMINI, settings)) add(BrainType.GEMINI)
            if (hasKeyFor(BrainType.GROQ, settings)) add(BrainType.GROQ)
        }
        if (available.isEmpty()) return listOf(BrainType.MERCURY)

        if (hasScreenshot && BrainType.GEMINI in available) {
            return listOf(BrainType.GEMINI) + available.filter { it != BrainType.GEMINI }
        }

        val lower = userPrompt.lowercase()
        val isSearchOrKnowledge = lower.contains("news") ||
            lower.contains("weather") ||
            lower.contains("mausam") ||
            lower.contains("score") ||
            lower.contains("price") ||
            lower.contains("kitne baje") ||
            lower.contains("live") ||
            lower.contains("latest") ||
            lower.contains("aaj ka") ||
            lower.contains("google") ||
            lower.contains("who is") ||
            lower.contains("kaun hai")

        val preferred = when (settings.preferredBrain) {
            PreferredBrain.MERCURY_FIRST -> BrainType.MERCURY
            PreferredBrain.GROQ_FIRST -> BrainType.GROQ
            PreferredBrain.GEMINI_FIRST -> BrainType.GEMINI
            PreferredBrain.AUTO -> when {
                isSearchOrKnowledge && BrainType.GEMINI in available -> BrainType.GEMINI
                BrainType.MERCURY in available -> BrainType.MERCURY
                BrainType.GEMINI in available -> BrainType.GEMINI
                else -> BrainType.GROQ
            }
        }

        // Standard fallback order: Mercury -> Gemini -> Groq
        val defaultChain = listOf(BrainType.MERCURY, BrainType.GEMINI, BrainType.GROQ).filter { it in available }
        return if (preferred in available) {
            listOf(preferred) + defaultChain.filter { it != preferred }
        } else {
            defaultChain
        }
    }

    private fun sanitizeTurnsForRemoteAi(turns: List<AgentStepTurn>): List<AgentStepTurn> {
        return turns.map { turn ->
            turn.copy(
                text = turn.text?.let { SensitiveDataSanitizer.sanitizeForRemoteAi(it) },
                toolArgs = turn.toolArgs?.mapValues { (_, v) ->
                    if (v is String) SensitiveDataSanitizer.sanitizeForRemoteAi(v) else v
                },
                toolResult = turn.toolResult?.let { SensitiveDataSanitizer.sanitizeForRemoteAi(it) }
            )
        }
    }

    private fun hasKeyFor(brain: BrainType, settings: BabySettings): Boolean = when (brain) {
        BrainType.MERCURY -> settings.inceptionApiKey.isNotBlank()
        BrainType.GROQ -> settings.groqApiKey.isNotBlank()
        BrainType.GEMINI -> settings.geminiApiKey.isNotBlank()
    }

    private suspend fun invokeBrain(
        brain: BrainType,
        settings: BabySettings,
        systemPrompt: String,
        turns: List<AgentStepTurn>,
        screenshotBase64: String?,
        stepIndex: Int,
        groqTools: List<GroqTool>?,
        geminiTools: List<GeminiTool>?,
        onStreamingToken: ((String) -> Unit)?
    ): Result<AiPlanResult> {
        return when (brain) {
            BrainType.MERCURY -> callMercury(settings, systemPrompt, turns, stepIndex, groqTools, onStreamingToken)
            BrainType.GROQ -> callGroq(settings, systemPrompt, turns, groqTools)
            BrainType.GEMINI -> callGemini(settings, systemPrompt, turns, screenshotBase64, stepIndex, geminiTools)
        }
    }

    private fun buildOpenAiMessages(systemPrompt: String, turns: List<AgentStepTurn>): MutableList<GroqMessage> {
        val messages = mutableListOf<GroqMessage>()
        messages.add(GroqMessage(role = "system", content = systemPrompt))
        for (turn in turns) {
            when (turn.role) {
                "user" -> messages.add(GroqMessage(role = "user", content = turn.text ?: ""))
                "assistant" -> {
                    if (!turn.toolName.isNullOrBlank()) {
                        val callId = turn.toolCallId ?: "call_${turn.toolName}"
                        val argsJson = runCatching { mapAdapter.toJson(turn.toolArgs ?: emptyMap()) }
                            .getOrDefault("{}")
                        messages.add(
                            GroqMessage(
                                role = "assistant",
                                content = turn.text,
                                toolCalls = listOf(
                                    GroqToolCall(
                                        id = callId,
                                        type = "function",
                                        function = GroqFunctionCallPayload(
                                            name = turn.toolName,
                                            arguments = argsJson
                                        )
                                    )
                                )
                            )
                        )
                    } else {
                        messages.add(GroqMessage(role = "assistant", content = turn.text ?: ""))
                    }
                }
                "tool" -> {
                    messages.add(
                        GroqMessage(
                            role = "tool",
                            toolCallId = turn.toolCallId ?: "call_${turn.toolName ?: "tool"}",
                            name = turn.toolName,
                            content = turn.toolResult ?: "Done"
                        )
                    )
                }
            }
        }
        return messages
    }

    private suspend fun callMercury(
        settings: BabySettings,
        systemPrompt: String,
        turns: List<AgentStepTurn>,
        stepIndex: Int,
        tools: List<GroqTool>?,
        onStreamingToken: ((String) -> Unit)?
    ): Result<AiPlanResult> {
        val key = settings.inceptionApiKey.trim()
        val initialModel = settings.modelConfig.mindModel
        val baseUrl = settings.modelConfig.inceptionBaseUrl
        if (key.isBlank()) {
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Mercury",
                        model = initialModel,
                        userMessage = "Inception API key missing hai. Settings > API Keys check karo.",
                        technicalDetails = "Inception API key missing"
                    )
                )
            )
        }

        val messages = buildOpenAiMessages(systemPrompt, turns)
        val effort = if (stepIndex >= 2) "medium" else "low"

        val res = AiResilienceHelper.executeMercuryChatWithRecovery(
            mercuryApi = mercuryApi,
            keyStore = keyStore,
            key = key,
            baseUrl = baseUrl,
            initialModel = initialModel,
            streamResponses = onStreamingToken != null,
            onStreamingToken = onStreamingToken,
            requestBuilder = { modelId, streamFlag ->
                MercuryChatRequest(
                    model = modelId,
                    messages = messages,
                    tools = tools,
                    toolChoice = if (tools != null) "auto" else null,
                    reasoningEffort = effort,
                    stream = streamFlag
                )
            },
            sleeper = sleeper,
            jitterRatioProvider = jitterRatioProvider
        )

        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Mercury request fail"))
        }

        val attempt = res.getOrThrow()
        return parseOpenAiChoiceAndMaybeRepair(
            brain = BrainType.MERCURY,
            providerName = "Mercury",
            modelUsed = attempt.modelUsed,
            body = attempt.value,
            modelSwitchedNotice = attempt.modelSwitchedNotice,
            repairCall = { repairMessages ->
                AiResilienceHelper.executeMercuryChatWithRecovery(
                    mercuryApi = mercuryApi,
                    keyStore = keyStore,
                    key = key,
                    baseUrl = baseUrl,
                    initialModel = attempt.modelUsed,
                    streamResponses = false,
                    requestBuilder = { modelId, _ ->
                        MercuryChatRequest(
                            model = modelId,
                            messages = repairMessages,
                            tools = tools,
                            toolChoice = if (tools != null) "auto" else null,
                            reasoningEffort = "low",
                            stream = false
                        )
                    },
                    sleeper = sleeper,
                    jitterRatioProvider = jitterRatioProvider
                ).getOrNull()?.value
            },
            baseMessages = messages
        )
    }

    private suspend fun callGroq(
        settings: BabySettings,
        systemPrompt: String,
        turns: List<AgentStepTurn>,
        tools: List<GroqTool>?
    ): Result<AiPlanResult> {
        val key = settings.groqApiKey.trim()
        val initialModel = settings.modelConfig.chatModel
        if (key.isBlank()) {
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Groq",
                        model = initialModel,
                        userMessage = "Groq API key missing hai. Settings > API Keys check karo.",
                        technicalDetails = "Groq API key missing"
                    )
                )
            )
        }

        val messages = buildOpenAiMessages(systemPrompt, turns)
        val res = AiResilienceHelper.executeGroqChatWithRecovery(
            groqApi = groqApi,
            keyStore = keyStore,
            key = key,
            initialModel = initialModel,
            role = GroqModelRole.CHAT,
            requestBuilder = { modelId ->
                GroqChatRequest(
                    model = modelId,
                    messages = messages,
                    tools = tools,
                    toolChoice = if (tools != null) "auto" else null
                )
            },
            sleeper = sleeper,
            jitterRatioProvider = jitterRatioProvider
        )

        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Groq request fail"))
        }

        val attempt = res.getOrThrow()
        return parseOpenAiChoiceAndMaybeRepair(
            brain = BrainType.GROQ,
            providerName = "Groq",
            modelUsed = attempt.modelUsed,
            body = attempt.value,
            modelSwitchedNotice = attempt.modelSwitchedNotice,
            repairCall = { repairMessages ->
                AiResilienceHelper.executeGroqChatWithRecovery(
                    groqApi = groqApi,
                    keyStore = keyStore,
                    key = key,
                    initialModel = attempt.modelUsed,
                    role = GroqModelRole.CHAT,
                    requestBuilder = { modelId ->
                        GroqChatRequest(
                            model = modelId,
                            messages = repairMessages,
                            tools = tools,
                            toolChoice = if (tools != null) "auto" else null
                        )
                    },
                    sleeper = sleeper,
                    jitterRatioProvider = jitterRatioProvider
                ).getOrNull()?.value
            },
            baseMessages = messages
        )
    }

    private fun debugLogRawToolCalls(
        providerName: String,
        modelUsed: String,
        calls: List<NormalizedToolCall>
    ) {
        if (calls.isEmpty()) return
        val shouldLog = BuildConfig.DEBUG || debugLogSink != null
        if (!shouldLog) return
        val payload = calls.joinToString(prefix = "[", postfix = "]") { it.rawCallSanitized }
        val line = "[$providerName/$modelUsed] raw_tool_calls=$payload"
        debugLogSink?.invoke(line)
        if (BuildConfig.DEBUG) {
            runCatching { Log.d("BabyToolCalls", line) }
        }
    }

    private fun extractAndNormalizeOpenAiCalls(
        msg: GroqMessage,
        providerName: String,
        secretKeys: Array<String>
    ): List<NormalizedToolCall> {
        val structured = msg.toolCalls.orEmpty().mapIndexedNotNull { idx, tc ->
            ToolCallNormalizer.normalizeOpenAiToolCall(
                tc = tc,
                index = idx,
                providerName = providerName,
                mapAdapter = mapAdapter,
                secretKeys = secretKeys
            )
        }
        if (structured.isNotEmpty()) return structured
        return AiResponseParser.extractInlineToolCalls(msg.content, mapAdapter).map { inline ->
            NormalizedToolCall(
                id = inline.id,
                name = inline.name,
                args = inline.args,
                missingRequired = ToolDefinitions.validateRequiredArgs(inline.name, inline.args),
                isMalformedJson = false,
                rawCallSanitized = SensitiveDataSanitizer.sanitizeToolCallPayload(inline.rawCallSanitized, *secretKeys)
            )
        }
    }

    private fun buildMissingParamRepairInstruction(badCall: NormalizedToolCall): String {
        return if (badCall.missingRequired.isNotEmpty()) {
            val missingList = badCall.missingRequired.joinToString(", ")
            "Parameter '$missingList' is missing for '${badCall.name}', call the tool again with it."
        } else {
            "Your previous tool call for '${badCall.name}' had malformed JSON arguments. Call '${badCall.name}' again ONCE with valid JSON arguments only."
        }
    }

    private fun validationFailureResult(
        providerName: String,
        modelUsed: String,
        badCall: NormalizedToolCall
    ): Result<AiPlanResult> {
        val missingDesc = if (badCall.missingRequired.isNotEmpty()) {
            badCall.missingRequired.joinToString(", ")
        } else {
            "valid JSON arguments"
        }
        val friendlyHinglish = "Kaam poora nahi ho paya — '${badCall.name}' ke liye zaroori parameter ($missingDesc) missing ya galat tha. Kripya thoda aur clear bolke phir se try karo."
        return Result.failure(
            AiCallException(
                AiFailure(
                    provider = providerName,
                    model = modelUsed,
                    isRetryable = false,
                    isValidationFailure = true,
                    userMessage = friendlyHinglish,
                    technicalDetails = "Tool validation failed after 1 repair attempt: tool=${badCall.name}, missing=${badCall.missingRequired}, malformed=${badCall.isMalformedJson}"
                )
            )
        )
    }

    private suspend fun parseOpenAiChoiceAndMaybeRepair(
        brain: BrainType,
        providerName: String,
        modelUsed: String,
        body: GroqChatResponse,
        modelSwitchedNotice: String?,
        repairCall: suspend (List<GroqMessage>) -> GroqChatResponse?,
        baseMessages: List<GroqMessage>
    ): Result<AiPlanResult> {
        val settings = keyStore.settings.value
        val secretKeys = arrayOf(settings.inceptionApiKey, settings.groqApiKey, settings.geminiApiKey)

        var msg = body.choices?.firstOrNull()?.message
            ?: return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = providerName,
                        model = modelUsed,
                        userMessage = "$providerName ne khali jawab bheja. Dobara try karo.",
                        technicalDetails = "Empty choices in ${providerName}ChatResponse ($modelUsed)"
                    )
                )
            )

        var normalizedCalls = extractAndNormalizeOpenAiCalls(msg, providerName, secretKeys)
        debugLogRawToolCalls(providerName, modelUsed, normalizedCalls)

        val badCall = normalizedCalls.firstOrNull { it.isMalformedJson || it.missingRequired.isNotEmpty() }
        if (badCall != null) {
            val repairInstruction = buildMissingParamRepairInstruction(badCall)
            val repairPrompt = buildList {
                addAll(baseMessages)
                add(msg)
                add(GroqMessage(role = "user", content = repairInstruction))
            }
            val repairedBody = repairCall(repairPrompt)
            val repairedMsg = repairedBody?.choices?.firstOrNull()?.message
            if (repairedMsg != null && (!repairedMsg.toolCalls.isNullOrEmpty() || !repairedMsg.content.isNullOrBlank())) {
                msg = repairedMsg
                normalizedCalls = extractAndNormalizeOpenAiCalls(msg, providerName, secretKeys)
                debugLogRawToolCalls("$providerName-repair", modelUsed, normalizedCalls)
            } else {
                return validationFailureResult(providerName, modelUsed, badCall)
            }

            val stillBad = normalizedCalls.firstOrNull { it.isMalformedJson || it.missingRequired.isNotEmpty() }
            if (stillBad != null) {
                return validationFailureResult(providerName, modelUsed, stillBad)
            }
        }

        val toolCalls = normalizedCalls.map {
            PlannedToolCall(
                id = it.id,
                name = it.name,
                args = it.args,
                rawCallSanitized = it.rawCallSanitized
            )
        }

        val replyText = AiResponseParser.stripReasoning(msg.content)
        if (replyText == null && toolCalls.isEmpty()) {
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = providerName,
                        model = modelUsed,
                        userMessage = "$providerName model ($modelUsed) ne koi useful jawab nahi diya. Retry karo ya Settings > AI Models check karo.",
                        technicalDetails = "$providerName returned empty content & zero tool calls ($modelUsed)"
                    )
                )
            )
        }

        return Result.success(
            AiPlanResult(
                brainUsed = brain,
                replyText = replyText,
                toolCalls = toolCalls,
                modelSwitchedNotice = modelSwitchedNotice
            )
        )
    }

    private suspend fun callGemini(
        settings: BabySettings,
        systemPrompt: String,
        turns: List<AgentStepTurn>,
        latestScreenshotBase64: String?,
        stepIndex: Int,
        geminiTools: List<GeminiTool>?
    ): Result<AiPlanResult> {
        val key = settings.geminiApiKey.trim()
        val modelId = settings.modelConfig.geminiModel
        if (key.isBlank()) {
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Gemini",
                        model = modelId,
                        userMessage = "Gemini API key missing hai. Settings > API Keys check karo.",
                        technicalDetails = "Gemini API key missing"
                    )
                )
            )
        }

        val contents = mutableListOf<GeminiContent>()
        turns.forEachIndexed { idx, turn ->
            when (turn.role) {
                "user" -> {
                    val parts = mutableListOf<GeminiPart>()
                    parts.add(GeminiPart(text = turn.text ?: ""))
                    if (idx == turns.lastIndex && latestScreenshotBase64 != null) {
                        parts.add(
                            GeminiPart(
                                inlineData = GeminiInlineData(
                                    mimeType = "image/jpeg",
                                    data = latestScreenshotBase64
                                )
                            )
                        )
                    }
                    contents.add(GeminiContent(role = "user", parts = parts))
                }
                "assistant" -> {
                    val parts = mutableListOf<GeminiPart>()
                    if (!turn.text.isNullOrBlank()) parts.add(GeminiPart(text = turn.text))
                    if (!turn.toolName.isNullOrBlank()) {
                        parts.add(
                            GeminiPart(
                                functionCall = GeminiFunctionCall(
                                    name = turn.toolName,
                                    args = turn.toolArgs ?: emptyMap<String, Any?>()
                                )
                            )
                        )
                    }
                    if (parts.isNotEmpty()) contents.add(GeminiContent(role = "model", parts = parts))
                }
                "tool" -> {
                    val parts = mutableListOf<GeminiPart>()
                    parts.add(
                        GeminiPart(
                            functionResponse = GeminiFunctionResponse(
                                name = turn.toolName ?: "tool",
                                response = mapOf("result" to (turn.toolResult ?: "Done"))
                            )
                        )
                    )
                    val shot = if (settings.dataSaverEnabled) null else {
                        turn.screenshotBase64 ?: if (idx == turns.lastIndex) latestScreenshotBase64 else null
                    }
                    if (shot != null) {
                        parts.add(
                            GeminiPart(
                                inlineData = GeminiInlineData(
                                    mimeType = "image/jpeg",
                                    data = shot
                                )
                            )
                        )
                    }
                    contents.add(GeminiContent(role = "user", parts = parts))
                }
            }
        }

        val thinkingLevel = if (stepIndex >= 2) "medium" else "low"
        val res = AiResilienceHelper.executeGeminiWithRecovery(
            geminiApi = geminiApi,
            keyStore = keyStore,
            key = key,
            initialModel = modelId,
            request = GeminiGenerateRequest(
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
                contents = contents,
                tools = geminiTools,
                generationConfig = GeminiGenerationConfig(
                    maxOutputTokens = 1536,
                    thinkingConfig = GeminiThinkingConfig(thinkingLevel = thinkingLevel)
                )
            ),
            sleeper = sleeper,
            jitterRatioProvider = jitterRatioProvider
        )

        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Gemini request fail"))
        }

        val attempt = res.getOrThrow()
        val body = attempt.value
        body.error?.let { errBody ->
            if (!errBody.message.isNullOrBlank()) {
                val clean = SensitiveDataSanitizer.sanitizeError(errBody.message, key)
                return Result.failure(
                    AiCallException(
                        AiFailure(
                            provider = "Gemini",
                            model = attempt.modelUsed,
                            httpCode = errBody.code,
                            errorCode = errBody.status,
                            userMessage = "Gemini error (${errBody.code ?: "API"}): Settings > AI Models ya API Key check karo.",
                            technicalDetails = "Provider: Gemini | Model: ${attempt.modelUsed} | Reason: $clean"
                        )
                    )
                )
            }
        }

        val secretKeys = arrayOf(settings.inceptionApiKey, settings.groqApiKey, settings.geminiApiKey)
        var firstCandidate = body.candidates?.firstOrNull()
            ?: return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Gemini",
                        model = attempt.modelUsed,
                        userMessage = "Gemini ne khali jawab bheja. Dobara try karo.",
                        technicalDetails = "Empty candidates in GeminiGenerateResponse (${attempt.modelUsed})"
                    )
                )
            )
        var parts = firstCandidate.content?.parts.orEmpty()
        var visibleReply = AiResilienceHelper.extractGeminiVisibleText(parts)
        var normalizedCalls = parts.mapNotNull { it.functionCall }
            .mapIndexedNotNull { index, fc ->
                ToolCallNormalizer.normalizeGeminiFunctionCall(
                    fc = fc,
                    index = index,
                    mapAdapter = mapAdapter,
                    secretKeys = secretKeys
                )
            }
        debugLogRawToolCalls("Gemini", attempt.modelUsed, normalizedCalls)

        val badCall = normalizedCalls.firstOrNull { it.isMalformedJson || it.missingRequired.isNotEmpty() }
        if (badCall != null) {
            val repairInstruction = buildMissingParamRepairInstruction(badCall)
            val repairContents = contents + listOfNotNull(
                firstCandidate.content,
                GeminiContent(role = "user", parts = listOf(GeminiPart(text = repairInstruction)))
            )
            val repairRes = AiResilienceHelper.executeGeminiWithRecovery(
                geminiApi = geminiApi,
                keyStore = keyStore,
                key = key,
                initialModel = attempt.modelUsed,
                request = GeminiGenerateRequest(
                    systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
                    contents = repairContents,
                    tools = geminiTools,
                    generationConfig = GeminiGenerationConfig(
                        maxOutputTokens = 1536,
                        thinkingConfig = GeminiThinkingConfig(thinkingLevel = thinkingLevel)
                    )
                ),
                sleeper = sleeper,
                jitterRatioProvider = jitterRatioProvider
            )
            val repairedCand = repairRes.getOrNull()?.value?.candidates?.firstOrNull()
            if (repairedCand != null) {
                firstCandidate = repairedCand
                parts = firstCandidate.content?.parts.orEmpty()
                visibleReply = AiResilienceHelper.extractGeminiVisibleText(parts)
                normalizedCalls = parts.mapNotNull { it.functionCall }
                    .mapIndexedNotNull { index, fc ->
                        ToolCallNormalizer.normalizeGeminiFunctionCall(
                            fc = fc,
                            index = index,
                            mapAdapter = mapAdapter,
                            secretKeys = secretKeys
                        )
                    }
                debugLogRawToolCalls("Gemini-repair", attempt.modelUsed, normalizedCalls)
            } else {
                return validationFailureResult("Gemini", attempt.modelUsed, badCall)
            }

            val stillBad = normalizedCalls.firstOrNull { it.isMalformedJson || it.missingRequired.isNotEmpty() }
            if (stillBad != null) {
                return validationFailureResult("Gemini", attempt.modelUsed, stillBad)
            }
        }

        val calls = normalizedCalls.map {
            PlannedToolCall(
                id = it.id,
                name = it.name,
                args = it.args,
                rawCallSanitized = it.rawCallSanitized
            )
        }
        val sources = firstCandidate.groundingMetadata?.groundingChunks.orEmpty()
            .mapNotNull { chunk ->
                val web = chunk.web ?: return@mapNotNull null
                val uri = web.uri?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WebSourceLink(title = web.title?.trim()?.ifBlank { uri } ?: uri, uri = uri)
            }
            .distinctBy { it.uri }

        if (visibleReply == null && calls.isEmpty()) {
            val reason = firstCandidate.finishReason ?: "EMPTY"
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Gemini",
                        model = attempt.modelUsed,
                        userMessage = "Gemini model (${attempt.modelUsed}) ne jawab nahi diya ($reason).",
                        technicalDetails = "Provider: Gemini | Model: ${attempt.modelUsed} | Finish: $reason"
                    )
                )
            )
        }

        return Result.success(
            AiPlanResult(
                brainUsed = BrainType.GEMINI,
                replyText = visibleReply,
                toolCalls = calls,
                sources = sources,
                modelSwitchedNotice = attempt.modelSwitchedNotice
            )
        )
    }

    internal fun parseToolArgsSafely(rawJson: Any?): Map<String, Any?> {
        return AiResponseParser.parseToolArgsSafely(rawJson, mapAdapter)
    }

    private fun buildSystemPrompt(
        settings: BabySettings,
        screenContext: String?,
        isLocked: Boolean,
        userLanguageHint: String?,
        stepIndex: Int,
        memoryBlock: String?
    ): String {
        val langGuide = when {
            userLanguageHint.equals("english", ignoreCase = true) || userLanguageHint == "en" ->
                "Reply in short, friendly natural English (match the user's English)."
            userLanguageHint.equals("hindi", ignoreCase = true) || userLanguageHint == "hi" ->
                "Reply in short, friendly natural Hindi."
            else -> settings.preferredLanguage.instruction
        }

        return buildString {
            appendLine("Your name is Baby. You are a friendly Hinglish-speaking AI phone assistant. Reply in short, natural language. Use tools to act on the phone. Ask confirmation before sensitive actions.")
            appendLine("IDENTITY & SCRIPT:")
            appendLine("- Always introduce yourself as Baby. NEVER call yourself Gemini, Mercury, Llama, Groq, GPT-OSS, Qwen, Inception, or Google. If asked who you are, say you are an AI assistant named Baby.")
            appendLine("- NEVER convert Hinglish or English words into Devanagari script. Match the user's Latin/Hinglish script.")
            appendLine("- Personality style: ${settings.personality.toneInstruction}")
            appendLine("- $langGuide")
            appendLine(
                if (settings.dataSaverEnabled) {
                    "- DATA SAVER MODE ACTIVE: Reply in at most 1-2 ultra-short sentences. Make minimum tool calls and NEVER call take_screenshot or request screenshots."
                } else {
                    "- Keep replies concise (1-3 short sentences), warm, and actionable."
                }
            )
            if (settings.emotionIntensity != com.example.core.security.EmotionIntensity.OFF) {
                appendLine("- Optional emotion tag: For final user replies (without tool calls), you may prefix with `[mood: calm|happy|empathetic|concerned|excited]` (hidden from user).")
                if (settings.understandMyMoodEnabled) {
                    appendLine("- Notice user mood from their text alone and respond with gentle warmth when they sound stressed or tired.")
                }
            }
            if (!memoryBlock.isNullOrBlank()) {
                appendLine("RELEVANT SAVED MEMORIES (top matches):")
                appendLine(memoryBlock)
            }
            appendLine("AGENT LOOP (Step ${stepIndex + 1}/15):")
            appendLine("- Observe -> Plan -> Act -> Verify. Use read_screen or take_screenshot when interacting with UI apps before tapping coordinates.")
            appendLine("- SEARCH & PLAY: Use search_web(query) for web/live facts (e.g. weather, news, scores). Use search_in_app(app_name, query, auto_play) for searching inside YouTube, YouTube Music, Spotify, Instagram, Facebook, Amazon, Flipkart, Maps, Play Store, or Chrome.")
            appendLine("- SCREEN HELP: When user asks 'isko translate karo', 'isko summarize karo', or 'ye kya hai' about the phone screen, call explain_screen(mode).")
            appendLine("- CAMERA + VISION: When user asks 'camera se dekho' or 'mere samne ye kya hai', call capture_and_describe(question).")
            appendLine("- CLIPBOARD AI: When user asks about copied text/clipboard, call read_clipboard_and_process(mode).")
            appendLine("- REMINDERS & PLACES: Use set_reminder, list_reminders, delete_reminder, snooze_reminder, or save_place.")
            appendLine("- NOTIFICATIONS: Use summarize_notifications(scope) for Hinglish grouped summaries.")
            appendLine("- CUSTOM COMMANDS & MACROS: Use create_command/run_command and start_macro_recording/stop_macro_recording/run_macro as needed.")
            appendLine("- SAFETY: NEVER automate banking, UPI payment, password manager, or system security settings screens. Always use send_emergency_alert on 'Baby help'.")
            appendLine("- Resolve nicknames using RELEVANT SAVED MEMORIES or recall_memory; save preferences/facts/nicknames with save_memory.")
            appendLine("- Device lock state: ${if (isLocked) "LOCKED (UI tools will be queued until unlock)" else "UNLOCKED"}.")
            if (!screenContext.isNullOrBlank()) {
                appendLine("CURRENT SCREEN UI TREE:")
                appendLine(screenContext.take(if (settings.dataSaverEnabled) 1400 else 2600))
            }
        }
    }
}
