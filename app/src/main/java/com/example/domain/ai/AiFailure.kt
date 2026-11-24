package com.example.domain.ai

import com.example.core.security.SensitiveDataSanitizer
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class AiFailure(
    val provider: String,
    val model: String,
    val httpCode: Int? = null,
    val errorCode: String? = null,
    val isRetryable: Boolean = false,
    val isModelNotFound: Boolean = false,
    val isOffline: Boolean = false,
    val isValidationFailure: Boolean = false,
    val userMessage: String,
    val technicalDetails: String
)

class AiCallException(
    val failure: AiFailure,
    cause: Throwable? = null
) : IllegalStateException(failure.userMessage, cause)

object AiFailureClassifier {

    private val RETRYABLE_HTTP_CODES = setOf(429, 500, 502, 503, 504)
    private val NON_RETRYABLE_HTTP_CODES = setOf(400, 401, 403, 404)

    fun extractCleanErrorMessageAndCode(rawBody: String?, vararg secretKeys: String): Pair<String?, String?> {
        if (rawBody.isNullOrBlank()) return null to null
        val trimmed = rawBody.trim()
        val parsed = runCatching {
            val obj = JSONObject(trimmed)
            val errObj = obj.optJSONObject("error")
            if (errObj != null) {
                val msg = errObj.optString("message", "").trim().takeIf { it.isNotEmpty() }
                val code = errObj.optString("code", "").trim()
                    .ifBlank { errObj.optString("status", "").trim() }
                    .ifBlank { errObj.optString("type", "").trim() }
                    .takeIf { it.isNotEmpty() }
                msg to code
            } else {
                val msg = obj.optString("message", "").trim().takeIf { it.isNotEmpty() }
                msg to null
            }
        }.getOrNull()

        val rawMsg = parsed?.first ?: if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) trimmed else null
        val cleanMsg = rawMsg?.let { SensitiveDataSanitizer.sanitizeError(it, *secretKeys).take(220) }
        val cleanCode = parsed?.second?.take(64)
        return cleanMsg to cleanCode
    }

    fun fromHttp(
        provider: String,
        model: String,
        code: Int,
        rawErrorBody: String?,
        attempts: Int = 1,
        vararg secretKeys: String
    ): AiCallException {
        val (cleanReason, errCode) = extractCleanErrorMessageAndCode(rawErrorBody, *secretKeys)
        val lowerReason = cleanReason.orEmpty().lowercase()
        val lowerCode = errCode.orEmpty().lowercase()

        val isModelMissing = code == 404 ||
            lowerCode == "model_not_found" ||
            lowerCode == "model_decommissioned" ||
            lowerReason.contains("does not exist") ||
            lowerReason.contains("decommissioned") ||
            lowerReason.contains("model not found")

        val isRetryable = !isModelMissing && (code in RETRYABLE_HTTP_CODES)

        val friendlyMsg = when {
            isModelMissing ->
                "$provider model '$model' nahi mila ya band ho gaya hai (404). Settings > AI Models mein jakar model badlo."
            code == 401 ->
                "$provider API key galat ya expire ho gayi hai (401). Settings > API Keys mein key check karo."
            code == 403 ->
                "$provider ko '$model' ya is API key par access nahi hai (403). Settings > API Keys check karo."
            code == 400 ->
                "$provider ne request accept nahi ki (400). Settings > AI Models mein model naam check karo."
            code == 429 ->
                "$provider ki rate limit ya quota abhi full hai (429). Thodi der mein Retry karo ya model badlo."
            code in 500..599 ->
                "$provider server abhi busy ya down hai (HTTP $code). Thodi der baad Retry karo."
            else ->
                "$provider request fail ho gayi (HTTP $code). Settings > AI Models ya network check karo."
        }

        val detailParts = buildList {
            add("Provider: $provider")
            if (model.isNotBlank()) add("Model: $model")
            add("HTTP: $code")
            if (!errCode.isNullOrBlank()) add("Code: $errCode")
            if (attempts > 1) add("Attempts: $attempts")
            if (!cleanReason.isNullOrBlank()) add("Reason: $cleanReason")
        }

        return AiCallException(
            AiFailure(
                provider = provider,
                model = model,
                httpCode = code,
                errorCode = errCode,
                isRetryable = isRetryable,
                isModelNotFound = isModelMissing,
                isOffline = false,
                userMessage = friendlyMsg,
                technicalDetails = detailParts.joinToString(" | ")
            )
        )
    }

    fun fromException(
        provider: String,
        model: String,
        err: Throwable,
        attempts: Int = 1,
        vararg secretKeys: String
    ): AiCallException {
        if (err is AiCallException) return err
        val isOffline = err is UnknownHostException || err is ConnectException
        val isTimeout = err is SocketTimeoutException ||
            (err is InterruptedIOException && !isOffline)
        val cleanReason = SensitiveDataSanitizer.sanitizeError(err.message, *secretKeys).take(180)

        val friendlyMsg = when {
            isOffline ->
                "$provider tak internet connection nahi pahunch raha. Offline commands (flashlight, volume, alarm, open app) bina internet chalenge."
            isTimeout ->
                "$provider se jawab aane mein zyada time lag gaya (timeout). Thodi der mein Retry karo."
            err is IOException ->
                "$provider se network connect karne mein problem aayi. Network check karke Retry karo."
            else ->
                "$provider request complete nahi ho payi. Settings > AI Models check karke Retry karo."
        }

        val detailParts = buildList {
            add("Provider: $provider")
            if (model.isNotBlank()) add("Model: $model")
            add("Error: ${err::class.java.simpleName}")
            if (attempts > 1) add("Attempts: $attempts")
            if (cleanReason.isNotBlank() && cleanReason != "Unknown error") add("Reason: $cleanReason")
        }

        return AiCallException(
            AiFailure(
                provider = provider,
                model = model,
                httpCode = null,
                errorCode = null,
                isRetryable = isTimeout,
                isModelNotFound = false,
                isOffline = isOffline,
                userMessage = friendlyMsg,
                technicalDetails = detailParts.joinToString(" | ")
            ),
            cause = err
        )
    }

    fun isNonRetryableCode(code: Int): Boolean = code in NON_RETRYABLE_HTTP_CODES
}
