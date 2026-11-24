package com.example.ui.state

import com.example.core.security.AiModelConfig
import com.example.core.security.SensitiveDataSanitizer
import com.example.domain.tools.ToolDefinitions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class CompareCommandCase(
    val id: Int,
    val prompt: String,
    val expectedTool: String?,
    val category: String
)

data class CompareCommandResult(
    val case: CompareCommandCase,
    val modelLabel: String,
    val passed: Boolean,
    val toolAccurate: Boolean,
    val latencyMs: Long,
    val actualToolOrReply: String
)

data class CompareModelSummary(
    val modelLabel: String,
    val modelId: String,
    val configured: Boolean,
    val passedCount: Int,
    val totalCount: Int,
    val avgLatencyMs: Long,
    val toolAccuracyPercent: Int
)

data class CompareBenchmarkUiState(
    val isRunning: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 90,
    val statusText: String = "Ready to benchmark 30 Hinglish commands",
    val summaries: List<CompareModelSummary> = emptyList(),
    val results: List<CompareCommandResult> = emptyList()
)

@Singleton
class ModelBenchmarkRunner @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    val thirtyHinglishCases: List<CompareCommandCase> = listOf(
        CompareCommandCase(1, "Flashlight on karo", "toggle_flashlight", "Device"),
        CompareCommandCase(2, "Torch band kar do", "toggle_flashlight", "Device"),
        CompareCommandCase(3, "Media volume 70 percent kar do", "set_volume", "Device"),
        CompareCommandCase(4, "Brightness full kar do", "set_brightness", "Device"),
        CompareCommandCase(5, "Wi-Fi chalu karo", "toggle_wifi", "Device"),
        CompareCommandCase(6, "Bluetooth off karo", "toggle_bluetooth", "Device"),
        CompareCommandCase(7, "DND mode lagao", "toggle_dnd", "Device"),
        CompareCommandCase(8, "Subah 6:30 ka alarm lagao", "set_alarm", "Clock"),
        CompareCommandCase(9, "10 minute ka timer lagao", "set_timer", "Clock"),
        CompareCommandCase(10, "Yutub kholo", "open_app", "Apps"),
        CompareCommandCase(11, "Whatsap open karo", "open_app", "Apps"),
        CompareCommandCase(12, "Insta kholo", "open_app", "Apps"),
        CompareCommandCase(13, "Mummy ko call lagao", "call_contact", "Comms"),
        CompareCommandCase(14, "Rahul ko WhatsApp pe message bhejo main pahunch raha hoon", "send_whatsapp", "Comms"),
        CompareCommandCase(15, "Bhai ko SMS bhejo ghar aa jao", "send_sms", "Comms"),
        CompareCommandCase(16, "Gaana pause karo", "media_control", "Media"),
        CompareCommandCase(17, "Next song chalao", "media_control", "Media"),
        CompareCommandCase(18, "Yutub pe Arijit Singh ka naya gana bajao", "search_in_app", "Search&Play"),
        CompareCommandCase(19, "Spotify pe lofi beats chalao", "search_in_app", "Search&Play"),
        CompareCommandCase(20, "Maps pe India Gate ka rasta dikhao", "search_in_app", "Search&Play"),
        CompareCommandCase(21, "Amazon pe black hoodie dhundo", "search_in_app", "Search&Play"),
        CompareCommandCase(22, "Google pe search karo aaj Delhi ka mausam", "search_web", "WebSearch"),
        CompareCommandCase(23, "Aaj ka cricket score kya hai", "search_web", "WebSearch"),
        CompareCommandCase(24, "Meri notifications batao", "summarize_notifications", "Notifs"),
        CompareCommandCase(25, "Shaam 6 baje doodh lene ka reminder lagao", "set_reminder", "Reminders"),
        CompareCommandCase(26, "Yaad rakhna Mummy ka number 9876543210 hai", "save_memory", "Memory"),
        CompareCommandCase(27, "Mummy ka number kya save hai", "recall_memory", "Memory"),
        CompareCommandCase(28, "Screen pe jo likha hai isko Hinglish mein explain karo", "explain_screen", "Vision"),
        CompareCommandCase(29, "Pichhe jao", "go_back", "Nav"),
        CompareCommandCase(30, "Tum kaun ho aur apna naam batao", null, "Identity")
    )

    suspend fun runBenchmark(
        config: AiModelConfig,
        inceptionKey: String,
        geminiKey: String,
        groqKey: String,
        onProgress: (CompareBenchmarkUiState) -> Unit
    ): CompareBenchmarkUiState = withContext(Dispatchers.IO) {
        val norm = config.normalized()
        val models = listOf(
            Triple("Mercury 3", norm.mindModel, inceptionKey.trim()),
            Triple("Gemini 3.8 Flash", norm.geminiModel, geminiKey.trim()),
            Triple("Groq Fallback", norm.chatModel, groqKey.trim())
        )
        val total = thirtyHinglishCases.size * models.size
        val allResults = mutableListOf<CompareCommandResult>()
        var done = 0

        for ((label, modelId, key) in models) {
            for (case in thirtyHinglishCases) {
                val res = if (key.isBlank()) {
                    CompareCommandResult(
                        case = case,
                        modelLabel = label,
                        passed = false,
                        toolAccurate = false,
                        latencyMs = 0L,
                        actualToolOrReply = "API key missing"
                    )
                } else {
                    when (label) {
                        "Mercury 3" -> executeOpenAiCase(
                            label = label,
                            url = "${norm.inceptionBaseUrl}/chat/completions",
                            modelId = modelId,
                            key = key,
                            case = case,
                            includeReasoningEffort = true
                        )
                        "Groq Fallback" -> executeOpenAiCase(
                            label = label,
                            url = "${norm.groqBaseUrl}/chat/completions",
                            modelId = modelId,
                            key = key,
                            case = case,
                            includeReasoningEffort = false
                        )
                        else -> executeGeminiCase(
                            label = label,
                            baseUrl = norm.geminiBaseUrl,
                            modelId = modelId,
                            key = key,
                            case = case
                        )
                    }
                }
                allResults += res
                done++
                if (done % 3 == 0 || done == total) {
                    onProgress(
                        CompareBenchmarkUiState(
                            isRunning = done < total,
                            progressCurrent = done,
                            progressTotal = total,
                            statusText = "Testing $label (${case.id}/30)...",
                            summaries = buildSummaries(models, allResults),
                            results = allResults.toList()
                        )
                    )
                }
            }
        }

        CompareBenchmarkUiState(
            isRunning = false,
            progressCurrent = total,
            progressTotal = total,
            statusText = "Benchmark complete (30 Hinglish commands x 3 models)",
            summaries = buildSummaries(models, allResults),
            results = allResults
        )
    }

    private fun buildSummaries(
        models: List<Triple<String, String, String>>,
        results: List<CompareCommandResult>
    ): List<CompareModelSummary> {
        return models.map { (label, modelId, key) ->
            val rows = results.filter { it.modelLabel == label }
            val passedCount = rows.count { it.passed }
            val toolCases = rows.filter { it.case.expectedTool != null }
            val toolHits = toolCases.count { it.toolAccurate }
            val avgMs = rows.filter { it.latencyMs > 0 }.map { it.latencyMs }.average()
                .let { if (it.isNaN()) 0L else it.toLong() }
            val accPct = if (toolCases.isEmpty()) 0 else ((toolHits * 100f) / toolCases.size).toInt()
            CompareModelSummary(
                modelLabel = label,
                modelId = modelId,
                configured = key.isNotBlank(),
                passedCount = passedCount,
                totalCount = thirtyHinglishCases.size,
                avgLatencyMs = avgMs,
                toolAccuracyPercent = accPct
            )
        }
    }

    private fun executeOpenAiCase(
        label: String,
        url: String,
        modelId: String,
        key: String,
        case: CompareCommandCase,
        includeReasoningEffort: Boolean
    ): CompareCommandResult {
        val t0 = System.currentTimeMillis()
        return try {
            val toolsJson = JSONArray().apply {
                ToolDefinitions.selectRelevantTools(case.prompt, dataSaver = false).forEach { tool ->
                    put(
                        JSONObject().apply {
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", tool.function.name)
                                    put("description", tool.function.description)
                                    put("parameters", JSONObject(tool.function.parameters))
                                }
                            )
                        }
                    )
                }
            }
            val payload = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", 256)
                if (includeReasoningEffort) put("reasoning_effort", "low")
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "Your name is Baby. Friendly Hinglish AI assistant. Use provided tools for actions; never mention model names.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", case.prompt)
                        })
                    }
                )
                put("tools", toolsJson)
                put("tool_choice", "auto")
            }.toString()

            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                if (!resp.isSuccessful) {
                    return CompareCommandResult(case, label, passed = false, toolAccurate = false, latencyMs = elapsed, actualToolOrReply = "HTTP ${resp.code}")
                }
                val root = JSONObject(raw)
                val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val toolCalls = msg?.optJSONArray("tool_calls")
                val actualTool = toolCalls?.optJSONObject(0)?.optJSONObject("function")?.optString("name")?.takeIf { it.isNotBlank() }
                val reply = msg?.optString("content")?.trim().orEmpty()

                val expected = case.expectedTool
                val toolOk = if (expected == null) {
                    actualTool == null && reply.contains("Baby", ignoreCase = true)
                } else {
                    actualTool == expected
                }
                val passed = if (expected == null) reply.isNotBlank() else actualTool != null && toolOk
                CompareCommandResult(
                    case = case,
                    modelLabel = label,
                    passed = passed,
                    toolAccurate = toolOk,
                    latencyMs = elapsed,
                    actualToolOrReply = actualTool ?: reply.take(48).ifBlank { "(empty)" }
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            CompareCommandResult(
                case = case,
                modelLabel = label,
                passed = false,
                toolAccurate = false,
                latencyMs = System.currentTimeMillis() - t0,
                actualToolOrReply = SensitiveDataSanitizer.sanitizeError(e.message, key).take(48)
            )
        }
    }

    private fun executeGeminiCase(
        label: String,
        baseUrl: String,
        modelId: String,
        key: String,
        case: CompareCommandCase
    ): CompareCommandResult {
        val t0 = System.currentTimeMillis()
        return try {
            val decls = JSONArray().apply {
                ToolDefinitions.selectRelevantGeminiTools(case.prompt, dataSaver = false)
                    .firstOrNull()?.functionDeclarations.orEmpty().forEach { fn ->
                        put(
                            JSONObject().apply {
                                put("name", fn.name)
                                put("description", fn.description)
                                put("parameters", JSONObject(fn.parameters))
                            }
                        )
                    }
            }
            val payload = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", "Your name is Baby. Friendly Hinglish AI assistant. Use provided tools for actions; never mention Gemini or model names.")
                        }))
                    }
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().apply { put("text", case.prompt) }))
                        }
                    )
                )
                put("tools", JSONArray().put(JSONObject().apply { put("functionDeclarations", decls) }))
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("maxOutputTokens", 256)
                        put("thinkingConfig", JSONObject().apply { put("thinkingLevel", "low") })
                    }
                )
            }.toString()

            val base = baseUrl.trim().trimEnd('/')
            val req = Request.Builder()
                .url("$base/models/${modelId.trim()}:generateContent")
                .header("x-goog-api-key", key)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                if (!resp.isSuccessful) {
                    return CompareCommandResult(case, label, passed = false, toolAccurate = false, latencyMs = elapsed, actualToolOrReply = "HTTP ${resp.code}")
                }
                val root = JSONObject(raw)
                val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                var actualTool: String? = null
                val textParts = mutableListOf<String>()
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    val fc = p.optJSONObject("functionCall")
                    if (fc != null) {
                        actualTool = fc.optString("name").takeIf { it.isNotBlank() }
                    }
                    val t = p.optString("text", "")
                    if (t.isNotBlank()) textParts += t
                }
                val reply = textParts.joinToString(" ").trim()
                val expected = case.expectedTool
                val toolOk = if (expected == null) {
                    actualTool == null && reply.contains("Baby", ignoreCase = true)
                } else {
                    actualTool == expected
                }
                val passed = if (expected == null) reply.isNotBlank() else actualTool != null && toolOk
                CompareCommandResult(
                    case = case,
                    modelLabel = label,
                    passed = passed,
                    toolAccurate = toolOk,
                    latencyMs = elapsed,
                    actualToolOrReply = actualTool ?: reply.take(48).ifBlank { "(empty)" }
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            CompareCommandResult(
                case = case,
                modelLabel = label,
                passed = false,
                toolAccurate = false,
                latencyMs = System.currentTimeMillis() - t0,
                actualToolOrReply = SensitiveDataSanitizer.sanitizeError(e.message, key).take(48)
            )
        }
    }
}
