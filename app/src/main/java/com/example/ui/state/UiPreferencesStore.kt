package com.example.ui.state

import android.content.Context
import android.content.SharedPreferences
import com.example.ui.theme.BabyThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class QuickCommandItem(
    val id: String,
    val phrase: String,
    val usageCount: Int = 0,
    val isPinned: Boolean = false
)

data class BabyUiPreferences(
    val themeMode: BabyThemeMode = BabyThemeMode.DARK,
    val dynamicColorEnabled: Boolean = false,
    val listeningSoundCuesEnabled: Boolean = true,
    val apiKeyBannerDismissed: Boolean = false,
    val onboardingStep: Int = 0,
    val quickCommands: List<QuickCommandItem> = DEFAULT_QUICK_COMMANDS,
    val dismissedTaskRunIds: Set<Long> = emptySet(),
    val dismissedActionLogIds: Set<Long> = emptySet(),
    val routineIcons: Map<String, String> = emptyMap()
) {
    val orderedQuickCommands: List<QuickCommandItem>
        get() = quickCommands.sortedWith(
            compareByDescending<QuickCommandItem> { it.isPinned }
                .thenByDescending { it.usageCount }
        )

    companion object {
        val DEFAULT_QUICK_COMMANDS = listOf(
            QuickCommandItem("qc_flash", "Flashlight on karo"),
            QuickCommandItem("qc_notifs", "Notifications padho"),
            QuickCommandItem("qc_call", "Mummy ko call karo"),
            QuickCommandItem("qc_alarm", "7 baje ka alarm lagao"),
            QuickCommandItem("qc_wifi", "Wi-Fi settings kholo"),
            QuickCommandItem("qc_screen", "What's on my screen?")
        )
    }
}

@Singleton
class UiPreferencesStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("baby_ui_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadFromPrefs())
    val state: StateFlow<BabyUiPreferences> = _state.asStateFlow()

    private fun loadFromPrefs(): BabyUiPreferences {
        val themeName = prefs.getString(KEY_THEME_MODE, BabyThemeMode.DARK.name)
        val themeMode = BabyThemeMode.entries.firstOrNull { it.name == themeName } ?: BabyThemeMode.DARK
        val quickCommands = decodeQuickCommands(prefs.getString(KEY_QUICK_COMMANDS, null))
        val dismissedTasks = decodeIdSet(prefs.getString(KEY_DISMISSED_TASKS, null))
        val dismissedLogs = decodeIdSet(prefs.getString(KEY_DISMISSED_LOGS, null))
        val routineIcons = decodeStringMap(prefs.getString(KEY_ROUTINE_ICONS, null))

        return BabyUiPreferences(
            themeMode = themeMode,
            dynamicColorEnabled = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
            listeningSoundCuesEnabled = prefs.getBoolean(KEY_SOUND_CUES, true),
            apiKeyBannerDismissed = prefs.getBoolean(KEY_BANNER_DISMISSED, false),
            onboardingStep = prefs.getInt(KEY_ONBOARDING_STEP, 0).coerceIn(0, 4),
            quickCommands = quickCommands,
            dismissedTaskRunIds = dismissedTasks,
            dismissedActionLogIds = dismissedLogs,
            routineIcons = routineIcons
        )
    }

    fun setThemeMode(mode: BabyThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _state.value = _state.value.copy(dynamicColorEnabled = enabled)
    }

    fun setListeningSoundCuesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND_CUES, enabled).apply()
        _state.value = _state.value.copy(listeningSoundCuesEnabled = enabled)
    }

    fun setApiKeyBannerDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_BANNER_DISMISSED, dismissed).apply()
        _state.value = _state.value.copy(apiKeyBannerDismissed = dismissed)
    }

    fun setOnboardingStep(step: Int) {
        val clamped = step.coerceIn(0, 4)
        prefs.edit().putInt(KEY_ONBOARDING_STEP, clamped).apply()
        _state.value = _state.value.copy(onboardingStep = clamped)
    }

    fun recordQuickCommandUsage(phrase: String) {
        val clean = phrase.trim()
        if (clean.isEmpty()) return
        val current = _state.value.quickCommands
        val idx = current.indexOfFirst { it.phrase.equals(clean, ignoreCase = true) }
        if (idx >= 0) {
            val updated = current.toMutableList()
            val item = updated[idx]
            updated[idx] = item.copy(usageCount = item.usageCount + 1)
            saveQuickCommands(updated)
        }
    }

    fun toggleQuickCommandPin(id: String) {
        val updated = _state.value.quickCommands.map { item ->
            if (item.id == id) item.copy(isPinned = !item.isPinned) else item
        }
        saveQuickCommands(updated)
    }

    fun updateQuickCommandPhrase(id: String, newPhrase: String) {
        val clean = newPhrase.trim()
        if (clean.isEmpty()) return
        val updated = _state.value.quickCommands.map { item ->
            if (item.id == id) item.copy(phrase = clean) else item
        }
        saveQuickCommands(updated)
    }

    fun addQuickCommand(phrase: String) {
        val clean = phrase.trim()
        if (clean.isEmpty()) return
        val exists = _state.value.quickCommands.any { it.phrase.equals(clean, ignoreCase = true) }
        if (exists) return
        val updated = _state.value.quickCommands + QuickCommandItem(
            id = "qc_${System.currentTimeMillis()}",
            phrase = clean,
            usageCount = 1,
            isPinned = true
        )
        saveQuickCommands(updated)
    }

    fun dismissTaskRun(id: Long) {
        val updated = _state.value.dismissedTaskRunIds + id
        prefs.edit().putString(KEY_DISMISSED_TASKS, encodeIdSet(updated)).apply()
        _state.value = _state.value.copy(dismissedTaskRunIds = updated)
    }

    fun restoreTaskRun(id: Long) {
        val updated = _state.value.dismissedTaskRunIds - id
        prefs.edit().putString(KEY_DISMISSED_TASKS, encodeIdSet(updated)).apply()
        _state.value = _state.value.copy(dismissedTaskRunIds = updated)
    }

    fun clearDismissedTasks() {
        prefs.edit().remove(KEY_DISMISSED_TASKS).apply()
        _state.value = _state.value.copy(dismissedTaskRunIds = emptySet())
    }

    fun dismissActionLog(id: Long) {
        val updated = _state.value.dismissedActionLogIds + id
        prefs.edit().putString(KEY_DISMISSED_LOGS, encodeIdSet(updated)).apply()
        _state.value = _state.value.copy(dismissedActionLogIds = updated)
    }

    fun restoreActionLog(id: Long) {
        val updated = _state.value.dismissedActionLogIds - id
        prefs.edit().putString(KEY_DISMISSED_LOGS, encodeIdSet(updated)).apply()
        _state.value = _state.value.copy(dismissedActionLogIds = updated)
    }

    fun clearDismissedLogs() {
        prefs.edit().remove(KEY_DISMISSED_LOGS).apply()
        _state.value = _state.value.copy(dismissedActionLogIds = emptySet())
    }

    fun setRoutineIcon(triggerKey: String, iconKey: String) {
        val cleanKey = triggerKey.trim().lowercase()
        if (cleanKey.isEmpty()) return
        val updated = _state.value.routineIcons.toMutableMap().apply {
            this[cleanKey] = iconKey
        }
        val json = JSONObject()
        updated.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_ROUTINE_ICONS, json.toString()).apply()
        _state.value = _state.value.copy(routineIcons = updated)
    }

    private fun saveQuickCommands(list: List<QuickCommandItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("phrase", item.phrase)
                    put("usage", item.usageCount)
                    put("pinned", item.isPinned)
                }
            )
        }
        prefs.edit().putString(KEY_QUICK_COMMANDS, arr.toString()).apply()
        _state.value = _state.value.copy(quickCommands = list)
    }

    private fun decodeQuickCommands(raw: String?): List<QuickCommandItem> {
        if (raw.isNullOrBlank()) return BabyUiPreferences.DEFAULT_QUICK_COMMANDS
        return runCatching {
            val arr = JSONArray(raw)
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        QuickCommandItem(
                            id = obj.optString("id", "qc_$i"),
                            phrase = obj.optString("phrase", ""),
                            usageCount = obj.optInt("usage", 0),
                            isPinned = obj.optBoolean("pinned", false)
                        )
                    )
                }
            }.filter { it.phrase.isNotBlank() }
            list.ifEmpty { BabyUiPreferences.DEFAULT_QUICK_COMMANDS }
        }.getOrDefault(BabyUiPreferences.DEFAULT_QUICK_COMMANDS)
    }

    private fun encodeIdSet(ids: Set<Long>): String = ids.joinToString(",")

    private fun decodeIdSet(raw: String?): Set<Long> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    private fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k ->
                    val v = obj.optString(k)
                    if (v.isNotBlank()) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val KEY_THEME_MODE = "ui_theme_mode"
        private const val KEY_DYNAMIC_COLOR = "ui_dynamic_color"
        private const val KEY_SOUND_CUES = "ui_sound_cues"
        private const val KEY_BANNER_DISMISSED = "ui_banner_dismissed"
        private const val KEY_ONBOARDING_STEP = "ui_onboarding_step"
        private const val KEY_QUICK_COMMANDS = "ui_quick_commands"
        private const val KEY_DISMISSED_TASKS = "ui_dismissed_tasks"
        private const val KEY_DISMISSED_LOGS = "ui_dismissed_logs"
        private const val KEY_ROUTINE_ICONS = "ui_routine_icons"
    }
}
