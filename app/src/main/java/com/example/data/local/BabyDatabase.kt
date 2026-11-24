package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatMessageEntity::class,
        TaskRunEntity::class,
        PendingQueuedTaskEntity::class,
        RoutineEntity::class,
        AutoReplyContactRuleEntity::class,
        MemoryEntity::class,
        ActionLogEntity::class,
        ReminderEntity::class,
        MacroEntity::class,
        AllowedAppEntity::class,
        EmergencyContactEntity::class,
        SavedPlaceEntity::class,
        AppAliasEntity::class,
        SearchRecipeEntity::class,
        CallScreeningLogEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class BabyDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun taskRunDao(): TaskRunDao
    abstract fun queuedTaskDao(): QueuedTaskDao
    abstract fun routineDao(): RoutineDao
    abstract fun autoReplyRuleDao(): AutoReplyRuleDao
    abstract fun memoryDao(): MemoryDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun reminderDao(): ReminderDao
    abstract fun macroDao(): MacroDao
    abstract fun allowedAppDao(): AllowedAppDao
    abstract fun emergencyContactDao(): EmergencyContactDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun appAliasDao(): AppAliasDao
    abstract fun searchRecipeDao(): SearchRecipeDao
    abstract fun callScreeningLogDao(): CallScreeningLogDao
}
