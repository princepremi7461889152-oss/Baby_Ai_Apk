package com.example.ui.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

sealed interface KeyTestResult {
    data object Idle : KeyTestResult
    data object Testing : KeyTestResult
    data class Valid(val message: String) : KeyTestResult
    data class Invalid(val message: String) : KeyTestResult
}

@Singleton
class ApiKeyHealthTester @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun testGroqKey(rawKey: String): KeyTestResult = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        if (key.isBlank()) {
            return@withContext KeyTestResult.Invalid("Groq key khali hai")
        }
        runCatching {
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
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
        }.getOrElse { err ->
            KeyTestResult.Invalid("Network error: ${err.localizedMessage ?: "Offline"}")
        }
    }

    suspend fun testGeminiKey(rawKey: String): KeyTestResult = withContext(Dispatchers.IO) {
        val key = rawKey.trim()
        if (key.isBlank()) {
            return@withContext KeyTestResult.Invalid("Gemini key khali hai")
        }
        runCatching {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
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
        }.getOrElse { err ->
            KeyTestResult.Invalid("Network error: ${err.localizedMessage ?: "Offline"}")
        }
    }
}
