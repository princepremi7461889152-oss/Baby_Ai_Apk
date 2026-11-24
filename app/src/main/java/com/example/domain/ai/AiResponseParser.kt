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
            val normalized = ToolCallNormalizer.normalizeFromRaw(
                rawPayload = jsonBlock,
                defaultId = "inline_call_$idx",
                mapAdapter = mapAdapter
            )
            if (normalized != null && !normalized.isMalformedJson) {
                results.add(
                    PlannedToolCall(
                        id = normalized.id,
                        name = normalized.name,
                        args = normalized.args,
                        rawCallSanitized = normalized.rawCallSanitized
                    )
                )
            }
        }
        return results
    }

    fun hasMalformedToolJson(rawJson: String?): Boolean =
        ToolCallNormalizer.hasMalformedArgsPayload(rawJson)

    fun parseToolArgsSafely(
        rawJson: Any?,
        mapAdapter: JsonAdapter<Map<String, Any?>>? = null,
        toolName: String? = null
    ): Map<String, Any?> = ToolCallNormalizer.parseArgsMapSafely(rawJson, toolName, mapAdapter)
}
