package com.example.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ExecutionPathType(val label: String) {
    FAST_PATH("Fast Path (<300ms)"),
    MERCURY("Mercury 3"),
    GEMINI("Gemini 3.8 Flash"),
    GROQ("Groq Fallback"),
    OFFLINE_FALLBACK("Offline Fallback")
}

data class LatencyRecord(
    val id: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    val commandPreview: String,
    val pathType: ExecutionPathType,
    val sttMs: Long = 0L,
    val intentOrLlmMs: Long = 0L,
    val toolMs: Long = 0L,
    val ttsMs: Long = 0L,
    val firstSpokenMs: Long = 0L,
    val totalMs: Long = 0L,
    val status: String = "OK"
) {
    val metTarget: Boolean
        get() = when (pathType) {
            ExecutionPathType.FAST_PATH -> totalMs <= 500L
            else -> (firstSpokenMs in 1..2000L) || totalMs <= 2000L
        }
}

@Singleton
class LatencyTracker @Inject constructor() {

    private val _records = MutableStateFlow<List<LatencyRecord>>(emptyList())
    val records: StateFlow<List<LatencyRecord>> = _records.asStateFlow()

    @Volatile
    private var nextId = 1L

    fun record(entry: LatencyRecord) {
        val withId = if (entry.id <= 0L) entry.copy(id = nextId++) else entry
        val updated = (listOf(withId) + _records.value).take(120)
        _records.value = updated
    }

    fun recordSimple(
        command: String,
        pathType: ExecutionPathType,
        sttMs: Long = 0L,
        intentOrLlmMs: Long = 0L,
        toolMs: Long = 0L,
        ttsMs: Long = 0L,
        firstSpokenMs: Long = 0L,
        totalMs: Long = intentOrLlmMs + toolMs + ttsMs,
        status: String = "OK"
    ) {
        record(
            LatencyRecord(
                id = nextId++,
                commandPreview = command.trim().take(70),
                pathType = pathType,
                sttMs = sttMs.coerceAtLeast(0L),
                intentOrLlmMs = intentOrLlmMs.coerceAtLeast(0L),
                toolMs = toolMs.coerceAtLeast(0L),
                ttsMs = ttsMs.coerceAtLeast(0L),
                firstSpokenMs = firstSpokenMs.coerceAtLeast(0L),
                totalMs = totalMs.coerceAtLeast(0L),
                status = status
            )
        )
    }

    fun clear() {
        _records.value = emptyList()
    }
}
