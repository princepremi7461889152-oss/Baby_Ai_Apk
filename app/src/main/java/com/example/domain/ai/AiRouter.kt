package com.example.domain.ai

import com.example.core.security.BabySettings
import com.example.core.security.PreferredBrain
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiFunctionResponse
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiInlineData
import com.example.data.remote.GeminiPart
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqMessage
import com.example.domain.tools.ToolDefinitions
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

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
    val fallbackNote: String? = null
)

data class WhisperTranscriptResult(
    val text: String,
    val detectedLanguage: String?
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

    suspend fun transcribeAudio(wavBytes: ByteArray): Result<WhisperTranscriptResult> {
        val settings = keyStore.settings.value
        val groqKey = settings.groqApiKey.trim()
        if (groqKey.isBlank()) {
            return Result.failure(IllegalStateException("Groq API key missing for Whisper speech-to-text."))
        }
        return try {
            val filePart = MultipartBody.Part.createFormData(
                "file",
                "voice.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            val modelBody = settings.groqWhisperModel.toRequestBody("text/plain".toMediaType())
            val formatBody = "verbose_json".toRequestBody("text/plain".toMediaType())
            val resp = groqApi.transcribeAudio(
                bearerAuth = "Bearer $groqKey",
                file = filePart,
                model = modelBody,
                responseFormat = formatBody
            )
            if (resp.isSuccessful && resp.body() != null) {
                val body = resp.body()!!
                Result.success(WhisperTranscriptResult(body.text.trim(), body.language))
            } else {
                val errBody = runCatching { resp.errorBody()?.string()?.take(200) }.getOrNull()
                Result.failure(classifyHttpFailure("Whisper", resp.code(), errBody, groqKey))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(classifyNetworkFailure("Whisper", e, groqKey))
        }
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
                IllegalStateException("No API keys saved. Please add your Groq or Gemini API key in Settings.")
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

        val firstErr = SensitiveDataSanitizer.sanitizeError(
            firstAttempt.exceptionOrNull()?.message ?: "Primary brain error",
            settings.groqApiKey,
            settings.geminiApiKey
        )
        if (settings.autoFallbackEnabled && hasKeyFor(secondary, settings)) {
            val fallbackAttempt = invokeBrain(secondary, settings, systemPrompt, safeTurns, effectiveScreenshot)
            if (fallbackAttempt.isSuccess) {
                val plan = fallbackAttempt.getOrThrow()
                return Result.success(
                    plan.copy(fallbackNote = "Auto-switched from ${primary.label} ($firstErr) to ${secondary.label}")
                )
            }
            val secondErr = SensitiveDataSanitizer.sanitizeError(
                fallbackAttempt.exceptionOrNull()?.message ?: "Fallback error",
                settings.groqApiKey,
                settings.geminiApiKey
            )
            return Result.failure(IllegalStateException("${primary.label} failed ($firstErr); ${secondary.label} failed ($secondErr)"))
        }

        return Result.failure(IllegalStateException(firstErr))
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
        if (key.isBlank()) return Result.failure(IllegalStateException("Groq API key missing"))

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
                                    com.example.data.remote.GroqToolCall(
                                        id = callId,
                                        type = "function",
                                        function = com.example.data.remote.GroqFunctionCallPayload(
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

        return try {
            val resp = groqApi.chatCompletions(
                bearerAuth = "Bearer $key",
                request = GroqChatRequest(
                    model = settings.groqModel,
                    messages = messages,
                    tools = ToolDefinitions.toGroqTools(),
                    toolChoice = "auto"
                )
            )
            if (!resp.isSuccessful || resp.body() == null) {
                val errBody = runCatching { resp.errorBody()?.string()?.take(220) }.getOrNull()
                return Result.failure(classifyHttpFailure("Groq", resp.code(), errBody, key))
            }
            val msg = resp.body()?.choices?.firstOrNull()?.message
                ?: return Result.failure(IllegalStateException("Empty Groq response"))

            val toolCalls = msg.toolCalls.orEmpty()
                .filter { it.function.name.isNotBlank() }
                .mapIndexed { idx, tc ->
                    PlannedToolCall(
                        id = tc.id.ifBlank { "groq_call_$idx" },
                        name = tc.function.name.trim(),
                        args = parseToolArgsSafely(tc.function.arguments)
                    )
                }
            val replyText = msg.content?.trim()?.takeIf { it.isNotEmpty() }
            if (replyText == null && toolCalls.isEmpty()) {
                return Result.failure(IllegalStateException("Groq returned empty output"))
            }
            Result.success(
                AiPlanResult(
                    brainUsed = BrainType.GROQ,
                    replyText = replyText,
                    toolCalls = toolCalls
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(classifyNetworkFailure("Groq", e, key))
        }
    }

    private suspend fun callGemini(
        settings: BabySettings,
        systemPrompt: String,
        turns: List<AgentStepTurn>,
        latestScreenshotBase64: String?
    ): Result<AiPlanResult> {
        val key = settings.geminiApiKey.trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("Gemini API key missing"))

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
                                functionCall = com.example.data.remote.GeminiFunctionCall(
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

        return try {
            val resp = geminiApi.generateContent(
                model = settings.geminiModel,
                apiKey = key,
                request = GeminiGenerateRequest(
                    systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
                    contents = contents,
                    tools = ToolDefinitions.toGeminiTools()
                )
            )
            if (!resp.isSuccessful || resp.body() == null) {
                val errBody = runCatching { resp.errorBody()?.string()?.take(220) }.getOrNull()
                return Result.failure(classifyHttpFailure("Gemini", resp.code(), errBody, key))
            }
            val body = resp.body()!!
            body.error?.message?.takeIf { it.isNotBlank() }?.let { apiErr ->
                return Result.failure(
                    IllegalStateException("Gemini API error: ${SensitiveDataSanitizer.sanitizeError(apiErr, key)}")
                )
            }
            val firstCandidate = body.candidates?.firstOrNull()
                ?: return Result.failure(IllegalStateException("Empty Gemini response"))
            val parts = firstCandidate.content?.parts.orEmpty()
            val textParts = parts.mapNotNull { it.text?.trim() }.filter { it.isNotEmpty() }
            val calls = parts.mapNotNull { it.functionCall }
                .filter { it.name.isNotBlank() }
                .mapIndexed { index, fc ->
                    PlannedToolCall(
                        id = "gem_call_$index",
                        name = fc.name.trim(),
                        args = fc.args ?: emptyMap()
                    )
                }
            if (textParts.isEmpty() && calls.isEmpty()) {
                val reason = firstCandidate.finishReason ?: "EMPTY"
                return Result.failure(IllegalStateException("Gemini returned no content ($reason)"))
            }
            Result.success(
                AiPlanResult(
                    brainUsed = BrainType.GEMINI,
                    replyText = textParts.joinToString("\n").takeIf { it.isNotBlank() },
                    toolCalls = calls
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(classifyNetworkFailure("Gemini", e, key))
        }
    }

    internal fun parseToolArgsSafely(rawJson: String?): Map<String, Any?> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        val cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonCandidate = if (cleaned.startsWith("{")) {
            cleaned
        } else {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) cleaned.substring(start, end + 1) else return emptyMap()
        }
        return runCatching {
            mapAdapter.fromJson(jsonCandidate).orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun classifyHttpFailure(
        provider: String,
        code: Int,
        rawError: String?,
        secretKey: String
    ): IllegalStateException {
        val cleanErr = SensitiveDataSanitizer.sanitizeError(rawError, secretKey).take(160)
        val msg = when (code) {
            400 -> "$provider rejected request (HTTP 400): $cleanErr"
            401, 403 -> "$provider API key invalid or unauthorized (HTTP $code)."
            429 -> "$provider rate limit exceeded (HTTP 429). Switching or retry shortly."
            in 500..599 -> "$provider service temporarily unavailable (HTTP $code)."
            else -> "$provider HTTP $code: $cleanErr"
        }
        return IllegalStateException(msg)
    }

    private fun classifyNetworkFailure(
        provider: String,
        err: Exception,
        secretKey: String
    ): IllegalStateException {
        val msg = when (err) {
            is SocketTimeoutException -> "$provider request timed out."
            is UnknownHostException, is ConnectException -> "No internet connection while contacting $provider."
            is IOException -> "$provider network error: ${SensitiveDataSanitizer.sanitizeError(err.message, secretKey)}"
            else -> SensitiveDataSanitizer.sanitizeError(err.message, secretKey)
        }
        return IllegalStateException(msg, err)
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
            appendLine("- Always introduce yourself as Baby. NEVER mention Gemini, Llama, Groq, Google, Meta, or any underlying model or API name.")
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
