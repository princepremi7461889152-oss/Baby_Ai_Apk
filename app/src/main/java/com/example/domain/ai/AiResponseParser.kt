package com.example.domain.ai

import com.squareup.moshi.JsonAdapter
import org.json.JSONObject

/**
 * Normalizes outputs from gpt-oss-120b, gpt-oss-20b, qwen3.6-27b, Llama, and Gemini models:
 * - Strips thinking/reasoning tags (<think>, <thinking>, <reasoning>, <thought>, Harmony channels)
 * - Safely parses tool_calls and inline <tool_call> blocks without crashing on malformed JSON
 */
object AiResponseParser {

    private val THOUGHT_BLOCK_REGEX = Regex(
        """(?is)<(?:think|thinking|reasoning|thought)>\s*.*?\s*</(?:think|thinking|reasoning|thought)>"""
    )
    private val UNCLOSED_THOUGHT_REGEX = Regex(
        """(?is)<(?:think|thinking|reasoning|thought)>.*$"""
    )
    private val BRACKET_THOUGHT_REGEX = Regex(
        """(?is)\[THOUGHT\]\s*.*?\s*\[/THOUGHT\]"""
    )
    private val HARMONY_ANALYSIS_REGEX = Regex(
        """(?is)<\|channel\|>analysis<\|message\|>.*?(?:<\|end\|>|<\|start\|>|$)"""
    )
    private val HARMONY_SPECIAL_TOKEN_REGEX = Regex("""<\|[a-zA-Z0-9_]+\|>""")
    private val INLINE_TOOL_CALL_REGEX = Regex(
        """(?is)<tool_call>\s*(\{.*?\})\s*</tool_call>"""
    )

    fun stripReasoning(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim()

        // GPT-OSS Harmony format: <|channel|>analysis<|message|>...<|end|><|start|>assistant<|channel|>final<|message|>Answer
        val finalMarker = "<|channel|>final<|message|>"
        val finalIdx = text.lastIndexOf(finalMarker, ignoreCase = true)
        if (finalIdx >= 0) {
            text = text.substring(finalIdx + finalMarker.length)
        } else {
            text = HARMONY_ANALYSIS_REGEX.replace(text, "")
        }

        text = HARMONY_SPECIAL_TOKEN_REGEX.replace(text, " ")
        text = THOUGHT_BLOCK_REGEX.replace(text, "")
        text = BRACKET_THOUGHT_REGEX.replace(text, "")
        text = UNCLOSED_THOUGHT_REGEX.replace(text, "")
        text = INLINE_TOOL_CALL_REGEX.replace(text, "")

        return text.trim().takeIf { it.isNotEmpty() }
    }

    fun extractInlineToolCalls(
        rawContent: String?,
        mapAdapter: JsonAdapter<Map<String, Any?>>
    ): List<PlannedToolCall> {
        if (rawContent.isNullOrBlank()) return emptyList()
        val results = mutableListOf<PlannedToolCall>()
        INLINE_TOOL_CALL_REGEX.findAll(rawContent).forEachIndexed { idx, match ->
            val jsonBlock = match.groupValues.getOrNull(1)?.trim() ?: return@forEachIndexed
            runCatching {
                val obj = JSONObject(jsonBlock)
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    val argsRaw = when {
                        obj.has("arguments") -> obj.opt("arguments")?.toString()
                        obj.has("parameters") -> obj.opt("parameters")?.toString()
                        else -> "{}"
                    }
                    results.add(
                        PlannedToolCall(
                            id = "inline_call_$idx",
                            name = name,
                            args = parseToolArgsSafely(argsRaw, mapAdapter)
                        )
                    )
                }
            }
        }
        return results
    }

    fun parseToolArgsSafely(
        rawJson: String?,
        mapAdapter: JsonAdapter<Map<String, Any?>>
    ): Map<String, Any?> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        var cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        // Handle double-encoded JSON strings sometimes returned by OpenAI-compatible endpoints
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length >= 2) {
            val unwrapped = runCatching {
                JSONObject("{\"v\":$cleaned}").optString("v", "")
            }.getOrNull()
            if (!unwrapped.isNullOrBlank()) {
                cleaned = unwrapped.trim()
            }
        }

        val jsonCandidate = if (cleaned.startsWith("{")) {
            cleaned
        } else {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) cleaned.substring(start, end + 1) else return emptyMap()
        }

        return runCatching {
            mapAdapter.fromJson(jsonCandidate).orEmpty()
        }.getOrElse {
            // Fallback lenient parse with org.json if Moshi choked on trailing whitespace/comments
            runCatching {
                val obj = JSONObject(jsonCandidate)
                val map = linkedMapOf<String, Any?>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.opt(k)
                    map[k] = if (v == JSONObject.NULL) null else v
                }
                map
            }.getOrDefault(emptyMap())
        }
    }
}
