package com.example.features.actionlog

import com.example.core.security.SecureKeyStore
import com.example.data.local.ActionLogDao
import com.example.data.local.ActionLogEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionLogRepository @Inject constructor(
    private val dao: ActionLogDao,
    private val keyStore: SecureKeyStore
) {
    val logs: Flow<List<ActionLogEntity>> = dao.observeRecent()

    suspend fun record(
        commandText: String,
        toolName: String,
        argsSummary: String,
        resultText: String,
        targetApp: String = "",
        status: String = "OK",
        source: String = "text"
    ) {
        val settings = keyStore.settings.value
        if (!settings.actionLogEnabled) return
        dao.insert(
            ActionLogEntity(
                commandText = commandText.take(220),
                toolName = toolName,
                argsSummary = argsSummary.take(280),
                resultText = resultText.take(400),
                targetApp = targetApp,
                status = status,
                source = source
            )
        )
        pruneExpired()
    }

    suspend fun pruneExpired() {
        val days = keyStore.settings.value.actionLogRetentionDays
        if (days > 0) {
            val cutoffMs = System.currentTimeMillis() - (days.toLong() * 24L * 60L * 60L * 1000L)
            dao.deleteOlderThan(cutoffMs)
        }
    }

    suspend fun clearAll() = dao.clearAll()

    suspend fun exportAsText(): String {
        val all = dao.getAllForExport()
        if (all.isEmpty()) return "Baby Action Log is empty."
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("=== Baby Assistant Action Log (${all.size} entries) ===")
            all.forEach { e ->
                appendLine("[${fmt.format(Date(e.timestamp))}] [${e.status}] [${e.source}] Tool: ${e.toolName}")
                if (e.commandText.isNotBlank()) appendLine("  Command: ${e.commandText}")
                if (e.targetApp.isNotBlank()) appendLine("  App: ${e.targetApp}")
                if (e.argsSummary.isNotBlank()) appendLine("  Args: ${e.argsSummary}")
                appendLine("  Result: ${e.resultText}")
            }
        }
    }
}
