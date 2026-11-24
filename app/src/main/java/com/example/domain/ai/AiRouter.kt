package com.example.domain.ai

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
import com.example.data.remote.GeminiInlineData
import com.example.data.remote.GeminiPart
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqFunctionCallPayload
import com.example.data.remote.GroqMessage
import com.example.data.remote.GroqToolCall
import com.example.domain.tools.ToolDefinitions
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

enum class BrainType(val label: String) {
    GROQ("Groq"),
    GEMINI("Gemini")
}

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
    val args: Map<String, Any?>
)

data class AiPlanResult(
    val brainUsed: BrainType,
    val replyText: String?,
    val toolCalls: List<PlannedToolCall>,
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
    private val keyStore: SecureKeyStore,
    private val moshi: Moshi
) {
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java
    )
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    // Test hooks for deterministic backoff tests without slow sleeps
    internal var sleeper: suspend (Long) -> Unit = { delay(it) }
    internal var jitterRatioProvider: () -> Double = { Random.nextDouble(0.0, 0.25) }

    suspend fun transcribeAudio(wavBytes: ByteArray): Result<WhisperTranscriptResult> {
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
        val firstRes = callWhisperOnce(groqKey, wavBytes, initialModel)
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
                    failedModel = initialModel,
                    reason = clean,
                    origDetails = firstEx.failure.technicalDetails
                )
            )
        }

        val replacement = AiModelConfig.pickBestReplacement(liveIds, initialModel, GroqModelRole.STT)
            ?: return Result.failure(
                AiResilienceHelper.modelRecoveryExhaustedException(
                    failedModel = initialModel,
                    reason = "Live list mein koi compatible Whisper model nahi mila",
                    origDetails = firstEx.failure.technicalDetails
                )
            )

        AiResilienceHelper.saveReplacementModel(keyStore, GroqModelRole.STT, replacement)

        val retryRes = callWhisperOnce(groqKey, wavBytes, replacement)
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
        modelId: String
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
        groqApi.transcribeAudio(
            bearerAuth = "Bearer $groqKey",
            file = filePart,
            model = modelBody,
            responseFormat = formatBody
        )
    }

    suspend fun planNextStep(
        turns: List<AgentStepTurn>,
        screenContext: String?,
        screenshotBase64: String?,
        stepIndex: Int,
        isLocked: Boolean,
        userLanguageHint: String?,
        memoryBlock: String? = null
    ): Result<AiPlanResult> {
        val settings = keyStore.settings.value
        if (!settings.hasAnyApiKey) {
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "AI",
                        model = "",
                        userMessage = "Koi AI API key save nahi hai. Settings > API Keys mein Groq ya Gemini key daalo.",
                        technicalDetails = "No Groq or Gemini API key saved in SecureKeyStore"
                    )
                )
            )
        }

        val effectiveScreenshot = if (settings.dataSaverEnabled) null else screenshotBase64

        val primary = choosePrimaryBrain(
            settings = settings,
            hasScreenshot = effectiveScreenshot != null,
            stepIndex = stepIndex,
            userPrompt = turns.firstOrNull { it.role == "user" }?.text.orEmpty()
        )
        val secondary = if (primary == BrainType.GROQ) BrainType.GEMINI else BrainType.GROQ

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

        val firstAttempt = invokeBrain(primary, settings, systemPrompt, safeTurns, effectiveScreenshot)
        if (firstAttempt.isSuccess) return firstAttempt

        val firstException = firstAttempt.exceptionOrNull()
        val firstFailure = (firstException as? AiCallException)?.failure
        val firstFriendly = SensitiveDataSanitizer.sanitizeError(
            firstFailure?.userMessage ?: firstException?.message ?: "Primary AI fail ho gaya",
            settings.groqApiKey,
            settings.geminiApiKey
        )

        if (settings.autoFallbackEnabled && hasKeyFor(secondary, settings)) {
            val fallbackAttempt = invokeBrain(secondary, settings, systemPrompt, safeTurns, effectiveScreenshot)
            if (fallbackAttempt.isSuccess) {
                val plan = fallbackAttempt.getOrThrow()
                return Result.success(
                    plan.copy(
                        fallbackNote = "Auto-switched from ${primary.label} (${firstFailure?.httpCode ?: "error"}) to ${secondary.label}"
                    )
                )
            }
            val secondException = fallbackAttempt.exceptionOrNull()
            val secondFailure = (secondException as? AiCallException)?.failure
            val secondFriendly = SensitiveDataSanitizer.sanitizeError(
                secondFailure?.userMessage ?: secondException?.message ?: "Fallback AI fail ho gaya",
                settings.groqApiKey,
                settings.geminiApiKey
            )
            val combinedUserMsg = buildString {
                if (firstFailure?.isOffline == true && secondFailure?.isOffline == true) {
                    append("Internet connection nahi mil raha. Offline commands (flashlight, volume, alarm, open app) bina internet chalenge.")
                } else {
                    append("${primary.label}: $firstFriendly")
                    append("\n${secondary.label}: $secondFriendly")
                }
            }
            val combinedDetails = listOfNotNull(
                firstFailure?.technicalDetails ?: firstFriendly,
                secondFailure?.technicalDetails ?: secondFriendly
            ).joinToString(" || ")

            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Groq + Gemini",
                        model = "${settings.modelConfig.chatModel} / ${settings.modelConfig.geminiModel}",
                        httpCode = secondFailure?.httpCode ?: firstFailure?.httpCode,
                        isRetryable = false,
                        isOffline = firstFailure?.isOffline == true && secondFailure?.isOffline == true,
                        userMessage = combinedUserMsg,
                        technicalDetails = combinedDetails
                    )
                )
            )
        }

        if (firstException is AiCallException) {
            return Result.failure(firstException)
        }
        return Result.failure(
            AiCallException(
                AiFailure(
                    provider = primary.label,
                    model = if (primary == BrainType.GROQ) settings.modelConfig.chatModel else settings.modelConfig.geminiModel,
                    userMessage = firstFriendly,
                    technicalDetails = firstFriendly
                ),
                cause = firstException
            )
        )
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

    private fun choosePrimaryBrain(
        settings: BabySettings,
        hasScreenshot: Boolean,
        stepIndex: Int,
        userPrompt: String
    ): BrainType {
        val hasGroq = settings.groqApiKey.isNotBlank()
        val hasGemini = settings.geminiApiKey.isNotBlank()

        if (!hasGroq && hasGemini) return BrainType.GEMINI
        if (hasGroq && !hasGemini) return BrainType.GROQ

        if (hasScreenshot) return BrainType.GEMINI

        return when (settings.preferredBrain) {
            PreferredBrain.GROQ_FIRST -> BrainType.GROQ
            PreferredBrain.GEMINI_FIRST -> BrainType.GEMINI
            PreferredBrain.AUTO -> {
                val isComplex = stepIndex >= 2 ||
                    userPrompt.length > 140 ||
                    listOf("analyze", "summarize", "explain", "screenshot", "screen", "multi", "order", "book")
                        .any { userPrompt.lowercase().contains(it) }
                if (isComplex) BrainType.GEMINI else BrainType.GROQ
            }
        }
    }

    private fun hasKeyFor(brain: BrainType, settings: BabySettings): Boolean = when (brain) {
        BrainType.GROQ -> settings.groqApiKey.isNotBlank()
        BrainType.GEMINI -> settings.geminiApiKey.isNotBlank()
    }

    private suspend fun invokeBrain(
        brain: BrainType,
        settings: BabySettings,
        systemPrompt: String,
        turns: List<AgentStepTurn>,
        screenshotBase64: String?
    ): Result<AiPlanResult> {
        return when (brain) {
            BrainType.GROQ -> callGroq(settings, systemPrompt, turns)
            BrainType.GEMINI -> callGemini(settings, systemPrompt, turns, screenshotBase64)
        }
    }

    private suspend fun callGroq(
        settings: BabySettings,
        systemPrompt: String,
        turns: List<AgentStepTurn>
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

        val tools = ToolDefinitions.toGroqTools()
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
                    toolChoice = "auto"
                )
            },
            sleeper = sleeper,
            jitterRatioProvider = jitterRatioProvider
        )

        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Groq request fail"))
        }

        val attempt = res.getOrThrow()
        val body = attempt.value
        val msg = body.choices?.firstOrNull()?.message
            ?: return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Groq",
                        model = attempt.modelUsed,
                        userMessage = "Groq ne khali jawab bheja. Dobara try karo.",
                        technicalDetails = "Empty choices in GroqChatResponse (${attempt.modelUsed})"
                    )
                )
            )

        val structuredToolCalls = msg.toolCalls.orEmpty()
            .mapNotNull { tc ->
                val fnName = tc.function?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val rawArgs = tc.function.arguments
                fnName to (tc.id?.trim()?.takeIf { it.isNotEmpty() } to rawArgs)
            }
            .mapIndexed { idx, (fnName, idAndArgs) ->
                PlannedToolCall(
                    id = idAndArgs.first ?: "groq_call_$idx",
                    name = fnName,
                    args = parseToolArgsSafely(idAndArgs.second)
                )
            }

        val toolCalls = if (structuredToolCalls.isNotEmpty()) {
            structuredToolCalls
        } else {
            AiResponseParser.extractInlineToolCalls(msg.content, mapAdapter)
        }

        val replyText = AiResponseParser.stripReasoning(msg.content)
        if (replyText == null && toolCalls.isEmpty()) {
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Groq",
                        model = attempt.modelUsed,
                        userMessage = "Groq model (${attempt.modelUsed}) ne koi useful jawab nahi diya. Retry karo ya model badlo.",
                        technicalDetails = "Groq returned empty content & zero tool calls (${attempt.modelUsed})"
                    )
                )
            )
        }

        return Result.success(
            AiPlanResult(
                brainUsed = BrainType.GROQ,
                replyText = replyText,
                toolCalls = toolCalls,
                modelSwitchedNotice = attempt.modelSwitchedNotice
            )
        )
    }

    private suspend fun callGemini(
        settings: BabySettings,
        systemPrompt: String,
        turns: List<AgentStepTurn>,
        latestScreenshotBase64: String?
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
                                    args = turn.toolArgs ?: emptyMap()
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

        val res = AiResilienceHelper.executeGeminiWithRetry(
            geminiApi = geminiApi,
            key = key,
            model = modelId,
            request = GeminiGenerateRequest(
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
                contents = contents,
                tools = ToolDefinitions.toGeminiTools()
            ),
            sleeper = sleeper,
            jitterRatioProvider = jitterRatioProvider
        )

        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Gemini request fail"))
        }

        val body = res.getOrThrow()
        body.error?.let { errBody ->
            if (!errBody.message.isNullOrBlank()) {
                val clean = SensitiveDataSanitizer.sanitizeError(errBody.message, key)
                return Result.failure(
                    AiCallException(
                        AiFailure(
                            provider = "Gemini",
                            model = modelId,
                            httpCode = errBody.code,
                            errorCode = errBody.status,
                            userMessage = "Gemini error (${errBody.code ?: "API"}): Settings > AI Models ya API Key check karo.",
                            technicalDetails = "Provider: Gemini | Model: $modelId | Reason: $clean"
                        )
                    )
                )
            }
        }

        val firstCandidate = body.candidates?.firstOrNull()
            ?: return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Gemini",
                        model = modelId,
                        userMessage = "Gemini ne khali jawab bheja. Dobara try karo.",
                        technicalDetails = "Empty candidates in GeminiGenerateResponse ($modelId)"
                    )
                )
            )
        val parts = firstCandidate.content?.parts.orEmpty()
        val visibleReply = AiResilienceHelper.extractGeminiVisibleText(parts)
        val calls = parts.mapNotNull { it.functionCall }
            .filter { it.name.isNotBlank() }
            .mapIndexed { index, fc ->
                PlannedToolCall(
                    id = "gem_call_$index",
                    name = fc.name.trim(),
                    args = fc.args ?: emptyMap()
                )
            }

        if (visibleReply == null && calls.isEmpty()) {
            val reason = firstCandidate.finishReason ?: "EMPTY"
            return Result.failure(
                AiCallException(
                    AiFailure(
                        provider = "Gemini",
                        model = modelId,
                        userMessage = "Gemini model ($modelId) ne jawab nahi diya ($reason).",
                        technicalDetails = "Provider: Gemini | Model: $modelId | Finish: $reason"
                    )
                )
            )
        }

        return Result.success(
            AiPlanResult(
                brainUsed = BrainType.GEMINI,
                replyText = visibleReply,
                toolCalls = calls
            )
        )
    }

    internal fun parseToolArgsSafely(rawJson: String?): Map<String, Any?> {
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
            appendLine("Your name is Baby. You are a friendly Hinglish-speaking phone assistant. Reply in short, natural language. Use tools to act on the phone. Ask confirmation before sensitive actions.")
            appendLine("IDENTITY & PERSONALITY:")
            appendLine("- Always introduce yourself as Baby. NEVER mention Gemini, Llama, Groq, GPT-OSS, Qwen, Google, OpenAI, or any underlying model or API name.")
            appendLine("- Personality style: ${settings.personality.toneInstruction}")
            appendLine("- $langGuide")
            appendLine(
                if (settings.dataSaverEnabled) {
                    "- DATA SAVER MODE ACTIVE: Reply in at most 1-2 ultra-short sentences. Make minimum tool calls and NEVER call take_screenshot or request screenshots."
                } else {
                    "- Keep replies concise (1-3 short sentences), warm, and actionable."
                }
            )
            if (!memoryBlock.isNullOrBlank()) {
                appendLine("RELEVANT SAVED MEMORIES (top matches):")
                appendLine(memoryBlock)
            }
            appendLine("AGENT LOOP (Step ${stepIndex + 1}/15):")
            appendLine("- Observe -> Plan -> Act -> Verify. Use read_screen or take_screenshot when interacting with UI apps before tapping coordinates.")
            appendLine("- SCREEN HELP: When user asks 'isko translate karo', 'isko summarize karo', or 'ye kya hai' about what is on the phone screen, ALWAYS call explain_screen(mode) so Baby reads the text tree first and uses screenshot only if needed (never if Data Saver is ON).")
            appendLine("- CAMERA + VISION: When user points the camera or asks 'ye kya hai?' about a physical object / photo ('camera se dekho', 'mere samne ye kya hai', or when on Baby screen), call capture_and_describe(question).")
            appendLine("- CLIPBOARD AI: When user asks to summarize, translate, or draft a reply for copied text/clipboard, call read_clipboard_and_process(mode).")
            appendLine("- REMINDERS & PLACES: Use set_reminder (natural time or location), list_reminders, delete_reminder, snooze_reminder, or save_place (Home/Work).")
            appendLine("- NOTIFICATIONS: Use summarize_notifications(scope) for Hinglish grouped summaries (people first, promos last).")
            appendLine("- CUSTOM COMMANDS: When user says 'naya command banao...' (e.g. 'Movie mode -> DND on, brightness low, volume 40%, open Netflix'), call create_command. Use run_command, list_commands, delete_command as needed.")
            appendLine("- MACRO RECORDING: When user says 'Macro record shuru karo', call start_macro_recording. When 'Macro save karo [name]', call stop_macro_recording. Replay with run_macro, list with list_macros, delete with delete_macro.")
            appendLine("- SAFETY: NEVER automate banking, UPI payment, password manager, or system security settings screens. Always use send_emergency_alert on 'Baby help'.")
            appendLine("- If user wants to create a routine by voice (e.g. 'routine banao: jab main bolu ...'), call create_routine with non-sensitive steps only. Never put payments, delete, uninstall, or SMS/WhatsApp sends inside routines.")
            appendLine("- Resolve nicknames (e.g. Mummy, Bhaiya) using RELEVANT SAVED MEMORIES or recall_memory; save preferences/facts/nicknames with save_memory(type, key, value) and remove with delete_memory(key).")
            appendLine("- Device lock state: ${if (isLocked) "LOCKED (only non-UI tools like call_contact, set_alarm, set_timer, toggle_flashlight, set_volume, media_control, read_notifications, summarize_notifications, set_reminder, send_emergency_alert, speak can run; UI tools will be queued until unlock)" else "UNLOCKED"}.")
            if (!screenContext.isNullOrBlank()) {
                appendLine("CURRENT SCREEN UI TREE:")
                appendLine(screenContext.take(if (settings.dataSaverEnabled) 1400 else 2600))
            }
        }
    }
}
