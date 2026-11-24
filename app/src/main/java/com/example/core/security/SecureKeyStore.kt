package com.example.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

enum class AppLanguage(val displayName: String, val localeTag: String, val instruction: String) {
    HINGLISH("Hinglish (Default)", "hi-IN", "Natural conversational Hinglish (mix of Hindi and English in Latin script)."),
    HINDI("Hindi", "hi-IN", "Natural conversational Hindi in Devanagari or simple Hindi."),
    ENGLISH("English", "en-IN", "Friendly, concise natural English.")
}

enum class PreferredBrain(val label: String) {
    AUTO("Smart Auto (Groq fast + Gemini heavy)"),
    GROQ_FIRST("Groq First (llama-3.3-70b-versatile)"),
    GEMINI_FIRST("Gemini First (Vision & Reasoning)")
}

enum class AutoReplyMode(val label: String) {
    OFF("Off"),
    BUSY("Busy"),
    DRIVING("Driving"),
    SLEEPING("Sleeping"),
    CUSTOM("Custom")
}

enum class BabyPersonality(val label: String, val toneInstruction: String) {
    FRIENDLY("Friendly", "Warm, caring, friendly Hinglish companion tone."),
    FUNNY("Funny", "Witty, playful, light-hearted Hinglish humor while staying helpful."),
    SERIOUS("Serious", "Crisp, direct, professional, zero-fluff tone.")
}

data class BabySettings(
    val geminiApiKey: String = "",
    val groqApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val groqModel: String = DEFAULT_GROQ_MODEL,
    val groqWhisperModel: String = DEFAULT_WHISPER_MODEL,
    val preferredBrain: PreferredBrain = PreferredBrain.AUTO,
    val autoFallbackEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = true,
    val wakeWordSensitivity: Float = 0.65f,
    val confirmSensitiveActions: Boolean = true,
    val preferredLanguage: AppLanguage = AppLanguage.HINGLISH,
    val floatingBubbleEnabled: Boolean = false,
    val ttsRepliesEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
    // Phase 1-3 feature toggles & configs
    val routinesEnabled: Boolean = true,
    val autoReplyEnabled: Boolean = false,
    val autoReplyMode: AutoReplyMode = AutoReplyMode.OFF,
    val autoReplyAiEnabled: Boolean = false,
    val autoReplyBusyMsg: String = "Abhi busy hoon, thodi der mein reply karta hoon.",
    val autoReplyDrivingMsg: String = "Abhi drive kar raha hoon, pahunch ke call karta hoon.",
    val autoReplySleepingMsg: String = "Abhi so raha hoon, subah baat karte hain.",
    val autoReplyCustomMsg: String = "Abhi available nahi hoon, baad mein reply karta hoon.",
    val autoReplyIgnoreGroups: Boolean = true,
    val autoReplyScheduleEnabled: Boolean = false,
    val autoReplyStartHour: Int = 22,
    val autoReplyStartMinute: Int = 0,
    val autoReplyEndHour: Int = 7,
    val autoReplyEndMinute: Int = 0,
    val memoryEnabled: Boolean = true,
    val actionLogEnabled: Boolean = true,
    val actionLogRetentionDays: Int = 30, // 7, 30, 90, -1 (forever)
    val notificationSummaryEnabled: Boolean = true,
    val readSensitiveNotificationsEnabled: Boolean = false,
    val scheduledSummaryMorningEnabled: Boolean = false,
    val scheduledSummaryEveningEnabled: Boolean = false,
    val scheduledSummarySpeakEnabled: Boolean = false,
    val remindersEnabled: Boolean = true,
    val locationRemindersEnabled: Boolean = false,
    val screenHelpEnabled: Boolean = true,
    val clipboardAiEnabled: Boolean = true,
    val cameraVisionEnabled: Boolean = true,
    val macrosEnabled: Boolean = true,
    val customCommandsEnabled: Boolean = true,
    val biometricSafetyEnabled: Boolean = false,
    val hasVoicePin: Boolean = false,
    val allowedAppsOnlyEnabled: Boolean = false,
    val emergencyModeEnabled: Boolean = true,
    val emergencyAutoCall: Boolean = false,
    val emergencyCustomMessage: String = DEFAULT_EMERGENCY_MSG,
    val emergencyPowerShortcutEnabled: Boolean = false,
    val offlineCommandsEnabled: Boolean = true,
    val personality: BabyPersonality = BabyPersonality.FRIENDLY,
    val dataSaverEnabled: Boolean = false,
    val lastTaskSummary: String = "Tap Mic to speak with Baby"
) {
    val hasAnyApiKey: Boolean get() = geminiApiKey.isNotBlank() || groqApiKey.isNotBlank()

    fun activeAutoReplyTemplate(): String = when (autoReplyMode) {
        AutoReplyMode.BUSY -> autoReplyBusyMsg
        AutoReplyMode.DRIVING -> autoReplyDrivingMsg
        AutoReplyMode.SLEEPING -> autoReplySleepingMsg
        AutoReplyMode.CUSTOM -> autoReplyCustomMsg
        AutoReplyMode.OFF -> ""
    }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_WHISPER_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_EMERGENCY_MSG = "EMERGENCY HELP NEEDED! Baby Assistant SOS alert."
    }
}

@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for rare Robolectric/OEM keystore edge cases while preserving app stability
            context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<BabySettings> = _settings.asStateFlow()

    init {
        migrateLegacyPlaintextPinIfPresent()
    }

    private fun migrateLegacyPlaintextPinIfPresent() {
        val legacy = prefs.getString(KEY_VOICE_PIN_LEGACY, null)
        if (!legacy.isNullOrBlank()) {
            setVoicePinCode(legacy)
            prefs.edit().remove(KEY_VOICE_PIN_LEGACY).apply()
            refresh()
        } else if (prefs.contains(KEY_VOICE_PIN_LEGACY)) {
            prefs.edit().remove(KEY_VOICE_PIN_LEGACY).apply()
        }
    }

    private fun readSettings(): BabySettings {
        val langName = prefs.getString(KEY_LANGUAGE, AppLanguage.HINGLISH.name) ?: AppLanguage.HINGLISH.name
        val brainName = prefs.getString(KEY_BRAIN, PreferredBrain.AUTO.name) ?: PreferredBrain.AUTO.name
        val replyModeName = prefs.getString(KEY_AUTO_REPLY_MODE, AutoReplyMode.OFF.name) ?: AutoReplyMode.OFF.name
        val personalityName = prefs.getString(KEY_PERSONALITY, BabyPersonality.FRIENDLY.name) ?: BabyPersonality.FRIENDLY.name
        return BabySettings(
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
            groqApiKey = prefs.getString(KEY_GROQ_API_KEY, "") ?: "",
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, BabySettings.DEFAULT_GEMINI_MODEL)
                ?: BabySettings.DEFAULT_GEMINI_MODEL,
            groqModel = prefs.getString(KEY_GROQ_MODEL, BabySettings.DEFAULT_GROQ_MODEL)
                ?: BabySettings.DEFAULT_GROQ_MODEL,
            groqWhisperModel = prefs.getString(KEY_WHISPER_MODEL, BabySettings.DEFAULT_WHISPER_MODEL)
                ?: BabySettings.DEFAULT_WHISPER_MODEL,
            preferredBrain = runCatching { PreferredBrain.valueOf(brainName) }.getOrDefault(PreferredBrain.AUTO),
            autoFallbackEnabled = prefs.getBoolean(KEY_AUTO_FALLBACK, true),
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true),
            wakeWordSensitivity = prefs.getFloat(KEY_WAKE_SENSITIVITY, 0.65f).coerceIn(0.1f, 1.0f),
            confirmSensitiveActions = prefs.getBoolean(KEY_CONFIRM_SENSITIVE, true),
            preferredLanguage = runCatching { AppLanguage.valueOf(langName) }.getOrDefault(AppLanguage.HINGLISH),
            floatingBubbleEnabled = prefs.getBoolean(KEY_FLOATING_BUBBLE, false),
            ttsRepliesEnabled = prefs.getBoolean(KEY_TTS_REPLIES, true),
            onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_DONE, false),
            routinesEnabled = prefs.getBoolean(KEY_ROUTINES_ENABLED, true),
            autoReplyEnabled = prefs.getBoolean(KEY_AUTO_REPLY_ENABLED, false),
            autoReplyMode = runCatching { AutoReplyMode.valueOf(replyModeName) }.getOrDefault(AutoReplyMode.OFF),
            autoReplyAiEnabled = prefs.getBoolean(KEY_AUTO_REPLY_AI, false),
            autoReplyBusyMsg = prefs.getString(KEY_AUTO_REPLY_BUSY_MSG, "Abhi busy hoon, thodi der mein reply karta hoon.") ?: "",
            autoReplyDrivingMsg = prefs.getString(KEY_AUTO_REPLY_DRIVING_MSG, "Abhi drive kar raha hoon, pahunch ke call karta hoon.") ?: "",
            autoReplySleepingMsg = prefs.getString(KEY_AUTO_REPLY_SLEEPING_MSG, "Abhi so raha hoon, subah baat karte hain.") ?: "",
            autoReplyCustomMsg = prefs.getString(KEY_AUTO_REPLY_CUSTOM_MSG, "Abhi available nahi hoon, baad mein reply karta hoon.") ?: "",
            autoReplyIgnoreGroups = prefs.getBoolean(KEY_AUTO_REPLY_IGNORE_GROUPS, true),
            autoReplyScheduleEnabled = prefs.getBoolean(KEY_AUTO_REPLY_SCHEDULE_ENABLED, false),
            autoReplyStartHour = prefs.getInt(KEY_AUTO_REPLY_START_HOUR, 22),
            autoReplyStartMinute = prefs.getInt(KEY_AUTO_REPLY_START_MINUTE, 0),
            autoReplyEndHour = prefs.getInt(KEY_AUTO_REPLY_END_HOUR, 7),
            autoReplyEndMinute = prefs.getInt(KEY_AUTO_REPLY_END_MINUTE, 0),
            memoryEnabled = prefs.getBoolean(KEY_MEMORY_ENABLED, true),
            actionLogEnabled = prefs.getBoolean(KEY_ACTION_LOG_ENABLED, true),
            actionLogRetentionDays = prefs.getInt(KEY_ACTION_LOG_RETENTION_DAYS, 30),
            notificationSummaryEnabled = prefs.getBoolean(KEY_NOTIFICATION_SUMMARY_ENABLED, true),
            readSensitiveNotificationsEnabled = prefs.getBoolean(KEY_READ_SENSITIVE_NOTIFS, false),
            scheduledSummaryMorningEnabled = prefs.getBoolean(KEY_SUMMARY_MORNING, false),
            scheduledSummaryEveningEnabled = prefs.getBoolean(KEY_SUMMARY_EVENING, false),
            scheduledSummarySpeakEnabled = prefs.getBoolean(KEY_SUMMARY_SPEAK, false),
            remindersEnabled = prefs.getBoolean(KEY_REMINDERS_ENABLED, true),
            locationRemindersEnabled = prefs.getBoolean(KEY_LOCATION_REMINDERS, false),
            screenHelpEnabled = prefs.getBoolean(KEY_SCREEN_HELP, true),
            clipboardAiEnabled = prefs.getBoolean(KEY_CLIPBOARD_AI, true),
            cameraVisionEnabled = prefs.getBoolean(KEY_CAMERA_VISION, true),
            macrosEnabled = prefs.getBoolean(KEY_MACROS_ENABLED, true),
            customCommandsEnabled = prefs.getBoolean(KEY_CUSTOM_COMMANDS_ENABLED, true),
            biometricSafetyEnabled = prefs.getBoolean(KEY_BIOMETRIC_SAFETY, false),
            hasVoicePin = !prefs.getString(KEY_VOICE_PIN_HASH, null).isNullOrBlank(),
            allowedAppsOnlyEnabled = prefs.getBoolean(KEY_ALLOWED_APPS_ONLY, false),
            emergencyModeEnabled = prefs.getBoolean(KEY_EMERGENCY_ENABLED, true),
            emergencyAutoCall = prefs.getBoolean(KEY_EMERGENCY_AUTO_CALL, false),
            emergencyCustomMessage = prefs.getString(KEY_EMERGENCY_CUSTOM_MSG, BabySettings.DEFAULT_EMERGENCY_MSG)
                ?.ifBlank { BabySettings.DEFAULT_EMERGENCY_MSG } ?: BabySettings.DEFAULT_EMERGENCY_MSG,
            emergencyPowerShortcutEnabled = prefs.getBoolean(KEY_EMERGENCY_POWER_SHORTCUT, false),
            offlineCommandsEnabled = prefs.getBoolean(KEY_OFFLINE_COMMANDS, true),
            personality = runCatching { BabyPersonality.valueOf(personalityName) }.getOrDefault(BabyPersonality.FRIENDLY),
            dataSaverEnabled = prefs.getBoolean(KEY_DATA_SAVER, false),
            lastTaskSummary = prefs.getString(KEY_LAST_TASK_SUMMARY, "Tap Mic to speak with Baby")
                ?: "Tap Mic to speak with Baby"
        )
    }

    fun updateApiKeys(geminiKey: String, groqKey: String) {
        prefs.edit()
            .putString(KEY_GEMINI_API_KEY, geminiKey.trim())
            .putString(KEY_GROQ_API_KEY, groqKey.trim())
            .apply()
        refresh()
    }

    fun updateGeminiModel(model: String) {
        prefs.edit().putString(KEY_GEMINI_MODEL, model.trim().ifBlank { BabySettings.DEFAULT_GEMINI_MODEL }).apply()
        refresh()
    }

    fun updateGroqModel(model: String) {
        prefs.edit().putString(KEY_GROQ_MODEL, model.trim().ifBlank { BabySettings.DEFAULT_GROQ_MODEL }).apply()
        refresh()
    }

    fun updatePreferredBrain(brain: PreferredBrain) {
        prefs.edit().putString(KEY_BRAIN, brain.name).apply()
        refresh()
    }

    fun setAutoFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FALLBACK, enabled).apply()
        refresh()
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
        refresh()
    }

    fun setWakeWordSensitivity(sensitivity: Float) {
        prefs.edit().putFloat(KEY_WAKE_SENSITIVITY, sensitivity.coerceIn(0.1f, 1.0f)).apply()
        refresh()
    }

    fun setConfirmSensitiveActions(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIRM_SENSITIVE, enabled).apply()
        refresh()
    }

    fun setPreferredLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
        refresh()
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_BUBBLE, enabled).apply()
        refresh()
    }

    fun setTtsRepliesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TTS_REPLIES, enabled).apply()
        refresh()
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, completed).apply()
        refresh()
    }

    fun clearApiKeys() {
        prefs.edit()
            .remove(KEY_GEMINI_API_KEY)
            .remove(KEY_GROQ_API_KEY)
            .apply()
        refresh()
    }

    fun setBooleanPref(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        refresh()
    }

    fun setAutoReplyMode(mode: AutoReplyMode) {
        val editor = prefs.edit()
            .putString(KEY_AUTO_REPLY_MODE, mode.name)
            .putBoolean(KEY_AUTO_REPLY_ENABLED, mode != AutoReplyMode.OFF)
        if (mode != AutoReplyMode.OFF) {
            editor.putString(KEY_AUTO_REPLY_LAST_MODE, mode.name)
        }
        editor.apply()
        refresh()
    }

    fun toggleAutoReplyQuick(): AutoReplyMode {
        val cur = _settings.value
        val next = if (cur.autoReplyEnabled && cur.autoReplyMode != AutoReplyMode.OFF) {
            AutoReplyMode.OFF
        } else {
            val last = prefs.getString(KEY_AUTO_REPLY_LAST_MODE, AutoReplyMode.BUSY.name)
            runCatching { AutoReplyMode.valueOf(last ?: AutoReplyMode.BUSY.name) }
                .getOrDefault(AutoReplyMode.BUSY)
                .takeIf { it != AutoReplyMode.OFF } ?: AutoReplyMode.BUSY
        }
        setAutoReplyMode(next)
        return next
    }

    fun updateAutoReplyTemplates(busy: String, driving: String, sleeping: String, custom: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_AUTO_REPLY_BUSY_MSG, busy.trim())
            .putString(KEY_AUTO_REPLY_DRIVING_MSG, driving.trim())
            .putString(KEY_AUTO_REPLY_SLEEPING_MSG, sleeping.trim())
        if (custom != null) {
            editor.putString(KEY_AUTO_REPLY_CUSTOM_MSG, custom.trim())
        }
        editor.apply()
        refresh()
    }

    fun updateAutoReplyMessageForMode(mode: AutoReplyMode, text: String) {
        val key = when (mode) {
            AutoReplyMode.BUSY -> KEY_AUTO_REPLY_BUSY_MSG
            AutoReplyMode.DRIVING -> KEY_AUTO_REPLY_DRIVING_MSG
            AutoReplyMode.SLEEPING -> KEY_AUTO_REPLY_SLEEPING_MSG
            AutoReplyMode.CUSTOM, AutoReplyMode.OFF -> KEY_AUTO_REPLY_CUSTOM_MSG
        }
        prefs.edit().putString(key, text.trim()).apply()
        refresh()
    }

    fun setAutoReplySchedule(enabled: Boolean, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        prefs.edit()
            .putBoolean(KEY_AUTO_REPLY_SCHEDULE_ENABLED, enabled)
            .putInt(KEY_AUTO_REPLY_START_HOUR, startHour.coerceIn(0, 23))
            .putInt(KEY_AUTO_REPLY_START_MINUTE, startMinute.coerceIn(0, 59))
            .putInt(KEY_AUTO_REPLY_END_HOUR, endHour.coerceIn(0, 23))
            .putInt(KEY_AUTO_REPLY_END_MINUTE, endMinute.coerceIn(0, 59))
            .apply()
        refresh()
    }

    fun setActionLogRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_ACTION_LOG_RETENTION_DAYS, days).apply()
        refresh()
    }

    fun setVoicePinCode(pin: String) {
        val clean = pin.trim()
        if (clean.isBlank()) {
            prefs.edit()
                .remove(KEY_VOICE_PIN_HASH)
                .remove(KEY_VOICE_PIN_SALT)
                .remove(KEY_VOICE_PIN_LEGACY)
                .apply()
        } else {
            val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val saltB64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
            val hashB64 = hashPin(clean, saltB64)
            prefs.edit()
                .putString(KEY_VOICE_PIN_SALT, saltB64)
                .putString(KEY_VOICE_PIN_HASH, hashB64)
                .remove(KEY_VOICE_PIN_LEGACY)
                .apply()
        }
        refresh()
    }

    fun verifyVoicePin(enteredPin: String): Boolean {
        val savedHash = prefs.getString(KEY_VOICE_PIN_HASH, null)
        val salt = prefs.getString(KEY_VOICE_PIN_SALT, null)
        if (savedHash.isNullOrBlank() || salt.isNullOrBlank()) return true
        val candidateHash = hashPin(enteredPin.trim(), salt)
        return MessageDigest.isEqual(
            savedHash.toByteArray(Charsets.UTF_8),
            candidateHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun setEmergencyCustomMessage(message: String) {
        val clean = message.trim().ifBlank { BabySettings.DEFAULT_EMERGENCY_MSG }
        prefs.edit().putString(KEY_EMERGENCY_CUSTOM_MSG, clean).apply()
        refresh()
    }

    fun setLastTaskSummary(summary: String) {
        val clean = summary.trim().take(80).ifBlank { "Ready" }
        prefs.edit().putString(KEY_LAST_TASK_SUMMARY, clean).apply()
        refresh()
    }

    fun setPersonality(personality: BabyPersonality) {
        prefs.edit().putString(KEY_PERSONALITY, personality.name).apply()
        refresh()
    }

    private fun hashPin(pin: String, saltB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$saltB64:$pin".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun refresh() {
        _settings.value = readSettings()
    }

    companion object {
        private const val PREFS_FILE = "baby_secure_keystore_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_WHISPER_MODEL = "groq_whisper_model"
        private const val KEY_BRAIN = "preferred_brain"
        private const val KEY_AUTO_FALLBACK = "auto_fallback"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_WAKE_SENSITIVITY = "wake_word_sensitivity"
        private const val KEY_CONFIRM_SENSITIVE = "confirm_sensitive"
        private const val KEY_LANGUAGE = "preferred_language"
        private const val KEY_FLOATING_BUBBLE = "floating_bubble"
        private const val KEY_TTS_REPLIES = "tts_replies"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"

        const val KEY_ROUTINES_ENABLED = "routines_enabled"
        const val KEY_AUTO_REPLY_ENABLED = "auto_reply_enabled"
        const val KEY_AUTO_REPLY_MODE = "auto_reply_mode"
        const val KEY_AUTO_REPLY_AI = "auto_reply_ai"
        const val KEY_AUTO_REPLY_BUSY_MSG = "auto_reply_busy_msg"
        const val KEY_AUTO_REPLY_DRIVING_MSG = "auto_reply_driving_msg"
        const val KEY_AUTO_REPLY_SLEEPING_MSG = "auto_reply_sleeping_msg"
        const val KEY_AUTO_REPLY_CUSTOM_MSG = "auto_reply_custom_msg"
        const val KEY_AUTO_REPLY_LAST_MODE = "auto_reply_last_mode"
        const val KEY_AUTO_REPLY_IGNORE_GROUPS = "auto_reply_ignore_groups"
        const val KEY_AUTO_REPLY_SCHEDULE_ENABLED = "auto_reply_schedule_enabled"
        const val KEY_AUTO_REPLY_START_HOUR = "auto_reply_start_hour"
        const val KEY_AUTO_REPLY_START_MINUTE = "auto_reply_start_minute"
        const val KEY_AUTO_REPLY_END_HOUR = "auto_reply_end_hour"
        const val KEY_AUTO_REPLY_END_MINUTE = "auto_reply_end_minute"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_ACTION_LOG_ENABLED = "action_log_enabled"
        const val KEY_ACTION_LOG_RETENTION_DAYS = "action_log_retention_days"
        const val KEY_NOTIFICATION_SUMMARY_ENABLED = "notification_summary_enabled"
        const val KEY_READ_SENSITIVE_NOTIFS = "read_sensitive_notifs"
        const val KEY_SUMMARY_MORNING = "summary_morning"
        const val KEY_SUMMARY_EVENING = "summary_evening"
        const val KEY_SUMMARY_SPEAK = "summary_speak"
        const val KEY_REMINDERS_ENABLED = "reminders_enabled"
        const val KEY_LOCATION_REMINDERS = "location_reminders"
        const val KEY_SCREEN_HELP = "screen_help"
        const val KEY_CLIPBOARD_AI = "clipboard_ai"
        const val KEY_CAMERA_VISION = "camera_vision"
        const val KEY_MACROS_ENABLED = "macros_enabled"
        const val KEY_CUSTOM_COMMANDS_ENABLED = "custom_commands_enabled"
        const val KEY_BIOMETRIC_SAFETY = "biometric_safety"
        private const val KEY_VOICE_PIN_LEGACY = "voice_pin"
        private const val KEY_VOICE_PIN_HASH = "voice_pin_hash"
        private const val KEY_VOICE_PIN_SALT = "voice_pin_salt"
        const val KEY_ALLOWED_APPS_ONLY = "allowed_apps_only"
        const val KEY_EMERGENCY_ENABLED = "emergency_enabled"
        const val KEY_EMERGENCY_AUTO_CALL = "emergency_auto_call"
        const val KEY_EMERGENCY_CUSTOM_MSG = "emergency_custom_msg"
        const val KEY_EMERGENCY_POWER_SHORTCUT = "emergency_power_shortcut"
        const val KEY_OFFLINE_COMMANDS = "offline_commands"
        const val KEY_PERSONALITY = "baby_personality"
        const val KEY_DATA_SAVER = "data_saver"
        const val KEY_LAST_TASK_SUMMARY = "last_task_summary"
    }
}
