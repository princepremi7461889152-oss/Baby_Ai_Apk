package com.example.domain.ai

import com.example.core.security.AiModelConfig
import com.example.core.security.GroqModelRole
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiGenerateResponse
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqChatResponse
import com.example.data.remote.GroqChoice
import com.example.data.remote.GroqFunctionCallPayload
import com.example.data.remote.GroqMessage
import com.example.data.remote.GroqToolCall
import com.example.data.remote.MercuryApiService
import com.example.data.remote.MercuryChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.BufferedSource
import org.json.JSONObject
import retrofit2.Response
import kotlin.random.Random

data class ResilientAttemptResult<T>(
    val value: T,
    val modelUsed: String,
    val modelSwitchedNotice: String? = null
)

/**
 * Shared resilience engine across Mercury 3 (Inception), Gemini 3.8 Flash, and Groq:
 * - Exponential backoff (1s, 2s, 4s, max 3 retries) with jitter for 429/500/502/503/504 & timeouts
 * - Fail-fast (no backoff) for 400/401/403/404 and offline network errors
 * - 404 model_not_found auto-recovery via live /models endpoint + persistence + notice
 * - OpenAI-compatible SSE streaming support for Mercury 3 token-by-token responses
 */
object AiResilienceHelper {

    val RETRY_BACKOFF_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

    suspend fun <T> executeWithRetry(
        provider: String,
        model: String,
        secretKey: String,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        jitterRatioProvider: () -> Double = { Random.nextDouble(0.0, 0.25) },
        call: suspend () -> Response<T>
    ): Result<T> {
        var attemptIndex = 0
        while (true) {
            try {
                val resp = call()
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    return Result.success(body)
                }
                val rawErr = runCatching { resp.errorBody()?.string()?.take(600) }.getOrNull()
                val httpCode = if (resp.isSuccessful) 502 else resp.code()
                val classified = AiFailureClassifier.fromHttp(
                    provider = provider,
                    model = model,
                    code = httpCode,
                    rawErrorBody = rawErr,
                    attempts = attemptIndex + 1,
                    secretKeys = arrayOf(secretKey)
                )
                if (classified.failure.isRetryable && attemptIndex < RETRY_BACKOFF_DELAYS_MS.size) {
                    val waitMs = computeBackoffDelay(attemptIndex, jitterRatioProvider)
                    attemptIndex++
                    sleeper(waitMs)
                    continue
                }
                return Result.failure(classified)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val classified = AiFailureClassifier.fromException(
                    provider = provider,
                    model = model,
                    err = t,
                    attempts = attemptIndex + 1,
                    secretKeys = arrayOf(secretKey)
                )
                if (classified.failure.isRetryable && attemptIndex < RETRY_BACKOFF_DELAYS_MS.size) {
                    val waitMs = computeBackoffDelay(attemptIndex, jitterRatioProvider)
                    attemptIndex++
                    sleeper(waitMs)
                    continue
                }
                return Result.failure(classified)
            }
        }
    }

    private fun computeBackoffDelay(attemptIndex: Int, jitterRatioProvider: () -> Double): Long {
        val base = RETRY_BACKOFF_DELAYS_MS[attemptIndex]
        val ratio = jitterRatioProvider().coerceIn(0.0, 0.5)
        return (base + (base * ratio).toLong()).coerceAtLeast(0L)
    }

    suspend fun fetchLiveMercuryModels(
        mercuryApi: MercuryApiService,
        baseUrl: String,
        rawKey: String
    ): Result<List<String>> {
        val key = rawKey.trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("Inception API key missing."))
        val url = "${baseUrl.trimEnd('/')}/models"
        return try {
            val resp = mercuryApi.listModels(url, "Bearer $key")
            if (!resp.isSuccessful || resp.body() == null) {
                val rawErr = runCatching { resp.errorBody()?.string()?.take(500) }.getOrNull()
                return Result.failure(
                    AiFailureClassifier.fromHttp("Mercury", "models", resp.code(), rawErr, 1, key)
                )
            }
            val ids = resp.body()?.data.orEmpty()
                .mapNotNull { it.id?.trim()?.takeIf { id -> id.isNotEmpty() } }
                .distinct()
            Result.success(ids)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(AiFailureClassifier.fromException("Mercury", "models", t, 1, key))
        }
    }

    suspend fun fetchLiveGeminiModels(
        geminiApi: GeminiApiService,
        rawKey: String
    ): Result<List<String>> {
        val key = rawKey.trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("Gemini API key missing."))
        return try {
            val resp = geminiApi.listModels(apiKey = key)
            if (!resp.isSuccessful || resp.body() == null) {
                val rawErr = runCatching { resp.errorBody()?.string()?.take(500) }.getOrNull()
                return Result.failure(
                    AiFailureClassifier.fromHttp("Gemini", "models", resp.code(), rawErr, 1, key)
                )
            }
            val ids = resp.body()?.models.orEmpty()
                .filter { model ->
                    val methods = model.supportedGenerationMethods.orEmpty()
                    methods.isEmpty() || methods.any { it.contains("generateContent", ignoreCase = true) }
                }
                .mapNotNull { it.name?.trim()?.removePrefix("models/")?.takeIf { id -> id.isNotEmpty() } }
                .distinct()
            Result.success(ids)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(AiFailureClassifier.fromException("Gemini", "models", t, 1, key))
        }
    }

    suspend fun fetchLiveGroqModels(
        groqApi: GroqApiService,
        rawKey: String
    ): Result<List<String>> {
        val key = rawKey.trim()
        if (key.isBlank()) {
            return Result.failure(IllegalStateException("Groq API key missing."))
        }
        return try {
            val resp = groqApi.listModels("Bearer $key")
            if (!resp.isSuccessful || resp.body() == null) {
                val rawErr = runCatching { resp.errorBody()?.string()?.take(500) }.getOrNull()
                return Result.failure(
                    AiFailureClassifier.fromHttp("Groq", "models", resp.code(), rawErr, 1, key)
                )
            }
            val ids = resp.body()?.data.orEmpty()
                .filter { it.active != false }
                .mapNotNull { it.id?.trim()?.takeIf { id -> id.isNotEmpty() } }
                .distinct()
            Result.success(ids)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(AiFailureClassifier.fromException("Groq", "models", t, 1, key))
        }
    }

    suspend fun executeMercuryChatWithRecovery(
        mercuryApi: MercuryApiService,
        keyStore: SecureKeyStore,
        key: String,
        baseUrl: String,
        initialModel: String,
        requestBuilder: (String, Boolean) -> MercuryChatRequest,
        streamResponses: Boolean = false,
        onStreamingToken: ((String) -> Unit)? = null,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        jitterRatioProvider: () -> Double = { Random.nextDouble(0.0, 0.25) }
    ): Result<ResilientAttemptResult<GroqChatResponse>> {
        val cleanBase = baseUrl.trimEnd('/')
        val chatUrl = "$cleanBase/chat/completions"

        suspend fun invokeModel(modelId: String): Result<GroqChatResponse> {
            return if (streamResponses) {
                streamMercuryWithRetry(
                    mercuryApi = mercuryApi,
                    chatUrl = chatUrl,
                    key = key,
                    modelId = modelId,
                    request = requestBuilder(modelId, true),
                    onStreamingToken = onStreamingToken,
                    sleeper = sleeper,
                    jitterRatioProvider = jitterRatioProvider
                )
            } else {
                executeWithRetry(
                    provider = "Mercury",
                    model = modelId,
                    secretKey = key,
                    sleeper = sleeper,
                    jitterRatioProvider = jitterRatioProvider
                ) {
                    mercuryApi.chatCompletions(chatUrl, "Bearer $key", requestBuilder(modelId, false))
                }
            }
        }

        val firstRes = invokeModel(initialModel)
        if (firstRes.isSuccess) {
            return Result.success(ResilientAttemptResult(firstRes.getOrThrow(), initialModel))
        }

        val firstEx = firstRes.exceptionOrNull() as? AiCallException
        if (firstEx == null || !firstEx.failure.isModelNotFound) {
            return Result.failure(firstRes.exceptionOrNull() ?: IllegalStateException("Mercury request fail"))
        }

        val liveIds = fetchLiveMercuryModels(mercuryApi, cleanBase, key).getOrElse { fetchErr ->
            val cleanFetch = SensitiveDataSanitizer.sanitizeError(fetchErr.message, key)
            return Result.failure(
                modelRecoveryExhaustedException(
                    provider = "Mercury",
                    failedModel = initialModel,
                    reason = "Live model list nahi mil payi ($cleanFetch)",
                    origDetails = firstEx.failure.technicalDetails
                )
            )
        }

        val replacement = AiModelConfig.pickBestMercuryReplacement(liveIds, initialModel)
            ?: return Result.failure(
                modelRecoveryExhaustedException(
                    provider = "Mercury",
                    failedModel = initialModel,
                    reason = "Live list mein koi compatible Mercury replacement model nahi mila",
                    origDetails = firstEx.failure.technicalDetails
                )
            )

        keyStore.updateMindModel(replacement)

        val retryRes = invokeModel(replacement)
        return retryRes.fold(
            onSuccess = { body ->
                Result.success(
                    ResilientAttemptResult(
                        value = body,
                        modelUsed = replacement,
                        modelSwitchedNotice = "MIND model badal diya: $replacement"
                    )
                )
            },
            onFailure = { retryErr ->
                val cleanRetry = SensitiveDataSanitizer.sanitizeError(retryErr.message, key)
                Result.failure(
                    modelRecoveryExhaustedException(
                        provider = "Mercury",
                        failedModel = "$initialModel -> $replacement",
                        reason = cleanRetry,
                        origDetails = (retryErr as? AiCallException)?.failure?.technicalDetails
                            ?: firstEx.failure.technicalDetails
                    )
                )
            }
        )
    }

    private suspend fun streamMercuryWithRetry(
        mercuryApi: MercuryApiService,
        chatUrl: String,
        key: String,
        modelId: String,
        request: MercuryChatRequest,
        onStreamingToken: ((String) -> Unit)?,
        sleeper: suspend (Long) -> Unit,
        jitterRatioProvider: () -> Double
    ): Result<GroqChatResponse> {
        var attemptIndex = 0
        while (true) {
            try {
                val resp = mercuryApi.chatCompletionsStream(chatUrl, "Bearer $key", request)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    val parsed = body.use { parseOpenAiSseStream(it.source(), onStreamingToken) }
                    return Result.success(parsed)
                }
                val rawErr = runCatching { resp.errorBody()?.string()?.take(600) }.getOrNull()
                val httpCode = if (resp.isSuccessful) 502 else resp.code()
                val classified = AiFailureClassifier.fromHttp(
                    provider = "Mercury",
                    model = modelId,
                    code = httpCode,
                    rawErrorBody = rawErr,
                    attempts = attemptIndex + 1,
                    secretKeys = arrayOf(key)
                )
                if (classified.failure.isRetryable && attemptIndex < RETRY_BACKOFF_DELAYS_MS.size) {
                    val waitMs = computeBackoffDelay(attemptIndex, jitterRatioProvider)
                    attemptIndex++
                    sleeper(waitMs)
                    continue
                }
                return Result.failure(classified)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val classified = AiFailureClassifier.fromException(
                    provider = "Mercury",
                    model = modelId,
                    err = t,
                    attempts = attemptIndex + 1,
                    secretKeys = arrayOf(key)
                )
                if (classified.failure.isRetryable && attemptIndex < RETRY_BACKOFF_DELAYS_MS.size) {
                    val waitMs = computeBackoffDelay(attemptIndex, jitterRatioProvider)
                    attemptIndex++
                    sleeper(waitMs)
                    continue
                }
                return Result.failure(classified)
            }
        }
    }

    internal fun parseOpenAiSseStream(
        source: BufferedSource,
        onStreamingToken: ((String) -> Unit)? = null
    ): GroqChatResponse {
        val contentAcc = StringBuilder()
        var lastCleanStreamed = ""
        val toolCallsByIndex = linkedMapOf<Int, MutableToolCallAcc>()

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]" || payload.isEmpty()) break

            runCatching {
                val root = JSONObject(payload)
                val choices = root.optJSONArray("choices") ?: return@runCatching
                if (choices.length() == 0) return@runCatching
                val first = choices.optJSONObject(0) ?: return@runCatching
                val delta = first.optJSONObject("delta") ?: first.optJSONObject("message") ?: return@runCatching

                val deltaContent = delta.opt("content")?.takeIf { it != JSONObject.NULL }?.toString()
                if (!deltaContent.isNullOrEmpty()) {
                    contentAcc.append(deltaContent)
                    if (onStreamingToken != null) {
                        val cleanedNow = AiResponseParser.stripReasoningForStreaming(contentAcc.toString())
                        if (cleanedNow != lastCleanStreamed) {
                            val deltaToEmit = if (cleanedNow.startsWith(lastCleanStreamed)) {
                                cleanedNow.substring(lastCleanStreamed.length)
                            } else {
                                cleanedNow
                            }
                            lastCleanStreamed = cleanedNow
                            if (deltaToEmit.isNotEmpty()) {
                                onStreamingToken(deltaToEmit)
                            }
                        }
                    }
                }

                val tcArr = delta.optJSONArray("tool_calls")
                if (tcArr != null) {
                    for (i in 0 until tcArr.length()) {
                        val tcObj = tcArr.optJSONObject(i) ?: continue
                        val idx = tcObj.optInt("index", i)
                        val acc = toolCallsByIndex.getOrPut(idx) { MutableToolCallAcc() }
                        ToolCallNormalizer.cleanString(tcObj.opt("id"))?.let { acc.id = it }
                        ToolCallNormalizer.cleanString(tcObj.opt("type"))?.let { acc.type = it }
                        val fn = tcObj.optJSONObject("function")
                        if (fn != null) {
                            ToolCallNormalizer.cleanString(fn.opt("name"))?.let { acc.name += it }
                            val rawArgObj = fn.opt("arguments") ?: fn.opt("parameters") ?: fn.opt("args")
                            val argChunk = rawArgObj
                                ?.takeIf { it != JSONObject.NULL }
                                ?.toString()
                                ?.takeIf { !it.equals("null", ignoreCase = true) }
                            if (!argChunk.isNullOrEmpty()) {
                                acc.arguments.append(argChunk)
                            }
                        } else {
                            ToolCallNormalizer.cleanString(tcObj.opt("name"))?.let { acc.name += it }
                            val rawArgObj = tcObj.opt("arguments") ?: tcObj.opt("args")
                            val argChunk = rawArgObj
                                ?.takeIf { it != JSONObject.NULL }
                                ?.toString()
                                ?.takeIf { !it.equals("null", ignoreCase = true) }
                            if (!argChunk.isNullOrEmpty()) {
                                acc.arguments.append(argChunk)
                            }
                        }
                    }
                }
            }
        }

        val toolCalls = if (toolCallsByIndex.isEmpty()) null else {
            toolCallsByIndex.entries.sortedBy { it.key }.map { (idx, acc) ->
                GroqToolCall(
                    id = acc.id ?: "mercury_call_$idx",
                    type = acc.type ?: "function",
                    function = GroqFunctionCallPayload(
                        name = acc.name.ifBlank { null },
                        arguments = acc.arguments.toString()
                    )
                )
            }
        }

        return GroqChatResponse(
            choices = listOf(
                GroqChoice(
                    message = GroqMessage(
                        role = "assistant",
                        content = contentAcc.toString().ifEmpty { null },
                        toolCalls = toolCalls
                    )
                )
            )
        )
    }

    private class MutableToolCallAcc {
        var id: String? = null
        var type: String? = null
        var name: String = ""
        val arguments = StringBuilder()
    }

    suspend fun executeGroqChatWithRecovery(
        groqApi: GroqApiService,
        keyStore: SecureKeyStore,
        key: String,
        initialModel: String,
        role: GroqModelRole,
        requestBuilder: (String) -> GroqChatRequest,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        jitterRatioProvider: () -> Double = { Random.nextDouble(0.0, 0.25) }
    ): Result<ResilientAttemptResult<GroqChatResponse>> {
        val firstRes = executeWithRetry(
            provider = "Groq",
            model = initialModel,
            secretKey = key,
            sleeper = sleeper,
            jitterRatioProvider = jitterRatioProvider
        ) {
            groqApi.chatCompletions("Bearer $key", requestBuilder(initialModel))
        }

        if (firstRes.isSuccess) {
            return Result.success(ResilientAttemptResult(firstRes.getOrThrow(), initialModel))
        }

        val firstEx = firstRes.exceptionOrNull() as? AiCallException
        if (firstEx == null || !firstEx.failure.isModelNotFound) {
            return Result.failure(firstRes.exceptionOrNull() ?: IllegalStateException("Groq request fail"))
        }

        val liveIds = fetchLiveGroqModels(groqApi, key).getOrElse { fetchErr ->
            val cleanFetch = SensitiveDataSanitizer.sanitizeError(fetchErr.message, key)
            return Result.failure(
                modelRecoveryExhaustedException(
                    provider = "Groq",
                    failedModel = initialModel,
                    reason = "Live model list nahi mil payi ($cleanFetch)",
                    origDetails = firstEx.failure.technicalDetails
                )
            )
        }

        val replacement = AiModelConfig.pickBestReplacement(liveIds, initialModel, role)
            ?: return Result.failure(
                modelRecoveryExhaustedException(
                    provider = "Groq",
                    failedModel = initialModel,
                    reason = "Live list mein koi compatible Groq replacement model nahi mila",
                    origDetails = firstEx.failure.technicalDetails
                )
            )

        saveReplacementModel(keyStore, role, replacement)

        val retryRes = executeWithRetry(
            provider = "Groq",
            model = replacement,
            secretKey = key,
            sleeper = sleeper,
            jitterRatioProvider = jitterRatioProvider
        ) {
            groqApi.chatCompletions("Bearer $key", requestBuilder(replacement))
        }

        return retryRes.fold(
            onSuccess = { body ->
                Result.success(
                    ResilientAttemptResult(
                        value = body,
                        modelUsed = replacement,
                        modelSwitchedNotice = "Model badal diya: $replacement"
                    )
                )
            },
            onFailure = { retryErr ->
                val cleanRetry = SensitiveDataSanitizer.sanitizeError(retryErr.message, key)
                Result.failure(
                    modelRecoveryExhaustedException(
                        provider = "Groq",
                        failedModel = "$initialModel -> $replacement",
                        reason = cleanRetry,
                        origDetails = (retryErr as? AiCallException)?.failure?.technicalDetails
                            ?: firstEx.failure.technicalDetails
                    )
                )
            }
        )
    }

    suspend fun executeGeminiWithRetry(
        geminiApi: GeminiApiService,
        key: String,
        model: String,
        request: GeminiGenerateRequest,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        jitterRatioProvider: () -> Double = { Random.nextDouble(0.0, 0.25) }
    ): Result<GeminiGenerateResponse> {
        return executeWithRetry(
            provider = "Gemini",
            model = model,
            secretKey = key,
            sleeper = sleeper,
            jitterRatioProvider = jitterRatioProvider
        ) {
            geminiApi.generateContent(model = model, apiKey = key, request = request)
        }
    }

    suspend fun executeGeminiWithRecovery(
        geminiApi: GeminiApiService,
        keyStore: SecureKeyStore,
        key: String,
        initialModel: String,
        request: GeminiGenerateRequest,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        jitterRatioProvider: () -> Double = { Random.nextDouble(0.0, 0.25) }
    ): Result<ResilientAttemptResult<GeminiGenerateResponse>> {
        val firstRes = executeGeminiWithRetry(geminiApi, key, initialModel, request, sleeper, jitterRatioProvider)
        if (firstRes.isSuccess) {
            return Result.success(ResilientAttemptResult(firstRes.getOrThrow(), initialModel))
        }
        val firstEx = firstRes.exceptionOrNull() as? AiCallException
        if (firstEx == null || !firstEx.failure.isModelNotFound) {
            return Result.failure(firstRes.exceptionOrNull() ?: IllegalStateException("Gemini request fail"))
        }

        val liveIds = fetchLiveGeminiModels(geminiApi, key).getOrElse { fetchErr ->
            val clean = SensitiveDataSanitizer.sanitizeError(fetchErr.message, key)
            return Result.failure(
                modelRecoveryExhaustedException(
                    provider = "Gemini",
                    failedModel = initialModel,
                    reason = clean,
                    origDetails = firstEx.failure.technicalDetails
                )
            )
        }

        val replacement = AiModelConfig.pickBestGeminiReplacement(liveIds, initialModel)
            ?: return Result.failure(
                modelRecoveryExhaustedException(
                    provider = "Gemini",
                    failedModel = initialModel,
                    reason = "Live Gemini list mein koi compatible replacement nahi mila",
                    origDetails = firstEx.failure.technicalDetails
                )
            )

        keyStore.updateGeminiModel(replacement)
        val retryRes = executeGeminiWithRetry(geminiApi, key, replacement, request, sleeper, jitterRatioProvider)
        return retryRes.fold(
            onSuccess = { body ->
                Result.success(
                    ResilientAttemptResult(
                        value = body,
                        modelUsed = replacement,
                        modelSwitchedNotice = "Gemini model badal diya: $replacement"
                    )
                )
            },
            onFailure = { retryErr ->
                val cleanRetry = SensitiveDataSanitizer.sanitizeError(retryErr.message, key)
                Result.failure(
                    modelRecoveryExhaustedException(
                        provider = "Gemini",
                        failedModel = "$initialModel -> $replacement",
                        reason = cleanRetry,
                        origDetails = (retryErr as? AiCallException)?.failure?.technicalDetails
                            ?: firstEx.failure.technicalDetails
                    )
                )
            }
        )
    }

    fun extractGeminiVisibleText(parts: List<com.example.data.remote.GeminiPart>): String? {
        val joined = parts
            .filter { it.thought != true }
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return AiResponseParser.stripReasoning(joined)
    }

    fun saveReplacementModel(keyStore: SecureKeyStore, role: GroqModelRole, modelId: String) {
        when (role) {
            GroqModelRole.CHAT -> keyStore.updateChatModel(modelId)
            GroqModelRole.FAST -> keyStore.updateFastModel(modelId)
            GroqModelRole.STT -> keyStore.updateSttModel(modelId)
        }
    }

    fun modelRecoveryExhaustedException(
        provider: String = "Groq",
        failedModel: String,
        reason: String,
        origDetails: String? = null
    ): AiCallException {
        val msg = "$provider model '$failedModel' kaam nahi kar raha. Settings > AI Models mein jakar model badlo."
        val details = buildString {
            append("Failed model: $failedModel")
            if (reason.isNotBlank()) append(" | $reason")
            if (!origDetails.isNullOrBlank()) append(" | $origDetails")
        }
        return AiCallException(
            AiFailure(
                provider = provider,
                model = failedModel,
                httpCode = 404,
                errorCode = "model_not_found",
                isRetryable = false,
                isModelNotFound = true,
                userMessage = msg,
                technicalDetails = details
            )
        )
    }
}
