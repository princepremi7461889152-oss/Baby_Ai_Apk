package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GroqChatRequest(
    val model: String,
    val messages: List<GroqMessage>,
    val tools: List<GroqTool>? = null,
    @Json(name = "tool_choice") val toolChoice: String? = "auto",
    val temperature: Double = 0.35,
    @Json(name = "max_tokens") val maxTokens: Int = 2048
)

@JsonClass(generateAdapter = true)
data class GroqMessage(
    val role: String,
    val content: String? = null,
    val reasoning: String? = null,
    @Json(name = "reasoning_content") val reasoningContent: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<GroqToolCall>? = null,
    @Json(name = "tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@JsonClass(generateAdapter = true)
data class GroqTool(
    val type: String = "function",
    val function: GroqFunctionSpec
)

@JsonClass(generateAdapter = true)
data class GroqFunctionSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class GroqToolCall(
    val id: String? = null,
    val type: String? = "function",
    val function: GroqFunctionCallPayload? = null
)

@JsonClass(generateAdapter = true)
data class GroqFunctionCallPayload(
    val name: String? = null,
    val arguments: String? = null
)

@JsonClass(generateAdapter = true)
data class GroqChatResponse(
    val id: String? = null,
    val choices: List<GroqChoice>? = null,
    val error: GroqErrorBody? = null
)

@JsonClass(generateAdapter = true)
data class GroqChoice(
    val index: Int = 0,
    val message: GroqMessage? = null,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GroqErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

@JsonClass(generateAdapter = true)
data class GroqTranscriptionResponse(
    val text: String = "",
    val language: String? = null,
    val duration: Double? = null
)

@JsonClass(generateAdapter = true)
data class GroqModelsListResponse(
    val data: List<GroqModelInfo>? = null
)

@JsonClass(generateAdapter = true)
data class GroqModelInfo(
    val id: String? = null,
    @Json(name = "owned_by") val ownedBy: String? = null,
    val active: Boolean? = null
)
