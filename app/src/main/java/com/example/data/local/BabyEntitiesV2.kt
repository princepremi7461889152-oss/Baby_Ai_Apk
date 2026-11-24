package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerPhrase: String,
    val stepsJson: String, // JSON array of {tool, args}
    val isBuiltIn: Boolean = false,
    val scheduleType: String = "MANUAL", // "MANUAL", "TIME", "CHARGING"
    val scheduleTime: String = "", // e.g. "07:30"
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "auto_reply_rules")
data class AutoReplyContactRuleEntity(
    @PrimaryKey val contactKey: String, // normalized phone/name/sender
    val displayName: String,
    val ruleType: String, // "ALLOW", "BLOCK", "NEVER"
    val lastRepliedAt: Long = 0L
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // "nickname", "preference", "fact", "habit"
    val memoryKey: String,
    val memoryValue: String,
    val usageCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val commandText: String,
    val toolName: String,
    val argsSummary: String,
    val resultText: String,
    val targetApp: String = "",
    val status: String = "OK", // "OK", "ERR", "BLOCKED", "QUEUED", "AUTO_REPLY"
    val source: String = "text" // "voice", "text", "routine", "auto-reply", "macro"
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerType: String, // "TIME", "LOCATION"
    val triggerAtMs: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Float = 200f,
    val placeLabel: String = "",
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerPhrase: String,
    val stepsJson: String, // JSON array of {type, tool, args, x, y, text}
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "allowed_apps")
data class AllowedAppEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val isAllowed: Boolean = true
)

@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phoneNumber: String
)

@Entity(
    tableName = "saved_places",
    indices = [Index(value = ["placeKey"], unique = true)]
)
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeKey: String, // e.g. "home", "work"
    val label: String, // Display name e.g. "Home", "Work"
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 200f,
    val addressHint: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
