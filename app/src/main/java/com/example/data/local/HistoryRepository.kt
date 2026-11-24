package com.example.data.local

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val taskRunDao: TaskRunDao,
    private val queuedTaskDao: QueuedTaskDao
) {
    val chatMessages: Flow<List<ChatMessageEntity>> = chatDao.observeAllMessages()
    val taskRuns: Flow<List<TaskRunEntity>> = taskRunDao.observeAllTaskRuns()
    val queuedTasks: Flow<List<PendingQueuedTaskEntity>> = queuedTaskDao.observeQueuedTasks()

    suspend fun addMessage(message: ChatMessageEntity): Long = chatDao.insertMessage(message)

    suspend fun getRecentConversation(limit: Int = 16): List<ChatMessageEntity> =
        chatDao.getRecentMessages(limit).reversed()

    suspend fun clearChat() = chatDao.clearAll()

    suspend fun startTaskRun(commandText: String): Long =
        taskRunDao.insertTaskRun(
            TaskRunEntity(
                commandText = commandText,
                status = "RUNNING"
            )
        )

    suspend fun updateTaskRun(
        id: Long,
        status: String,
        stepsCount: Int,
        brainSummary: String,
        finalReply: String,
        stepLogsJson: String,
        finishedAt: Long? = null
    ) {
        val existing = taskRunDao.getById(id) ?: return
        taskRunDao.updateTaskRun(
            existing.copy(
                status = status,
                stepsCount = stepsCount,
                brainSummary = brainSummary,
                finalReply = finalReply,
                stepLogsJson = stepLogsJson,
                finishedAt = finishedAt
            )
        )
    }

    suspend fun clearTaskHistory() = taskRunDao.clearAll()

    suspend fun enqueueForUnlock(commandText: String, source: String): Long =
        queuedTaskDao.enqueue(PendingQueuedTaskEntity(commandText = commandText, source = source))

    suspend fun popAllQueuedTasks(): List<PendingQueuedTaskEntity> {
        val items = queuedTaskDao.getAllQueuedTasks()
        queuedTaskDao.clearAll()
        return items
    }
}
