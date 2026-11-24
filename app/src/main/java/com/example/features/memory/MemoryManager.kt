package com.example.features.memory

import com.example.core.security.SecureKeyStore
import com.example.data.local.MemoryDao
import com.example.data.local.MemoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryManager @Inject constructor(
    private val dao: MemoryDao,
    private val keyStore: SecureKeyStore
) {
    val memories: Flow<List<MemoryEntity>> = dao.observeAll()

    suspend fun saveMemory(
        key: String,
        value: String,
        category: String = "FACT"
    ): String {
        if (!keyStore.settings.value.memoryEnabled) {
            return "Memory feature Settings mein OFF hai."
        }
        val cleanKey = key.trim().ifBlank { value.take(28) }
        val cleanVal = value.trim()
        if (cleanVal.isBlank()) return "Empty memory save nahi ki."

        val existing = dao.findByKey(cleanKey)
        if (existing != null) {
            dao.update(
                existing.copy(
                    category = category.uppercase(),
                    memoryValue = cleanVal,
                    usageCount = existing.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            dao.upsert(
                MemoryEntity(
                    category = category.uppercase(),
                    memoryKey = cleanKey,
                    memoryValue = cleanVal
                )
            )
        }
        return "Yaad rakh liya: $cleanKey = $cleanVal"
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        dao.update(memory.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteMemory(id: Long) = dao.deleteById(id)

    suspend fun deleteMemoryByKey(key: String): String {
        val clean = key.trim()
        if (clean.isBlank()) return "Memory key empty hai."
        val deleted = dao.deleteByKey(clean)
        return if (deleted > 0) {
            "Memory '$clean' delete kar di hai."
        } else {
            // Try partial match fallback
            val match = dao.getAllSnapshot().firstOrNull {
                it.memoryKey.contains(clean, ignoreCase = true) ||
                    it.memoryValue.contains(clean, ignoreCase = true)
            }
            if (match != null) {
                dao.deleteById(match.id)
                "Memory '${match.memoryKey}' delete kar di hai."
            } else {
                "'$clean' naam ki koi memory nahi mili."
            }
        }
    }

    suspend fun wipeAll(): String {
        dao.wipeAll()
        return "Sab yaadein mita di hain. Memory ab bilkul saaf hai."
    }

    suspend fun recallRelevant(query: String, limit: Int = 10): List<MemoryEntity> {
        if (!keyStore.settings.value.memoryEnabled) return emptyList()
        val all = dao.getAllSnapshot()
        if (all.isEmpty()) return emptyList()
        val tokens = query.lowercase()
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 2 }
            .toSet()

        if (tokens.isEmpty()) return all.take(limit)

        val scored = all.map { mem ->
            val hay = "${mem.memoryKey} ${mem.memoryValue} ${mem.category}".lowercase()
            val hits = tokens.count { hay.contains(it) }
            mem to hits
        }.filter { it.second > 0 }
            .sortedByDescending { it.second * 10 + it.first.usageCount }
            .map { it.first }
            .take(limit)

        return scored
    }

    suspend fun buildRelevantPromptBlock(userQuery: String): String? {
        val relevant = recallRelevant(userQuery, limit = 10)
        if (relevant.isEmpty()) return null
        return relevant.joinToString("\n") { "- [${it.category}] ${it.memoryKey}: ${it.memoryValue}" }
    }

    suspend fun recordAppUsageHabit(appName: String) {
        if (!keyStore.settings.value.memoryEnabled || appName.isBlank()) return
        val key = "freq_app_${appName.lowercase().trim()}"
        val existing = dao.findByKey(key)
        if (existing != null) {
            dao.update(
                existing.copy(
                    usageCount = existing.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            dao.upsert(
                MemoryEntity(
                    category = "APP_HABIT",
                    memoryKey = key,
                    memoryValue = "User frequently opens $appName",
                    usageCount = 1
                )
            )
        }
    }

    companion object {
        fun isWipeMemoryPhrase(text: String): Boolean {
            val l = text.lowercase().trim()
            return l.contains("sab bhool jao") ||
                l.contains("sab kuch bhool jao") ||
                l.contains("wipe all memory") ||
                l.contains("forget everything")
        }

        fun extractYaadRakhna(text: String): Pair<String, String>? {
            val lower = text.lowercase()
            val marker = listOf("yaad rakhna", "yaad rakh", "remember that", "remember ")
                .firstOrNull { lower.contains(it) } ?: return null
            val idx = lower.indexOf(marker)
            val content = text.substring(idx + marker.length).trim(' ', ':', ',', '.')
            if (content.length < 3) return null
            val key = content.split(" ").take(4).joinToString(" ")
            return key to content
        }
    }
}
