package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BabyDatabase
import com.example.data.local.MIGRATION_1_2
import com.example.data.local.MIGRATION_2_3
import com.example.data.local.MIGRATION_3_4
import com.example.data.local.MIGRATION_4_5
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationTest {

    private lateinit var context: Context
    private val createdDbNames = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        createdDbNames.forEach { context.deleteDatabase(it) }
    }

    private fun openMigratedDb(dbName: String): BabyDatabase {
        return Room.databaseBuilder(context, BabyDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `migrate version 1 through 5 preserves chat, task runs, and queued tasks without destructive fallback`() = runBlocking {
        val dbName = "migration_v1_v5_${System.nanoTime()}.db"
        createdDbNames += dbName
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val helper = object : SQLiteOpenHelper(context, dbName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `brainUsed` TEXT,
                        `toolName` TEXT,
                        `toolArgsJson` TEXT,
                        `toolResult` TEXT,
                        `languageHint` TEXT,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_runs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `commandText` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `stepsCount` INTEGER NOT NULL,
                        `maxSteps` INTEGER NOT NULL,
                        `brainSummary` TEXT NOT NULL,
                        `finalReply` TEXT NOT NULL,
                        `stepLogsJson` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `finishedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_queued_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `commandText` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.use { rawDb ->
            rawDb.execSQL(
                "INSERT INTO chat_messages (id, role, content, brainUsed, languageHint, timestamp) VALUES (1, 'user', 'Flashlight on karo', 'groq', 'hinglish', 1000)"
            )
            rawDb.execSQL(
                "INSERT INTO task_runs (id, commandText, status, stepsCount, maxSteps, brainSummary, finalReply, stepLogsJson, startedAt, finishedAt) VALUES (10, 'Flashlight on karo', 'COMPLETED', 1, 15, 'Groq', 'ON', '[]', 1000, 1100)"
            )
            rawDb.execSQL(
                "INSERT INTO pending_queued_tasks (id, commandText, source, createdAt) VALUES (5, 'WhatsApp kholo', 'voice', 1200)"
            )
        }
        helper.close()

        val roomDb = openMigratedDb(dbName)
        try {
            val msgs = roomDb.chatDao().getRecentMessages(10)
            assertEquals(1, msgs.size)
            assertEquals("Flashlight on karo", msgs.first().content)

            val run = roomDb.taskRunDao().getById(10L)
            assertEquals("COMPLETED", run?.status)

            val queued = roomDb.queuedTaskDao().getAllQueuedTasks()
            assertEquals(1, queued.size)
            assertEquals("WhatsApp kholo", queued.first().commandText)

            // Verify v2..v5 tables & indexes exist and are writable
            val sqlDb = roomDb.openHelper.writableDatabase
            val indexNames = mutableListOf<String>()
            sqlDb.query("SELECT name FROM sqlite_master WHERE type='index'").use { c ->
                while (c.moveToNext()) indexNames += c.getString(0)
            }
            assertTrue(indexNames.contains("index_chat_messages_timestamp"))
            assertTrue(indexNames.contains("index_task_runs_startedAt"))
            assertTrue(indexNames.contains("index_action_logs_timestamp"))
            assertTrue(indexNames.contains("index_action_logs_status"))
            assertTrue(indexNames.contains("index_action_logs_source"))
            assertTrue(indexNames.contains("index_reminders_isCompleted_triggerAtMs"))
            assertTrue(indexNames.contains("index_saved_places_placeKey"))
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun `migrate version 2 through 5 preserves routines, memories, and action logs with default source`() = runBlocking {
        val dbName = "migration_v2_v5_${System.nanoTime()}.db"
        createdDbNames += dbName
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val helper = object : SQLiteOpenHelper(context, dbName, null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `brainUsed` TEXT, `toolName` TEXT, `toolArgsJson` TEXT, `toolResult` TEXT, `languageHint` TEXT, `timestamp` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `commandText` TEXT NOT NULL, `status` TEXT NOT NULL, `stepsCount` INTEGER NOT NULL, `maxSteps` INTEGER NOT NULL, `brainSummary` TEXT NOT NULL, `finalReply` TEXT NOT NULL, `stepLogsJson` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pending_queued_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `commandText` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `triggerPhrase` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `isBuiltIn` INTEGER NOT NULL, `scheduleType` TEXT NOT NULL, `scheduleTime` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `auto_reply_rules` (`contactKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `ruleType` TEXT NOT NULL, `lastRepliedAt` INTEGER NOT NULL, PRIMARY KEY(`contactKey`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, `memoryKey` TEXT NOT NULL, `memoryValue` TEXT NOT NULL, `usageCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `action_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `commandText` TEXT NOT NULL, `toolName` TEXT NOT NULL, `argsSummary` TEXT NOT NULL, `resultText` TEXT NOT NULL, `targetApp` TEXT NOT NULL, `status` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `triggerType` TEXT NOT NULL, `triggerAtMs` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, `radiusMeters` REAL NOT NULL, `placeLabel` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `macros` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `triggerPhrase` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `allowed_apps` (`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `isAllowed` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `emergency_contacts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phoneNumber` TEXT NOT NULL)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.use { rawDb ->
            rawDb.execSQL(
                "INSERT INTO memories (id, category, memoryKey, memoryValue, usageCount, updatedAt) VALUES (1, 'NICKNAME', 'Mummy', '9876543210', 3, 2000)"
            )
            rawDb.execSQL(
                "INSERT INTO action_logs (id, timestamp, commandText, toolName, argsSummary, resultText, targetApp, status) VALUES (1, 5000, 'Torch on', 'toggle_flashlight', '{}', 'ON', 'System', 'OK')"
            )
            rawDb.execSQL(
                "INSERT INTO reminders (id, title, triggerType, triggerAtMs, latitude, longitude, radiusMeters, placeLabel, isCompleted, createdAt) VALUES (7, 'Doodh lena', 'TIME', 900000, NULL, NULL, 200, '', 0, 2000)"
            )
        }
        helper.close()

        val roomDb = openMigratedDb(dbName)
        try {
            val memories = roomDb.memoryDao().getAllSnapshot()
            assertEquals(1, memories.size)
            assertEquals("9876543210", memories.first().memoryValue)
            assertEquals(0L, memories.first().createdAt)

            val logs = roomDb.actionLogDao().getAllForExport()
            assertEquals(1, logs.size)
            assertEquals("text", logs.first().source)

            val reminders = roomDb.reminderDao().getAllActiveSnapshot()
            assertEquals(1, reminders.size)
            assertEquals("Doodh lena", reminders.first().title)
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun `migrate version 4 to 5 preserves saved places and adds performance indexes`() = runBlocking {
        val dbName = "migration_v4_v5_${System.nanoTime()}.db"
        createdDbNames += dbName
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val helper = object : SQLiteOpenHelper(context, dbName, null, 4) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `brainUsed` TEXT, `toolName` TEXT, `toolArgsJson` TEXT, `toolResult` TEXT, `languageHint` TEXT, `timestamp` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `commandText` TEXT NOT NULL, `status` TEXT NOT NULL, `stepsCount` INTEGER NOT NULL, `maxSteps` INTEGER NOT NULL, `brainSummary` TEXT NOT NULL, `finalReply` TEXT NOT NULL, `stepLogsJson` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pending_queued_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `commandText` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `triggerPhrase` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `isBuiltIn` INTEGER NOT NULL, `scheduleType` TEXT NOT NULL, `scheduleTime` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `auto_reply_rules` (`contactKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `ruleType` TEXT NOT NULL, `lastRepliedAt` INTEGER NOT NULL, PRIMARY KEY(`contactKey`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, `memoryKey` TEXT NOT NULL, `memoryValue` TEXT NOT NULL, `usageCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `action_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `commandText` TEXT NOT NULL, `toolName` TEXT NOT NULL, `argsSummary` TEXT NOT NULL, `resultText` TEXT NOT NULL, `targetApp` TEXT NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `triggerType` TEXT NOT NULL, `triggerAtMs` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, `radiusMeters` REAL NOT NULL, `placeLabel` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `macros` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `triggerPhrase` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `allowed_apps` (`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `isAllowed` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `emergency_contacts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phoneNumber` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `saved_places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `placeKey` TEXT NOT NULL, `label` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `radiusMeters` REAL NOT NULL, `addressHint` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_places_placeKey` ON `saved_places` (`placeKey`)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.use { rawDb ->
            rawDb.execSQL(
                "INSERT INTO saved_places (id, placeKey, label, latitude, longitude, radiusMeters, addressHint, updatedAt) VALUES (1, 'home', 'Home', 28.6139, 77.2090, 250.0, 'New Delhi', 9000)"
            )
        }
        helper.close()

        val roomDb = openMigratedDb(dbName)
        try {
            val places = roomDb.savedPlaceDao().getAllSnapshot()
            assertEquals(1, places.size)
            assertEquals("Home", places.first().label)
            assertEquals(28.6139, places.first().latitude, 0.0001)
        } finally {
            roomDb.close()
        }
    }
}
