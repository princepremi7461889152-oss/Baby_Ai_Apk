package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user", "assistant", "tool", "system"
    val content: String,
    val brainUsed: String? = null, // "groq", "gemini", "local"
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolResult: String? = null,
    val languageHint: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_runs")
data class TaskRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandText: String,
    val status: String, // "RUNNING", "AWAITING_CONFIRMATION", "QUEUED_FOR_UNLOCK", "COMPLETED", "STOPPED", "FAILED"
    val stepsCount: Int = 0,
    val maxSteps: Int = 15,
    val brainSummary: String = "",
    val finalReply: String = "",
    val stepLogsJson: String = "[]",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

@Entity(tableName = "pending_queued_tasks")
data class PendingQueuedTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandText: String,
    val source: String, // "voice", "text", "bubble"
    val createdAt: Long = System.currentTimeMillis()
)
