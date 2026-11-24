package com.example.features.actionlog

import com.example.domain.tools.ToolExecutionResult
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central interceptor wrapping every tool execution across the Agent Loop,
 * Daily Routines, Macros, and Offline Commands so every action is logged
 * uniformly into the ActionLog Room table.
 */
@Singleton
class ActionLogInterceptor @Inject constructor(
    private val actionLogRepository: ActionLogRepository
) {
    suspend fun executeLogged(
        commandText: String,
        toolName: String,
        args: Map<String, Any?>,
        source: String,
        block: suspend () -> ToolExecutionResult
    ): ToolExecutionResult {
        val result = try {
            block()
        } catch (t: Throwable) {
            val errRes = ToolExecutionResult(false, "Tool error ($toolName): ${t.message ?: "unknown"}")
            recordResult(commandText, toolName, args, source, errRes)
            return errRes
        }
        recordResult(commandText, toolName, args, source, result)
        return result
    }

    private suspend fun recordResult(
        commandText: String,
        toolName: String,
        args: Map<String, Any?>,
        source: String,
        result: ToolExecutionResult
    ) {
        val argsJson = runCatching { JSONObject(args).toString() }.getOrDefault(args.toString())
        val targetApp = args["app_name"]?.toString()
            ?: args["recipient"]?.toString()
            ?: args["target"]?.toString()
            ?: ""
        actionLogRepository.record(
            commandText = commandText,
            toolName = toolName,
            argsSummary = argsJson,
            resultText = result.output,
            targetApp = targetApp,
            status = if (result.ok) "OK" else "ERR",
            source = normalizeSource(source)
        )
    }

    companion object {
        fun normalizeSource(raw: String): String {
            val l = raw.lowercase()
            return when {
                l.contains("voice") || l.contains("wake") -> "voice"
                l.contains("routine") -> "routine"
                l.contains("auto") && l.contains("reply") -> "auto-reply"
                l.contains("macro") -> "macro"
                else -> "text"
            }
        }
    }
}
