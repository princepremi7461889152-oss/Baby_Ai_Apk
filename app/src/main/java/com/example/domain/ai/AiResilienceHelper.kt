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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.Response
import kotlin.random.Random

data class ResilientAttemptResult<T>(
    val value: T,
    val modelUsed: String,
    val modelSwitchedNotice: String? = null
)

/**
 * Shared resilience engine used by both AiRouter and feature managers:
 * - Exponential backoff (1s, 2s, 4s, max 3 retries) with jitter for 429/500/502/503/504 & timeouts
 * - Fail-fast (no backoff) for 400/401/403/404 and offline network errors
 * - Groq 404 model_not_found auto-recovery using live GET openai/v1/models
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
                    val base = RETRY_BACKOFF_DELAYS_MS[attemptIndex]
                    val ratio = jitterRatioProvider().coerceIn(0.0, 0.5)
                    val waitMs = (base + (base * ratio).toLong()).coerceAtLeast(0L)
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
                    val base = RETRY_BACKOFF_DELAYS_MS[attemptIndex]
                    val ratio = jitterRatioProvider().coerceIn(0.0, 0.5)
                    val waitMs = (base + (base * ratio).toLong()).coerceAtLeast(0L)
                    attemptIndex++
                    sleeper(waitMs)
                    continue
                }
                return Result.failure(classified)
            }
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
                    AiFailureClassifier.fromHttp(
                        provider = "Groq",
                        model = "models",
                        code = resp.code(),
                        rawErrorBody = rawErr,
                        attempts = 1,
                        secretKeys = arrayOf(key)
                    )
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
            Result.failure(
                AiFailureClassifier.fromException(
                    provider = "Groq",
                    model = "models",
                    err = t,
                    attempts = 1,
                    secretKeys = arrayOf(key)
                )
            )
        }
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
                    failedModel = initialModel,
                    reason = "Live model list nahi mil payi ($cleanFetch)",
                    origDetails = firstEx.failure.technicalDetails
                )
            )
        }

        val replacement = AiModelConfig.pickBestReplacement(liveIds, initialModel, role)
            ?: return Result.failure(
                modelRecoveryExhaustedException(
                    failedModel = initialModel,
                    reason = "Live list mein koi compatible Groq replacement model nahi mila",
                    origDetails = firstEx.failure.technicalDetails
                )
            )

        // Persist the replacement model immediately, then retry once
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
        failedModel: String,
        reason: String,
        origDetails: String? = null
    ): AiCallException {
        val msg = "Groq model '$failedModel' kaam nahi kar raha. Settings > AI Models mein jakar model badlo."
        val details = buildString {
            append("Failed model: $failedModel")
            if (reason.isNotBlank()) append(" | $reason")
            if (!origDetails.isNullOrBlank()) append(" | $origDetails")
        }
        return AiCallException(
            AiFailure(
                provider = "Groq",
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
