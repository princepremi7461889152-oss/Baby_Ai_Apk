package com.example.domain.ai

import com.squareup.moshi.JsonAdapter
import org.json.JSONObject

/**
 * Normalizes outputs from Mercury 3, Gemini 3.8 Flash, gpt-oss-120b, and qwen3.6-27b:
 * - Strips thinking/reasoning tags (<think>, <thinking>, <reasoning>, <thought>, Harmony channels)
 * - Safely parses tool_calls and inline <tool_call> blocks without crashing on malformed JSON
 * - Detects malformed JSON tool arguments so AiRouter can re-ask once with a repair prompt
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

    /**
     * Strips unclosed thinking tags in partial streaming chunks so live UI never flashes chain-of-thought.
     */
    fun stripReasoningForStreaming(rawAccumulated: String): String {
        if (rawAccumulated.isBlank()) return ""
        var text = rawAccumulated
        val finalMarker = "<|channel|>final<|message|>"
        val finalIdx = text.lastIndexOf(finalMarker, ignoreCase = true)
        if (finalIdx >= 0) {
            text = text.substring(finalIdx + finalMarker.length)
        } else {
            text = HARMONY_ANALYSIS_REGEX.replace(text, "")
        }
        text = THOUGHT_BLOCK_REGEX.replace(text, "")
        text = BRACKET_THOUGHT_REGEX.replace(text, "")
        text = UNCLOSED_THOUGHT_REGEX.replace(text, "")
        text = INLINE_TOOL_CALL_REGEX.replace(text, "")
        text = HARMONY_SPECIAL_TOKEN_REGEX.replace(text, " ")
        return text.trimStart()
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

    fun hasMalformedToolJson(rawJson: String?): Boolean {
        if (rawJson.isNullOrBlank()) return false
        val cleaned = unwrapMarkdownAndEscapedString(rawJson)
        if (cleaned.isBlank() || cleaned == "{}") return false
        val candidate = extractBracedJson(cleaned) ?: return true
        return runCatching {
            JSONObject(candidate)
            false
        }.getOrDefault(true)
    }

    fun parseToolArgsSafely(
        rawJson: String?,
        mapAdapter: JsonAdapter<Map<String, Any?>>
    ): Map<String, Any?> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        val cleaned = unwrapMarkdownAndEscapedString(rawJson)
        val jsonCandidate = extractBracedJson(cleaned) ?: return emptyMap()

        return runCatching {
            mapAdapter.fromJson(jsonCandidate).orEmpty()
        }.getOrElse {
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

    private fun unwrapMarkdownAndEscapedString(rawJson: String): String {
        var cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length >= 2) {
            val unwrapped = runCatching {
                JSONObject("{\"v\":$cleaned}").optString("v", "")
            }.getOrNull()
            if (!unwrapped.isNullOrBlank()) {
                cleaned = unwrapped.trim()
            }
        }
        return cleaned
    }

    private fun extractBracedJson(cleaned: String): String? {
        if (cleaned.startsWith("{")) return cleaned
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }
}
