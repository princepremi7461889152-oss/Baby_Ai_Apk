package com.example.domain.tools

import android.content.Context
import com.example.core.security.AutoReplyMode
import com.example.core.security.SecureKeyStore
import com.example.data.local.HistoryRepository
import com.example.features.macros.MacroManager
import com.example.features.memory.MemoryManager
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.reminders.ReminderManager
import com.example.features.routines.RoutineManager
import com.example.features.routines.RoutineStep
import com.example.features.safety.SafetyManager
import com.example.features.vision.CameraVisionManager
import com.example.features.vision.ClipboardAiManager
import com.example.features.vision.ScreenHelpManager
import com.example.domain.ai.AiRouter
import com.example.features.searchplay.SearchPlayEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtendedToolRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val historyRepository: HistoryRepository,
    private val routineManager: RoutineManager,
    private val memoryManager: MemoryManager,
    private val reminderManager: ReminderManager,
    private val notificationSummaryManager: NotificationSummaryManager,
    private val screenHelpManager: ScreenHelpManager,
    private val clipboardAiManager: ClipboardAiManager,
    private val cameraVisionManager: CameraVisionManager,
    private val macroManager: MacroManager,
    private val safetyManager: SafetyManager,
    private val searchPlayEngine: SearchPlayEngine?,
    private val aiRouter: AiRouter?
) {
    // Legacy 12-arg constructor for unit tests
    constructor(
        context: Context,
        keyStore: SecureKeyStore,
        historyRepository: HistoryRepository,
        routineManager: RoutineManager,
        memoryManager: MemoryManager,
        reminderManager: ReminderManager,
        notificationSummaryManager: NotificationSummaryManager,
        screenHelpManager: ScreenHelpManager,
        clipboardAiManager: ClipboardAiManager,
        cameraVisionManager: CameraVisionManager,
        macroManager: MacroManager,
        safetyManager: SafetyManager
    ) : this(
        context = context,
        keyStore = keyStore,
        historyRepository = historyRepository,
        routineManager = routineManager,
        memoryManager = memoryManager,
        reminderManager = reminderManager,
        notificationSummaryManager = notificationSummaryManager,
        screenHelpManager = screenHelpManager,
        clipboardAiManager = clipboardAiManager,
        cameraVisionManager = cameraVisionManager,
        macroManager = macroManager,
        safetyManager = safetyManager,
        searchPlayEngine = null,
        aiRouter = null
    )

    suspend fun tryExecute(name: String, args: Map<String, Any?>): ToolExecutionResult? {
        return when (name) {
            "search_web" -> {
                val query = args["query"]?.toString().orEmpty().trim()
                if (query.isBlank()) return ToolExecutionResult(false, "Search query empty hai.")
                val router = aiRouter ?: return null
                if (keyStore.settings.value.geminiApiKey.isBlank()) {
                    return null // Falls back to PhoneControlExecutor.searchWeb browser launch
                }
                val res = router.searchWebWithGrounding(
                    query = query,
                    userLanguageHint = keyStore.settings.value.preferredLanguage.name.lowercase()
                )
                res.fold(
                    onSuccess = { plan ->
                        val base = plan.replyText ?: "Web search complete."
                        val sourcesText = if (plan.sources.isNotEmpty()) {
                            "\n\nSources:\n" + plan.sources.take(4).joinToString("\n") { "- [${it.title}](${it.uri})" }
                        } else ""
                        ToolExecutionResult(true, "$base$sourcesText")
                    },
                    onFailure = {
                        null // Fall back cleanly to PhoneControlExecutor browser search
                    }
                )
            }

            "search_in_app" -> {
                val engine = searchPlayEngine ?: return ToolExecutionResult(false, "SearchPlayEngine unavailable.")
                val appName = args["app_name"]?.toString().orEmpty()
                val query = args["query"]?.toString().orEmpty()
                val autoPlay = when (val ap = args["auto_play"]) {
                    is Boolean -> ap
                    is String -> ap.equals("true", ignoreCase = true)
                    else -> true
                }
                engine.executeSearchAndPlay(appName, query, autoPlay)
            }

            "create_routine" -> {
                if (!keyStore.settings.value.routinesEnabled) {
                    return ToolExecutionResult(false, "Routines feature Settings mein OFF hai.")
                }
                val rName = args["name"]?.toString().orEmpty().ifBlank { "Custom Routine" }
                val trigger = args["trigger_phrase"]?.toString().orEmpty().ifBlank { rName.lowercase() }
                val rawActions = args["actions_json"]?.toString().orEmpty()
                val schedType = args["schedule_type"]?.toString()?.uppercase() ?: "MANUAL"
                val schedTime = args["schedule_time"]?.toString().orEmpty()
                val rawSteps = if (rawActions.isNotBlank()) {
                    RoutineManager.decodeSteps(rawActions)
                } else {
                    val tool = args["tool"]?.toString().orEmpty()
                    if (tool.isNotBlank()) listOf(RoutineStep(tool)) else emptyList()
                }
                val blockedCount = rawSteps.count { ToolDefinitions.isSensitiveAction(it.tool, it.args) }
                val id = routineManager.saveRoutine(rName, trigger, rawSteps, schedType, schedTime)
                val note = if (blockedCount > 0) " ($blockedCount sensitive action blocked)" else ""
                ToolExecutionResult(true, "Routine '$rName' saved (#$id) for phrase \"$trigger\".$note")
            }

            "run_routine" -> {
                if (!keyStore.settings.value.routinesEnabled) {
                    return ToolExecutionResult(false, "Routines feature Settings mein OFF hai.")
                }
                val phrase = args["trigger_phrase"]?.toString().orEmpty()
                val match = routineManager.findMatchingRoutine(phrase)
                    ?: return ToolExecutionResult(false, "Routine '$phrase' nahi mili.")
                val out = routineManager.runRoutine(match) { cmd ->
                    historyRepository.enqueueForUnlock(cmd, "routine")
                }
                ToolExecutionResult(true, out)
            }

            "list_routines" -> {
                ToolExecutionResult(true, routineManager.listRoutinesSummary())
            }

            "delete_routine" -> {
                val query = args["routine_name_or_phrase"]?.toString().orEmpty()
                ToolExecutionResult(true, routineManager.deleteRoutineByQuery(query))
            }

            "save_memory" -> {
                val key = args["key"]?.toString().orEmpty()
                val value = args["value"]?.toString().orEmpty()
                val cat = (args["type"] ?: args["category"])?.toString() ?: "FACT"
                ToolExecutionResult(true, memoryManager.saveMemory(key, value, cat))
            }

            "recall_memory" -> {
                val query = args["query"]?.toString().orEmpty()
                val list = memoryManager.recallRelevant(query, limit = 10)
                val out = if (list.isEmpty()) "Is baare mein koi memory saved nahi hai."
                else list.joinToString("\n") { "- [${it.category}] ${it.memoryKey}: ${it.memoryValue}" }
                ToolExecutionResult(true, out)
            }

            "delete_memory" -> {
                val key = args["key"]?.toString().orEmpty()
                ToolExecutionResult(true, memoryManager.deleteMemoryByKey(key))
            }

            "set_reminder" -> {
                if (!keyStore.settings.value.remindersEnabled) {
                    return ToolExecutionResult(false, "Reminders feature Settings mein OFF hai.")
                }
                val title = args["title"]?.toString().orEmpty().ifBlank { "Reminder" }
                val type = args["trigger_type"]?.toString()?.uppercase() ?: "TIME"
                if (type == "LOCATION") {
                    val out = reminderManager.createLocationReminder(
                        title = title,
                        placeLabel = args["place_label"]?.toString() ?: "Home",
                        latitude = (args["latitude"] as? Number)?.toDouble(),
                        longitude = (args["longitude"] as? Number)?.toDouble()
                    )
                    ToolExecutionResult(true, out)
                } else {
                    val timeExpr = args["time_expression"]?.toString()
                    val delayMin = (args["delay_minutes"] as? Number)?.toInt()
                    ToolExecutionResult(true, reminderManager.createNaturalReminder(title, timeExpr, delayMin))
                }
            }

            "list_reminders" -> {
                val inc = args["include_completed"] as? Boolean ?: false
                ToolExecutionResult(true, reminderManager.listRemindersSummary(inc))
            }

            "delete_reminder" -> {
                val q = args["query"]?.toString().orEmpty()
                ToolExecutionResult(true, reminderManager.deleteReminderByQuery(q))
            }

            "snooze_reminder" -> {
                val id = (args["reminder_id"] as? Number)?.toLong() ?: -1L
                val mins = (args["minutes"] as? Number)?.toInt() ?: 10
                if (id <= 0L) ToolExecutionResult(false, "Valid reminder_id chahiye.")
                else ToolExecutionResult(true, reminderManager.snoozeReminder(id, mins))
            }

            "save_place" -> {
                val label = args["label"]?.toString().orEmpty().ifBlank { "Home" }
                val useGps = args["use_current_gps"] as? Boolean ?: false
                val lat = (args["latitude"] as? Number)?.toDouble()
                val lon = (args["longitude"] as? Number)?.toDouble()
                val rad = (args["radius_meters"] as? Number)?.toFloat() ?: 200f
                val out = if (useGps || lat == null || lon == null) {
                    reminderManager.saveCurrentLocationAsPlace(label, rad)
                } else {
                    reminderManager.savePlace(label, lat, lon, rad)
                }
                ToolExecutionResult(true, out)
            }

            "summarize_notifications" -> {
                val scope = args["scope"]?.toString().orEmpty().ifBlank { "all" }
                val speak = args["speak"] as? Boolean ?: false
                val summary = notificationSummaryManager.buildSummary(scope = scope, speakAloud = speak)
                ToolExecutionResult(true, summary)
            }

            "set_auto_reply" -> {
                val onOffArg = args["on_off"] as? Boolean
                val modeStr = args["mode"]?.toString()?.uppercase()
                val targetMode = when {
                    onOffArg == false -> AutoReplyMode.OFF
                    modeStr != null -> runCatching { AutoReplyMode.valueOf(modeStr) }.getOrDefault(AutoReplyMode.BUSY)
                    else -> AutoReplyMode.BUSY
                }
                keyStore.setAutoReplyMode(targetMode)
                com.example.service.BabyForegroundService.refreshNotificationIfRunning(context)
                ToolExecutionResult(true, "Auto-reply mode set to ${targetMode.label}.")
            }

            "set_auto_reply_message" -> {
                val modeStr = args["mode"]?.toString()?.uppercase() ?: "CUSTOM"
                val text = args["text"]?.toString().orEmpty()
                if (text.isBlank()) return ToolExecutionResult(false, "Auto-reply message text empty hai.")
                val mode = runCatching { AutoReplyMode.valueOf(modeStr) }.getOrDefault(AutoReplyMode.CUSTOM)
                keyStore.updateAutoReplyMessageForMode(mode, text)
                ToolExecutionResult(true, "${mode.label} auto-reply message updated: \"$text\"")
            }

            "explain_screen" -> {
                val mode = args["mode"]?.toString().orEmpty().ifBlank { "explain" }
                val lang = args["target_language"]?.toString()
                val q = args["question"]?.toString()
                screenHelpManager.explainScreen(mode = mode, targetLanguage = lang, extraQuestion = q)
            }

            "read_clipboard_and_process", "read_clipboard" -> {
                val mode = args["mode"]?.toString().orEmpty().ifBlank { "summarize" }
                val lang = args["target_language"]?.toString()
                clipboardAiManager.processClipboard(mode = mode, targetLanguage = lang)
            }

            "capture_and_describe", "capture_photo" -> {
                val q = args["question"]?.toString().orEmpty().ifBlank { "Ye kya hai?" }
                cameraVisionManager.captureAndDescribe(question = q)
            }

            "create_command" -> {
                if (!keyStore.settings.value.customCommandsEnabled) {
                    return ToolExecutionResult(false, "Custom Commands Settings mein OFF hai.")
                }
                val cName = args["name"]?.toString().orEmpty().ifBlank { "Custom Command" }
                val trigger = args["trigger_phrase"]?.toString().orEmpty().ifBlank { cName.lowercase() }
                val rawActions = args["actions_json"]?.toString().orEmpty()
                val rawSteps = if (rawActions.isNotBlank()) {
                    RoutineManager.decodeSteps(rawActions)
                } else {
                    buildList {
                        (args["dnd_enable"] as? Boolean)?.let { dnd ->
                            add(RoutineStep("toggle_dnd", mapOf("enable" to dnd)))
                        }
                        (args["brightness_percent"] as? Number)?.toInt()?.let { br ->
                            add(RoutineStep("set_brightness", mapOf("level_percent" to br)))
                        }
                        (args["volume_percent"] as? Number)?.toInt()?.let { vol ->
                            add(RoutineStep("set_volume", mapOf("stream" to "media", "level_percent" to vol)))
                        }
                        args["open_app_name"]?.toString()?.takeIf { it.isNotBlank() }?.let { app ->
                            add(RoutineStep("open_app", mapOf("app_name" to app.trim())))
                        }
                        args["tool"]?.toString()?.takeIf { it.isNotBlank() }?.let { singleTool ->
                            add(RoutineStep(singleTool))
                        }
                    }
                }
                val blockedCount = rawSteps.count { ToolDefinitions.isSensitiveAction(it.tool, it.args) }
                val id = routineManager.saveCustomCommand(cName, trigger, rawSteps)
                val note = if (blockedCount > 0) " ($blockedCount sensitive action blocked)" else ""
                ToolExecutionResult(true, "Custom command '$cName' saved (#$id) for phrase \"$trigger\".$note")
            }

            "run_command" -> {
                if (!keyStore.settings.value.customCommandsEnabled) {
                    return ToolExecutionResult(false, "Custom Commands Settings mein OFF hai.")
                }
                val phrase = args["trigger_phrase"]?.toString().orEmpty()
                val match = routineManager.findMatchingCommand(phrase)
                    ?: return ToolExecutionResult(false, "Custom command '$phrase' nahi mila.")
                val out = routineManager.runRoutine(match) { cmd ->
                    historyRepository.enqueueForUnlock(cmd, "command")
                }
                ToolExecutionResult(true, out)
            }

            "list_commands" -> {
                ToolExecutionResult(true, routineManager.listCommandsSummary())
            }

            "delete_command" -> {
                val query = args["command_name_or_phrase"]?.toString().orEmpty()
                ToolExecutionResult(true, routineManager.deleteCommandByQuery(query))
            }

            "start_macro_recording" -> {
                val out = macroManager.startRecording()
                ToolExecutionResult(keyStore.settings.value.macrosEnabled, out)
            }

            "stop_macro_recording" -> {
                val name = args["name"]?.toString().orEmpty().ifBlank { "My Macro" }
                val phrase = args["trigger_phrase"]?.toString().orEmpty().ifBlank { name.lowercase() }
                val count = macroManager.recordedSteps.value.size
                val id = macroManager.stopAndSaveRecording(name, phrase)
                ToolExecutionResult(true, "Macro '$name' (#$id, $count steps) saved! Bolna \"$phrase chalao\" to replay.")
            }

            "run_macro" -> {
                if (!keyStore.settings.value.macrosEnabled) {
                    return ToolExecutionResult(false, "Macros Settings mein OFF hai.")
                }
                val phrase = args["trigger_phrase"]?.toString().orEmpty()
                val match = macroManager.findMatchingMacro(phrase)
                    ?: return ToolExecutionResult(false, "Macro '$phrase' nahi mila.")
                val out = macroManager.runMacro(match) { cmd ->
                    historyRepository.enqueueForUnlock(cmd, "macro")
                }
                ToolExecutionResult(true, out)
            }

            "list_macros" -> {
                ToolExecutionResult(true, macroManager.listMacrosSummary())
            }

            "delete_macro" -> {
                val query = args["macro_name_or_phrase"]?.toString().orEmpty()
                ToolExecutionResult(true, macroManager.deleteMacroByQuery(query))
            }

            "send_emergency_alert" -> {
                val alsoCall = args["also_call"] as? Boolean
                val out = safetyManager.triggerEmergencyAlert(alsoCall)
                ToolExecutionResult(true, out)
            }

            else -> null
        }
    }
}
