package com.example.ui.state

import com.example.core.security.AiModelConfig
import com.example.core.security.SensitiveDataSanitizer
import com.example.domain.ai.AiFailureClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface KeyTestResult {
    data object Idle : KeyTestResult
    data object Testing : KeyTestResult
    data class Valid(val message: String) : KeyTestResult
    data class Invalid(val message: String) : KeyTestResult
}

data class ModelTestItem(
    val label: String,
    val modelId: String,
    val passed: Boolean?,
    val detail: String
)

data class ModelsTestUiState(
    val isTesting: Boolean = false,
    val items: List<ModelTestItem> = emptyList()
)

@Singleton
class ApiKeyHealthTester @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    internal var groqBaseUrl: String = "https://api.groq.com"
    internal var geminiBaseUrl: String = "https://generativelanguage.googleapis.com"

    suspend fun testGroqKey(rawKey: String): KeyTestResult = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        if (key.isBlank()) {
            return@withContext KeyTestResult.Invalid("Groq key khali hai")
        }
        try {
            val request = Request.Builder()
                .url("${groqBaseUrl.trimEnd('/')}/openai/v1/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> KeyTestResult.Valid("Groq key verified (HTTP ${resp.code})")
                    resp.code == 401 || resp.code == 403 -> KeyTestResult.Invalid("Invalid Groq API key (${resp.code})")
                    resp.code == 429 -> KeyTestResult.Valid("Key valid, rate limit active (429)")
                    else -> KeyTestResult.Invalid("Groq check failed (HTTP ${resp.code})")
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val msg = when (e) {
                is SocketTimeoutException -> "Connection timed out"
                is UnknownHostException -> "Offline / No internet"
                else -> SensitiveDataSanitizer.sanitizeError(e.localizedMessage, key)
            }
            KeyTestResult.Invalid("Network error: $msg")
        }
    }

    suspend fun testGeminiKey(rawKey: String): KeyTestResult = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        if (key.isBlank()) {
            return@withContext KeyTestResult.Invalid("Gemini key khali hai")
        }
        try {
            val request = Request.Builder()
                .url("${geminiBaseUrl.trimEnd('/')}/v1beta/models?pageSize=1")
                .header("x-goog-api-key", key)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> KeyTestResult.Valid("Gemini key verified (HTTP ${resp.code})")
                    resp.code == 400 || resp.code == 401 || resp.code == 403 ->
                        KeyTestResult.Invalid("Invalid Gemini API key (${resp.code})")
                    resp.code == 429 -> KeyTestResult.Valid("Key valid, quota limit hit (429)")
                    else -> KeyTestResult.Invalid("Gemini check failed (HTTP ${resp.code})")
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val msg = when (e) {
                is SocketTimeoutException -> "Connection timed out"
                is UnknownHostException -> "Offline / No internet"
                else -> SensitiveDataSanitizer.sanitizeError(e.localizedMessage, key)
            }
            KeyTestResult.Invalid("Network error: $msg")
        }
    }

    suspend fun fetchGroqModels(rawKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Groq API key missing hai."))
        }
        try {
            val request = Request.Builder()
                .url("${groqBaseUrl.trimEnd('/')}/openai/v1/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                val rawBody = runCatching { resp.body?.string() }.getOrNull()
                if (!resp.isSuccessful || rawBody.isNullOrBlank()) {
                    val ex = AiFailureClassifier.fromHttp(
                        provider = "Groq",
                        model = "models",
                        code = resp.code,
                        rawErrorBody = rawBody,
                        attempts = 1,
                        secretKeys = arrayOf(key)
                    )
                    return@withContext Result.failure(ex)
                }
                val root = JSONObject(rawBody)
                val arr = root.optJSONArray("data") ?: JSONArray()
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val active = if (item.has("active") && !item.isNull("active")) item.optBoolean("active", true) else true
                    val id = item.optString("id", "").trim()
                    if (active && id.isNotEmpty()) ids.add(id)
                }
                Result.success(ids.distinct().sorted())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(
                AiFailureClassifier.fromException(
                    provider = "Groq",
                    model = "models",
                    err = e,
                    attempts = 1,
                    secretKeys = arrayOf(key)
                )
            )
        }
    }

    suspend fun testAllModels(
        groqKey: String,
        geminiKey: String,
        config: AiModelConfig
    ): ModelsTestUiState = withContext(Dispatchers.IO) {
        val norm = config.normalized()
        val gKey = groqKey.trim()
        val gemKey = geminiKey.trim()

        val items = listOf(
            testGroqChatModel("Chat / Tools", norm.chatModel, gKey),
            testGroqChatModel("Fast / Simple", norm.fastModel, gKey),
            testGroqSttModel("Speech-to-Text", norm.sttModel, gKey),
            testGeminiModel("Gemini Flash", norm.geminiModel, gemKey)
        )
        ModelsTestUiState(isTesting = false, items = items)
    }

    private fun testGroqChatModel(label: String, modelId: String, key: String): ModelTestItem {
        if (key.isBlank()) {
            return ModelTestItem(label, modelId, passed = false, detail = "Groq API key missing")
        }
        return try {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", 12)
                put("temperature", 0.0)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", "Reply OK")
                        }
                    )
                )
            }.toString()
            val req = Request.Builder()
                .url("${groqBaseUrl.trimEnd('/')}/openai/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val body = runCatching { resp.body?.string() }.getOrNull()
                if (resp.isSuccessful) {
                    ModelTestItem(label, modelId, passed = true, detail = "Pass (HTTP ${resp.code})")
                } else {
                    val (reason, _) = AiFailureClassifier.extractCleanErrorMessageAndCode(body, key)
                    ModelTestItem(
                        label,
                        modelId,
                        passed = false,
                        detail = "Fail (HTTP ${resp.code}${if (!reason.isNullOrBlank()) ": ${reason.take(80)}" else ""})"
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val clean = SensitiveDataSanitizer.sanitizeError(e.message, key).take(80)
            ModelTestItem(label, modelId, passed = false, detail = "Fail ($clean)")
        }
    }

    private fun testGroqSttModel(label: String, modelId: String, key: String): ModelTestItem {
        if (key.isBlank()) {
            return ModelTestItem(label, modelId, passed = false, detail = "Groq API key missing")
        }
        return try {
            val tinyWav = buildMinimalSilentWav(durationMs = 150)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "ping.wav", tinyWav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", modelId)
                .addFormDataPart("response_format", "json")
                .build()
            val req = Request.Builder()
                .url("${groqBaseUrl.trimEnd('/')}/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $key")
                .post(multipart)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val body = runCatching { resp.body?.string() }.getOrNull()
                if (resp.isSuccessful) {
                    ModelTestItem(label, modelId, passed = true, detail = "Pass (HTTP ${resp.code})")
                } else {
                    val (reason, _) = AiFailureClassifier.extractCleanErrorMessageAndCode(body, key)
                    ModelTestItem(
                        label,
                        modelId,
                        passed = false,
                        detail = "Fail (HTTP ${resp.code}${if (!reason.isNullOrBlank()) ": ${reason.take(80)}" else ""})"
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val clean = SensitiveDataSanitizer.sanitizeError(e.message, key).take(80)
            ModelTestItem(label, modelId, passed = false, detail = "Fail ($clean)")
        }
    }

    private fun testGeminiModel(label: String, modelId: String, key: String): ModelTestItem {
        if (key.isBlank()) {
            return ModelTestItem(label, modelId, passed = false, detail = "Gemini API key missing")
        }
        return try {
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "parts",
                                JSONArray().put(JSONObject().apply { put("text", "Reply OK") })
                            )
                        }
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("maxOutputTokens", 12)
                        put("temperature", 0.0)
                    }
                )
            }.toString()
            val req = Request.Builder()
                .url("${geminiBaseUrl.trimEnd('/')}/v1beta/models/${modelId.trim()}:generateContent")
                .header("x-goog-api-key", key)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val body = runCatching { resp.body?.string() }.getOrNull()
                if (resp.isSuccessful) {
                    ModelTestItem(label, modelId, passed = true, detail = "Pass (HTTP ${resp.code})")
                } else {
                    val (reason, _) = AiFailureClassifier.extractCleanErrorMessageAndCode(body, key)
                    ModelTestItem(
                        label,
                        modelId,
                        passed = false,
                        detail = "Fail (HTTP ${resp.code}${if (!reason.isNullOrBlank()) ": ${reason.take(80)}" else ""})"
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val clean = SensitiveDataSanitizer.sanitizeError(e.message, key).take(80)
            ModelTestItem(label, modelId, passed = false, detail = "Fail ($clean)")
        }
    }

    private fun buildMinimalSilentWav(sampleRate: Int = 16000, durationMs: Int = 150): ByteArray {
        val numSamples = (sampleRate * durationMs) / 1000
        val dataSize = numSamples * 2
        val byteRate = sampleRate * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        writeAscii("RIFF")
        writeIntLE(36 + dataSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(2)
        writeShortLE(16)
        writeAscii("data")
        writeIntLE(dataSize)
        out.write(ByteArray(dataSize))
        return out.toByteArray()
    }
}
