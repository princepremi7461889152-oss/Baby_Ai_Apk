package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC, id ASC")
    fun observeAllMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int = 20): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

@Dao
interface TaskRunDao {
    @Query("SELECT * FROM task_runs ORDER BY startedAt DESC")
    fun observeAllTaskRuns(): Flow<List<TaskRunEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskRun(taskRun: TaskRunEntity): Long

    @Update
    suspend fun updateTaskRun(taskRun: TaskRunEntity)

    @Query("SELECT * FROM task_runs WHERE id = :id")
    suspend fun getById(id: Long): TaskRunEntity?

    @Query("DELETE FROM task_runs")
    suspend fun clearAll()
}

@Dao
interface QueuedTaskDao {
    @Query("SELECT * FROM pending_queued_tasks ORDER BY createdAt ASC")
    fun observeQueuedTasks(): Flow<List<PendingQueuedTaskEntity>>

    @Query("SELECT * FROM pending_queued_tasks ORDER BY createdAt ASC")
    suspend fun getAllQueuedTasks(): List<PendingQueuedTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(task: PendingQueuedTaskEntity): Long

    @Query("DELETE FROM pending_queued_tasks WHERE id = :id")
    suspend fun removeById(id: Long)

    @Query("DELETE FROM pending_queued_tasks")
    suspend fun clearAll()
}
