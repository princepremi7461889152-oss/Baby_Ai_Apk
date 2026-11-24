package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY isBuiltIn DESC, createdAt ASC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE isEnabled = 1")
    suspend fun getEnabledRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RoutineEntity?

    @Query("SELECT COUNT(*) FROM routines")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: RoutineEntity): Long

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AutoReplyRuleDao {
    @Query("SELECT * FROM auto_reply_rules ORDER BY displayName ASC")
    fun observeAll(): Flow<List<AutoReplyContactRuleEntity>>

    @Query("SELECT * FROM auto_reply_rules WHERE contactKey = :key LIMIT 1")
    suspend fun getByKey(key: String): AutoReplyContactRuleEntity?

    @Query("SELECT * FROM auto_reply_rules")
    suspend fun getAll(): List<AutoReplyContactRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AutoReplyContactRuleEntity)

    @Query("DELETE FROM auto_reply_rules WHERE contactKey = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY usageCount DESC, updatedAt DESC")
    suspend fun getAllSnapshot(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE LOWER(memoryKey) = LOWER(:key) LIMIT 1")
    suspend fun findByKey(key: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories WHERE LOWER(memoryKey) = LOWER(:key)")
    suspend fun deleteByKey(key: String): Int

    @Query("DELETE FROM memories")
    suspend fun wipeAll()
}

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC LIMIT 300")
    fun observeRecent(): Flow<List<ActionLogEntity>>

    @Query("SELECT COUNT(*) FROM action_logs")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT DISTINCT targetApp FROM action_logs WHERE targetApp != '' ORDER BY targetApp COLLATE NOCASE ASC")
    fun observeDistinctTargetApps(): Flow<List<String>>

    @Query(
        """
        SELECT * FROM action_logs
        WHERE (:status = 'ALL' OR status = :status)
          AND (:source = 'ALL' OR source = :source)
          AND (:targetApp = 'ALL' OR targetApp = :targetApp)
          AND timestamp >= :minTimestamp
          AND (
            :query = ''
            OR commandText LIKE '%' || :query || '%'
            OR toolName LIKE '%' || :query || '%'
            OR argsSummary LIKE '%' || :query || '%'
            OR resultText LIKE '%' || :query || '%'
            OR targetApp LIKE '%' || :query || '%'
          )
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getPagedFiltered(
        query: String,
        status: String,
        source: String,
        targetApp: String,
        minTimestamp: Long,
        limit: Int,
        offset: Int
    ): List<ActionLogEntity>

    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC")
    suspend fun getAllForExport(): List<ActionLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ActionLogEntity): Long

    @Query("DELETE FROM action_logs WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM action_logs")
    suspend fun clearAll()
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY isCompleted ASC, triggerAtMs ASC, createdAt DESC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE triggerType = 'TIME' AND isCompleted = 0")
    suspend fun getActiveTimeReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE triggerType = 'LOCATION' AND isCompleted = 0")
    suspend fun getActiveLocationReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE isCompleted = 0 ORDER BY triggerAtMs ASC")
    suspend fun getAllActiveSnapshot(): List<ReminderEntity>

    @Query("SELECT * FROM reminders ORDER BY isCompleted ASC, triggerAtMs ASC")
    suspend fun getAllSnapshot(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Query("UPDATE reminders SET triggerAtMs = :newTriggerMs, isCompleted = 0 WHERE id = :id")
    suspend fun reschedule(id: Long, newTriggerMs: Long)

    @Query("UPDATE reminders SET isCompleted = 1 WHERE id = :id")
    suspend fun markCompleted(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places ORDER BY updatedAt DESC")
    suspend fun getAllSnapshot(): List<SavedPlaceEntity>

    @Query("SELECT * FROM saved_places WHERE LOWER(placeKey) = LOWER(:key) OR LOWER(label) = LOWER(:key) LIMIT 1")
    suspend fun findByKeyOrLabel(key: String): SavedPlaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: SavedPlaceEntity): Long

    @Query("DELETE FROM saved_places WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MacroDao {
    @Query("SELECT * FROM macros ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macros WHERE isEnabled = 1")
    suspend fun getEnabled(): List<MacroEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(macro: MacroEntity): Long

    @Query("DELETE FROM macros WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AllowedAppDao {
    @Query("SELECT * FROM allowed_apps ORDER BY appLabel ASC")
    fun observeAll(): Flow<List<AllowedAppEntity>>

    @Query("SELECT * FROM allowed_apps WHERE isAllowed = 1")
    suspend fun getAllowed(): List<AllowedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: AllowedAppEntity)

    @Query("DELETE FROM allowed_apps WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)
}

@Dao
interface EmergencyContactDao {
    @Query("SELECT * FROM emergency_contacts ORDER BY name ASC")
    fun observeAll(): Flow<List<EmergencyContactEntity>>

    @Query("SELECT * FROM emergency_contacts")
    suspend fun getAll(): List<EmergencyContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: EmergencyContactEntity): Long

    @Query("DELETE FROM emergency_contacts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
