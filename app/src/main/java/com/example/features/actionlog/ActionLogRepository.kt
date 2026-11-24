package com.example.features.actionlog

import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.local.ActionLogDao
import com.example.data.local.ActionLogEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionLogRepository @Inject constructor(
    private val dao: ActionLogDao,
    private val keyStore: SecureKeyStore
) {
    private val insertCounter = AtomicInteger(0)

    val logs: Flow<List<ActionLogEntity>> = dao.observeRecent()
    val totalCount: Flow<Int> = dao.observeTotalCount()
    val availableApps: Flow<List<String>> = dao.observeDistinctTargetApps()

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
        val safeCmd = SensitiveDataSanitizer.sanitizeForLogs(commandText).take(220)
        val safeArgs = SensitiveDataSanitizer.sanitizeForLogs(argsSummary).take(280)
        val safeResult = SensitiveDataSanitizer.sanitizeForLogs(resultText).take(400)
        val safeTargetApp = SensitiveDataSanitizer.sanitizeForLogs(targetApp).take(80)
        dao.insert(
            ActionLogEntity(
                commandText = safeCmd,
                toolName = toolName,
                argsSummary = safeArgs,
                resultText = safeResult,
                targetApp = safeTargetApp,
                status = status,
                source = source
            )
        )
        if (insertCounter.incrementAndGet() % PRUNE_EVERY_N_INSERTS == 0) {
            pruneExpired()
        }
    }

    suspend fun getPagedFiltered(
        query: String,
        status: String,
        source: String,
        targetApp: String,
        minTimestamp: Long,
        limit: Int,
        offset: Int
    ): List<ActionLogEntity> = dao.getPagedFiltered(
        query = query.trim(),
        status = status,
        source = source,
        targetApp = targetApp,
        minTimestamp = minTimestamp,
        limit = limit,
        offset = offset
    )

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
                if (e.commandText.isNotBlank()) appendLine("  Command: ${SensitiveDataSanitizer.sanitizeForLogs(e.commandText)}")
                if (e.targetApp.isNotBlank()) appendLine("  App: ${e.targetApp}")
                if (e.argsSummary.isNotBlank()) appendLine("  Args: ${SensitiveDataSanitizer.sanitizeForLogs(e.argsSummary)}")
                appendLine("  Result: ${SensitiveDataSanitizer.sanitizeForLogs(e.resultText)}")
            }
        }
    }

    companion object {
        private const val PRUNE_EVERY_N_INSERTS = 50
    }
}
