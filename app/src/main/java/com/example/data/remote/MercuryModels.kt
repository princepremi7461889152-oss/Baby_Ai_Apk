package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MercuryChatRequest(
    val model: String,
    val messages: List<GroqMessage>,
    val tools: List<GroqTool>? = null,
    @Json(name = "tool_choice") val toolChoice: String? = null,
    @Json(name = "reasoning_effort") val reasoningEffort: String? = "low",
    @Json(name = "max_completion_tokens") val maxCompletionTokens: Int = 2048,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class MercuryModelsListResponse(
    val data: List<MercuryModelInfo>? = null
)

@JsonClass(generateAdapter = true)
data class MercuryModelInfo(
    val id: String? = null,
    @Json(name = "owned_by") val ownedBy: String? = null
)
