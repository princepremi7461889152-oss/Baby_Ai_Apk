package com.example.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routines` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `triggerPhrase` TEXT NOT NULL,
                `stepsJson` TEXT NOT NULL,
                `isBuiltIn` INTEGER NOT NULL,
                `scheduleType` TEXT NOT NULL,
                `scheduleTime` TEXT NOT NULL,
                `isEnabled` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_reply_rules` (
                `contactKey` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `ruleType` TEXT NOT NULL,
                `lastRepliedAt` INTEGER NOT NULL,
                PRIMARY KEY(`contactKey`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memories` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `category` TEXT NOT NULL,
                `memoryKey` TEXT NOT NULL,
                `memoryValue` TEXT NOT NULL,
                `usageCount` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `action_logs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `commandText` TEXT NOT NULL,
                `toolName` TEXT NOT NULL,
                `argsSummary` TEXT NOT NULL,
                `resultText` TEXT NOT NULL,
                `targetApp` TEXT NOT NULL,
                `status` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reminders` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `triggerType` TEXT NOT NULL,
                `triggerAtMs` INTEGER NOT NULL,
                `latitude` REAL,
                `longitude` REAL,
                `radiusMeters` REAL NOT NULL,
                `placeLabel` TEXT NOT NULL,
                `isCompleted` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `macros` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `triggerPhrase` TEXT NOT NULL,
                `stepsJson` TEXT NOT NULL,
                `isEnabled` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `allowed_apps` (
                `packageName` TEXT NOT NULL,
                `appLabel` TEXT NOT NULL,
                `isAllowed` INTEGER NOT NULL,
                PRIMARY KEY(`packageName`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `emergency_contacts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `phoneNumber` TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `action_logs` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'text'")
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_places` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `placeKey` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `radiusMeters` REAL NOT NULL,
                `addressHint` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_places_placeKey` ON `saved_places` (`placeKey`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_runs_startedAt` ON `task_runs` (`startedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_logs_timestamp` ON `action_logs` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_logs_status` ON `action_logs` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_logs_source` ON `action_logs` (`source`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_isCompleted_triggerAtMs` ON `reminders` (`isCompleted`, `triggerAtMs`)")
    }
}
