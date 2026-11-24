package com.example.ui.state

import com.example.core.security.AiModelConfig
import com.example.core.security.AiRole
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
    val detail: String,
    val role: AiRole? = null,
    val latencyMs: Long = 0L
)

data class ModelsTestUiState(
    val isTesting: Boolean = false,
    val items: List<ModelTestItem> = emptyList()
)

@Singleton
class ApiKeyHealthTester @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    internal var inceptionBaseUrl: String = AiModelConfig.DEFAULT_INCEPTION_BASE_URL
    internal var groqBaseUrl: String = "https://api.groq.com"
    internal var geminiBaseUrl: String = "https://generativelanguage.googleapis.com"

    suspend fun testInceptionKey(
        rawKey: String,
        baseUrlOverride: String = inceptionBaseUrl
    ): KeyTestResult = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        val base = baseUrlOverride.trim().trimEnd('/').ifBlank { AiModelConfig.DEFAULT_INCEPTION_BASE_URL }
        if (key.isBlank()) {
            return@withContext KeyTestResult.Invalid("Inception API key khali hai")
        }
        try {
            val request = Request.Builder()
                .url("$base/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> KeyTestResult.Valid("Inception Mercury key verified (HTTP ${resp.code})")
                    resp.code == 401 || resp.code == 403 -> KeyTestResult.Invalid("Invalid Inception API key (${resp.code})")
                    resp.code == 429 -> KeyTestResult.Valid("Key valid, rate limit active (429)")
                    else -> KeyTestResult.Invalid("Inception check failed (HTTP ${resp.code})")
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

    suspend fun testGroqKey(
        rawKey: String,
        baseUrlOverride: String? = null
    ): KeyTestResult = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        val base = resolveGroqOpenAiRoot(baseUrlOverride)
        if (key.isBlank()) {
            return@withContext KeyTestResult.Invalid("Groq key khali hai")
        }
        try {
            val request = Request.Builder()
                .url("$base/models")
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

    suspend fun testGeminiKey(
        rawKey: String,
        baseUrlOverride: String? = null
    ): KeyTestResult = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        val base = resolveGeminiV1BetaRoot(baseUrlOverride)
        if (key.isBlank()) {
            return@withContext KeyTestResult.Invalid("Gemini key khali hai")
        }
        try {
            val request = Request.Builder()
                .url("$base/models?pageSize=1")
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

    suspend fun fetchMercuryModels(
        rawKey: String,
        baseUrlOverride: String = inceptionBaseUrl
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        val base = baseUrlOverride.trim().trimEnd('/').ifBlank { AiModelConfig.DEFAULT_INCEPTION_BASE_URL }
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Inception API key missing hai."))
        }
        fetchOpenAiModelIds("$base/models", "Mercury", key)
    }

    suspend fun fetchGroqModels(
        rawKey: String,
        baseUrlOverride: String? = null
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        val base = resolveGroqOpenAiRoot(baseUrlOverride)
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Groq API key missing hai."))
        }
        fetchOpenAiModelIds("$base/models", "Groq", key)
    }

    suspend fun fetchGeminiModels(
        rawKey: String,
        baseUrlOverride: String? = null
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        val base = resolveGeminiV1BetaRoot(baseUrlOverride)
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key missing hai."))
        }
        try {
            val request = Request.Builder()
                .url("$base/models?pageSize=100")
                .header("x-goog-api-key", key)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                val rawBody = runCatching { resp.body?.string() }.getOrNull()
                if (!resp.isSuccessful || rawBody.isNullOrBlank()) {
                    return@withContext Result.failure(
                        AiFailureClassifier.fromHttp("Gemini", "models", resp.code, rawBody, 1, key)
                    )
                }
                val root = JSONObject(rawBody)
                val arr = root.optJSONArray("models") ?: JSONArray()
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val name = item.optString("name", "").removePrefix("models/").trim()
                    if (name.isNotEmpty() && !name.contains("embedding", ignoreCase = true)) {
                        ids.add(name)
                    }
                }
                Result.success(ids.distinct().sorted())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(AiFailureClassifier.fromException("Gemini", "models", e, 1, key))
        }
    }

    private fun fetchOpenAiModelIds(url: String, provider: String, key: String): Result<List<String>> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                val rawBody = runCatching { resp.body?.string() }.getOrNull()
                if (!resp.isSuccessful || rawBody.isNullOrBlank()) {
                    val ex = AiFailureClassifier.fromHttp(
                        provider = provider,
                        model = "models",
                        code = resp.code,
                        rawErrorBody = rawBody,
                        attempts = 1,
                        secretKeys = arrayOf(key)
                    )
                    return Result.failure(ex)
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
                    provider = provider,
                    model = "models",
                    err = e,
                    attempts = 1,
                    secretKeys = arrayOf(key)
                )
            )
        }
    }

    suspend fun testSingleRole(
        role: AiRole,
        config: AiModelConfig,
        inceptionKey: String,
        geminiKey: String,
        groqKey: String
    ): ModelTestItem = withContext(Dispatchers.IO) {
        val norm = config.normalized()
        when (role) {
            AiRole.MIND -> testMercuryChatModel("MIND (Mercury)", norm.mindModel, inceptionKey.trim(), norm.inceptionBaseUrl)
            AiRole.VISION_SEARCH -> testGeminiModel("Vision & Search", norm.geminiModel, geminiKey.trim(), norm.geminiBaseUrl)
            AiRole.STT -> testGroqSttModel("Speech-to-Text", norm.sttModel, groqKey.trim(), norm.groqBaseUrl)
            AiRole.TTS -> testGeminiTtsModel("Voice TTS", norm.ttsModel, geminiKey.trim(), norm.geminiBaseUrl)
            AiRole.FALLBACK_CHAT -> testGroqChatModel("Fallback Chat", norm.chatModel, groqKey.trim(), norm.groqBaseUrl)
        }
    }

    // Backwards-compatible 3-arg overload preserved for existing tests
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

    suspend fun testAllRoles(
        inceptionKey: String,
        groqKey: String,
        geminiKey: String,
        config: AiModelConfig
    ): ModelsTestUiState = withContext(Dispatchers.IO) {
        val norm = config.normalized()
        val items = listOf(
            testMercuryChatModel("MIND (Mercury)", norm.mindModel, inceptionKey.trim(), norm.inceptionBaseUrl),
            testGeminiModel("Vision & Search", norm.geminiModel, geminiKey.trim(), norm.geminiBaseUrl),
            testGroqSttModel("Speech-to-Text", norm.sttModel, groqKey.trim(), norm.groqBaseUrl),
            testGeminiTtsModel("Voice TTS", norm.ttsModel, geminiKey.trim(), norm.geminiBaseUrl),
            testGroqChatModel("Fallback Chat", norm.chatModel, groqKey.trim(), norm.groqBaseUrl),
            testGroqChatModel("Fast / Simple", norm.fastModel, groqKey.trim(), norm.groqBaseUrl)
        )
        ModelsTestUiState(isTesting = false, items = items)
    }

    private fun testMercuryChatModel(
        label: String,
        modelId: String,
        key: String,
        baseUrl: String = inceptionBaseUrl
    ): ModelTestItem {
        if (key.isBlank()) {
            return ModelTestItem(label, modelId, passed = false, detail = "Inception API key missing", role = AiRole.MIND)
        }
        val t0 = System.currentTimeMillis()
        return try {
            val base = baseUrl.trim().trimEnd('/').ifBlank { AiModelConfig.DEFAULT_INCEPTION_BASE_URL }
            val payload = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", 16)
                put("reasoning_effort", "low")
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
                .url("$base/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val body = runCatching { resp.body?.string() }.getOrNull()
                if (resp.isSuccessful) {
                    ModelTestItem(label, modelId, passed = true, detail = "Pass (HTTP ${resp.code}, ${elapsed}ms)", role = AiRole.MIND, latencyMs = elapsed)
                } else {
                    val (reason, _) = AiFailureClassifier.extractCleanErrorMessageAndCode(body, key)
                    ModelTestItem(
                        label,
                        modelId,
                        passed = false,
                        detail = "Fail (HTTP ${resp.code}${if (!reason.isNullOrBlank()) ": ${reason.take(80)}" else ""})",
                        role = AiRole.MIND,
                        latencyMs = elapsed
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val clean = SensitiveDataSanitizer.sanitizeError(e.message, key).take(80)
            ModelTestItem(label, modelId, passed = false, detail = "Fail ($clean)", role = AiRole.MIND)
        }
    }

    private fun testGroqChatModel(
        label: String,
        modelId: String,
        key: String,
        baseUrlOverride: String? = null
    ): ModelTestItem {
        if (key.isBlank()) {
            return ModelTestItem(label, modelId, passed = false, detail = "Groq API key missing", role = AiRole.FALLBACK_CHAT)
        }
        val t0 = System.currentTimeMillis()
        val base = resolveGroqOpenAiRoot(baseUrlOverride)
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
                .url("$base/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val body = runCatching { resp.body?.string() }.getOrNull()
                if (resp.isSuccessful) {
                    ModelTestItem(label, modelId, passed = true, detail = "Pass (HTTP ${resp.code}, ${elapsed}ms)", role = AiRole.FALLBACK_CHAT, latencyMs = elapsed)
                } else {
                    val (reason, _) = AiFailureClassifier.extractCleanErrorMessageAndCode(body, key)
                    ModelTestItem(
                        label,
                        modelId,
                        passed = false,
                        detail = "Fail (HTTP ${resp.code}${if (!reason.isNullOrBlank()) ": ${reason.take(80)}" else ""})",
                        role = AiRole.FALLBACK_CHAT,
                        latencyMs = elapsed
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val clean = SensitiveDataSanitizer.sanitizeError(e.message, key).take(80)
            ModelTestItem(label, modelId, passed = false, detail = "Fail ($clean)", role = AiRole.FALLBACK_CHAT)
        }
    }

    private fun testGroqSttModel(
        label: String,
        modelId: String,
        key: String,
        baseUrlOverride: String? = null
    ): ModelTestItem {
        if (key.isBlank()) {
            return ModelTestItem(label, modelId, passed = false, detail = "Groq API key missing", role = AiRole.STT)
        }
        val t0 = System.currentTimeMillis()
        val base = resolveGroqOpenAiRoot(baseUrlOverride)
        return try {
            val tinyWav = buildMinimalSilentWav(durationMs = 150)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "ping.wav", tinyWav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", modelId)
                .addFormDataPart("response_format", "json")
                .build()
            val req = Request.Builder()
                .url("$base/audio/transcriptions")
                .header("Authorization", "Bearer $key")
                .post(multipart)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val body = runCatching { resp.body?.string() }.getOrNull()
                if (resp.isSuccessful) {
                    ModelTestItem(label, modelId, passed = true, detail = "Pass (HTTP ${resp.code}, ${elapsed}ms)", role = AiRole.STT, latencyMs = elapsed)
                } else {
                    val (reason, _) = AiFailureClassifier.extractCleanErrorMessageAndCode(body, key)
                    ModelTestItem(
                        label,
                        modelId,
                        passed = false,
                        detail = "Fail (HTTP ${resp.code}${if (!reason.isNullOrBlank()) ": ${reason.take(80)}" else ""})",
                        role = AiRole.STT,
                        latencyMs = elapsed
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val clean = SensitiveDataSanitizer.sanitizeError(e.message, key).take(80)
            ModelTestItem(label, modelId, passed = false, detail = "Fail ($clean)", role = AiRole.STT)
        }
    }

    private fun testGeminiModel(
        label: String,
        modelId: String,
        key: String,
        baseUrlOverride: String? = null
    ): ModelTestItem {
        if (key.isBlank()) {
            return ModelTestItem(label, modelId, passed = false, detail = "Gemini API key missing", role = AiRole.VISION_SEARCH)
        }
        val t0 = System.currentTimeMillis()
        val base = resolveGeminiV1BetaRoot(baseUrlOverride)
        return try {
            // Gemini 3.8 Flash: NO temperature/top_p/top_k/candidate_count; use thinking_level="low"
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
                        put("maxOutputTokens", 16)
                        put(
                            "thinkingConfig",
                            JSONObject().apply { put("thinkingLevel", "low") }
                        )
                    }
                )
            }.toString()
            val req = Request.Builder()
                .url("$base/models/${modelId.trim()}:generateContent")
                .header("x-goog-api-key", key)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val body = runCatching { resp.body?.string() }.getOrNull()
                if (resp.isSuccessful) {
                    ModelTestItem(label, modelId, passed = true, detail = "Pass (HTTP ${resp.code}, ${elapsed}ms)", role = AiRole.VISION_SEARCH, latencyMs = elapsed)
                } else {
                    val (reason, _) = AiFailureClassifier.extractCleanErrorMessageAndCode(body, key)
                    ModelTestItem(
                        label,
                        modelId,
                        passed = false,
                        detail = "Fail (HTTP ${resp.code}${if (!reason.isNullOrBlank()) ": ${reason.take(80)}" else ""})",
                        role = AiRole.VISION_SEARCH,
                        latencyMs = elapsed
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val clean = SensitiveDataSanitizer.sanitizeError(e.message, key).take(80)
            ModelTestItem(label, modelId, passed = false, detail = "Fail ($clean)", role = AiRole.VISION_SEARCH)
        }
    }

    private fun testGeminiTtsModel(
        label: String,
        modelId: String,
        key: String,
        baseUrlOverride: String? = null
    ): ModelTestItem {
        if (key.isBlank()) {
            return ModelTestItem(label, modelId, passed = true, detail = "Android TTS ready (Gemini TTS key missing)", role = AiRole.TTS)
        }
        val t0 = System.currentTimeMillis()
        val base = resolveGeminiV1BetaRoot(baseUrlOverride)
        return try {
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("text", "Namaste.")
                                        put("speechMetadata", JSONObject().apply { put("style", "warm") })
                                    }
                                )
                            )
                        }
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put(
                            "speechConfig",
                            JSONObject().apply {
                                put(
                                    "voiceConfig",
                                    JSONObject().apply {
                                        put(
                                            "prebuiltVoiceConfig",
                                            JSONObject().apply { put("voiceName", "Kore") }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }.toString()
            val req = Request.Builder()
                .url("$base/models/${modelId.trim()}:generateContent")
                .header("x-goog-api-key", key)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val body = runCatching { resp.body?.string() }.getOrNull()
                if (resp.isSuccessful) {
                    ModelTestItem(label, modelId, passed = true, detail = "Pass (HTTP ${resp.code}, ${elapsed}ms)", role = AiRole.TTS, latencyMs = elapsed)
                } else {
                    val (reason, _) = AiFailureClassifier.extractCleanErrorMessageAndCode(body, key)
                    ModelTestItem(
                        label,
                        modelId,
                        passed = false,
                        detail = "Fail (HTTP ${resp.code}${if (!reason.isNullOrBlank()) ": ${reason.take(80)}" else ""})",
                        role = AiRole.TTS,
                        latencyMs = elapsed
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val clean = SensitiveDataSanitizer.sanitizeError(e.message, key).take(80)
            ModelTestItem(label, modelId, passed = false, detail = "Fail ($clean)", role = AiRole.TTS)
        }
    }

    private fun resolveGroqOpenAiRoot(baseUrlOverride: String?): String {
        val raw = baseUrlOverride?.trim()?.trimEnd('/').orEmpty()
        return if (raw.isBlank()) {
            "${groqBaseUrl.trimEnd('/')}/openai/v1"
        } else if (raw.endsWith("/openai/v1")) {
            raw
        } else {
            "$raw/openai/v1"
        }
    }

    private fun resolveGeminiV1BetaRoot(baseUrlOverride: String?): String {
        val raw = baseUrlOverride?.trim()?.trimEnd('/').orEmpty()
        return if (raw.isBlank()) {
            "${geminiBaseUrl.trimEnd('/')}/v1beta"
        } else if (raw.endsWith("/v1beta")) {
            raw
        } else {
            "$raw/v1beta"
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
