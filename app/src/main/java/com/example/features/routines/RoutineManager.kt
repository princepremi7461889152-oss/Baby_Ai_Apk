package com.example.features.routines

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.core.security.SecureKeyStore
import com.example.data.local.RoutineDao
import com.example.data.local.RoutineEntity
import com.example.domain.tools.PhoneControlExecutor
import com.example.domain.tools.StopSignalBus
import com.example.domain.tools.ToolDefinitions
import com.example.features.actionlog.ActionLogRepository
import com.example.features.notifications.NotificationSummaryManager
import com.example.util.PermissionHelper
import com.example.voice.BabyTtsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class RoutineStep(
    val tool: String,
    val args: Map<String, Any?> = emptyMap()
)

@Singleton
class RoutineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routineDao: RoutineDao,
    private val executor: PhoneControlExecutor,
    private val keyStore: SecureKeyStore,
    private val permissionHelper: PermissionHelper,
    private val ttsManager: BabyTtsManager,
    private val actionLog: ActionLogRepository,
    private val notificationSummaryManager: NotificationSummaryManager,
    private val okHttpClient: OkHttpClient,
    private val stopSignalBus: StopSignalBus
) {
    val routines: Flow<List<RoutineEntity>> = routineDao.observeAll()

    suspend fun ensureBuiltInRoutinesSeeded() {
        if (routineDao.count() > 0) return
        val builtins = listOf(
            RoutineEntity(
                name = "Good Morning",
                triggerPhrase = "good morning",
                stepsJson = encodeSteps(
                    listOf(
                        RoutineStep("stop_alarm", emptyMap()),
                        RoutineStep("speak_weather", emptyMap()),
                        RoutineStep("read_calendar_events", emptyMap()),
                        RoutineStep("summarize_notifications", mapOf("speak" to true))
                    )
                ),
                isBuiltIn = true,
                scheduleType = "MANUAL",
                scheduleTime = "07:30"
            ),
            RoutineEntity(
                name = "Good Night",
                triggerPhrase = "good night",
                stepsJson = encodeSteps(
                    listOf(
                        RoutineStep("toggle_dnd", mapOf("enable" to true)),
                        RoutineStep("set_brightness", mapOf("level_percent" to 15)),
                        RoutineStep("set_volume", mapOf("stream" to "media", "level_percent" to 20)),
                        RoutineStep("speak", mapOf("text" to "Good night! DND on kar diya hai, aaram se so jao.", "language" to "hinglish"))
                    )
                ),
                isBuiltIn = true,
                scheduleType = "MANUAL",
                scheduleTime = "22:30"
            ),
            RoutineEntity(
                name = "Leaving Home",
                triggerPhrase = "leaving home",
                stepsJson = encodeSteps(
                    listOf(
                        RoutineStep("toggle_bluetooth", mapOf("enable" to true)),
                        RoutineStep("toggle_wifi", mapOf("enable" to false)),
                        RoutineStep("speak", mapOf("text" to "Safe journey! Bluetooth on aur Wi-Fi panel khol diya hai.", "language" to "hinglish"))
                    )
                ),
                isBuiltIn = true
            ),
            RoutineEntity(
                name = "Reached Home",
                triggerPhrase = "reached home",
                stepsJson = encodeSteps(
                    listOf(
                        RoutineStep("toggle_dnd", mapOf("enable" to false)),
                        RoutineStep("set_brightness", mapOf("level_percent" to 55)),
                        RoutineStep("read_notifications", emptyMap()),
                        RoutineStep("speak", mapOf("text" to "Welcome home! Sab normal kar diya hai.", "language" to "hinglish"))
                    )
                ),
                isBuiltIn = true
            )
        )
        builtins.forEach { routineDao.upsert(it) }
    }

    suspend fun saveRoutine(
        name: String,
        triggerPhrase: String,
        steps: List<RoutineStep>,
        scheduleType: String = "MANUAL",
        scheduleTime: String = ""
    ): Long {
        val safeSteps = sanitizeSteps(steps)
        val entity = RoutineEntity(
            name = name.trim(),
            triggerPhrase = triggerPhrase.trim().lowercase(),
            stepsJson = encodeSteps(safeSteps),
            isBuiltIn = false,
            scheduleType = scheduleType.uppercase(),
            scheduleTime = scheduleTime.trim()
        )
        val id = routineDao.upsert(entity)
        scheduleWorkIfNeeded(entity.copy(id = id))
        return id
    }

    suspend fun updateRoutine(
        id: Long,
        name: String,
        triggerPhrase: String,
        steps: List<RoutineStep>,
        scheduleType: String,
        scheduleTime: String
    ): Boolean {
        val current = routineDao.getById(id)
        val safeSteps = sanitizeSteps(steps)
        val entity = RoutineEntity(
            id = id,
            name = name.trim(),
            triggerPhrase = triggerPhrase.trim().lowercase(),
            stepsJson = encodeSteps(safeSteps),
            isBuiltIn = current?.isBuiltIn ?: false,
            isEnabled = current?.isEnabled ?: true,
            scheduleType = scheduleType.uppercase(),
            scheduleTime = scheduleTime.trim()
        )
        routineDao.upsert(entity)
        WorkManager.getInstance(context).cancelUniqueWork("routine_$id")
        scheduleWorkIfNeeded(entity)
        return safeSteps.size == steps.size
    }

    suspend fun deleteRoutine(id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("routine_$id")
        routineDao.deleteById(id)
    }

    suspend fun listRoutinesSummary(): String {
        ensureBuiltInRoutinesSeeded()
        val enabled = routineDao.getEnabledRoutines().filter { it.scheduleType != SCHEDULE_TYPE_COMMAND }
        if (enabled.isEmpty()) return "Abhi koi enabled routine nahi hai."
        return enabled.joinToString("\n") { r ->
            val stepCount = decodeSteps(r.stepsJson).size
            "- ${r.name} (say \"${r.triggerPhrase}\") • $stepCount steps • ${r.scheduleType}"
        }
    }

    suspend fun deleteRoutineByQuery(query: String): String {
        ensureBuiltInRoutinesSeeded()
        val q = query.trim().lowercase()
        if (q.isBlank()) return "Kaunsi routine delete karni hai, naam ya trigger phrase batao."
        val target = routineDao.getEnabledRoutines()
            .filter { it.scheduleType != SCHEDULE_TYPE_COMMAND }
            .firstOrNull {
                it.name.lowercase().contains(q) || it.triggerPhrase.lowercase().contains(q)
            } ?: return "'$query' naam ki koi enabled routine nahi mili."
        if (target.isBuiltIn) {
            return "'${target.name}' built-in routine hai; aap Routines screen se iska toggle OFF kar sakte ho."
        }
        deleteRoutine(target.id)
        return "Routine '${target.name}' delete kar di hai."
    }

    suspend fun toggleRoutineEnabled(routine: RoutineEntity, enabled: Boolean) {
        val updated = routine.copy(isEnabled = enabled)
        routineDao.upsert(updated)
        if (enabled) scheduleWorkIfNeeded(updated)
        else WorkManager.getInstance(context).cancelUniqueWork("routine_${routine.id}")
    }

    suspend fun findMatchingRoutine(command: String): RoutineEntity? {
        if (!keyStore.settings.value.routinesEnabled) return null
        ensureBuiltInRoutinesSeeded()
        val lower = command.lowercase().trim()
        return routineDao.getEnabledRoutines()
            .filter { it.scheduleType != SCHEDULE_TYPE_COMMAND }
            .firstOrNull { routine ->
                val trig = routine.triggerPhrase.lowercase().trim()
                trig.isNotBlank() && (lower == trig || lower.contains(trig))
            }
    }

    // ---- Custom Commands (reuses RoutineManager engine with scheduleType = "COMMAND") ----

    suspend fun saveCustomCommand(
        name: String,
        triggerPhrase: String,
        steps: List<RoutineStep>
    ): Long = saveRoutine(
        name = name,
        triggerPhrase = triggerPhrase,
        steps = steps,
        scheduleType = SCHEDULE_TYPE_COMMAND,
        scheduleTime = ""
    )

    suspend fun updateCustomCommand(
        id: Long,
        name: String,
        triggerPhrase: String,
        steps: List<RoutineStep>
    ): Boolean = updateRoutine(
        id = id,
        name = name,
        triggerPhrase = triggerPhrase,
        steps = steps,
        scheduleType = SCHEDULE_TYPE_COMMAND,
        scheduleTime = ""
    )

    suspend fun listCommandsSummary(): String {
        val enabled = routineDao.getEnabledRoutines().filter { it.scheduleType == SCHEDULE_TYPE_COMMAND }
        if (enabled.isEmpty()) return "Abhi koi custom command saved nahi hai. Commands tab ya voice ('naya command banao...') se banao."
        return enabled.joinToString("\n") { c ->
            val stepCount = decodeSteps(c.stepsJson).size
            "- ${c.name} (say \"${c.triggerPhrase}\") • $stepCount actions"
        }
    }

    suspend fun deleteCommandByQuery(query: String): String {
        val q = query.trim().lowercase()
        if (q.isBlank()) return "Kaunsa custom command delete karna hai, naam ya trigger phrase batao."
        val target = routineDao.getEnabledRoutines()
            .filter { it.scheduleType == SCHEDULE_TYPE_COMMAND }
            .firstOrNull {
                it.name.lowercase().contains(q) || it.triggerPhrase.lowercase().contains(q)
            } ?: return "'$query' naam ka koi custom command nahi mila."
        deleteRoutine(target.id)
        return "Custom command '${target.name}' delete kar diya hai."
    }

    suspend fun findMatchingCommand(command: String): RoutineEntity? {
        if (!keyStore.settings.value.customCommandsEnabled) return null
        val lower = command.lowercase().trim()
        return routineDao.getEnabledRoutines()
            .filter { it.scheduleType == SCHEDULE_TYPE_COMMAND }
            .firstOrNull { cmd ->
                val trig = cmd.triggerPhrase.lowercase().trim()
                trig.isNotBlank() && (lower == trig || lower.contains(trig))
            }
    }

    suspend fun runHeadphoneTriggeredRoutines(): Int {
        if (!keyStore.settings.value.routinesEnabled) return 0
        ensureBuiltInRoutinesSeeded()
        val matches = routineDao.getEnabledRoutines().filter { it.scheduleType == "HEADPHONES" }
        matches.forEach { routine ->
            runRoutine(routine) { /* background trigger skips interactive UI steps unless unlocked */ }
        }
        return matches.size
    }

    suspend fun runRoutine(
        routine: RoutineEntity,
        onQueueUiCommand: suspend (String) -> Unit
    ): String {
        val steps = decodeSteps(routine.stepsJson)
        val results = mutableListOf<String>()
        val isLocked = permissionHelper.isDeviceLocked()
        val gen = stopSignalBus.currentGeneration()
        val logSource = if (routine.scheduleType == SCHEDULE_TYPE_COMMAND) "command" else "routine"

        for (step in steps) {
            if (stopSignalBus.isStoppedSince(gen)) {
                actionLog.record(routine.name, "stop", "", "Stopped by Panic Stop", status = "STOPPED", source = logSource)
                return "'${routine.name}' Panic Stop se rok diya gaya."
            }
            // Strictly block sensitive actions inside routines
            if (ToolDefinitions.isSensitiveAction(step.tool, step.args)) {
                results.add("Skipped sensitive step ${step.tool} (not allowed in routines)")
                actionLog.record(
                    commandText = "Routine: ${routine.name}",
                    toolName = step.tool,
                    argsSummary = step.args.toString(),
                    resultText = "Blocked sensitive routine step",
                    status = "BLOCKED",
                    source = "routine"
                )
                continue
            }

            if (isLocked && ToolDefinitions.requiresUnlockedScreen(step.tool)) {
                onQueueUiCommand("Run routine step ${step.tool} with ${step.args}")
                results.add("Queued UI step ${step.tool} until unlock")
                actionLog.record(
                    commandText = "Routine: ${routine.name}",
                    toolName = step.tool,
                    argsSummary = step.args.toString(),
                    resultText = "Queued until unlock",
                    status = "QUEUED",
                    source = "routine"
                )
                continue
            }

            when (step.tool) {
                "stop_alarm" -> {
                    val out = dismissActiveAlarm()
                    results.add(out)
                    actionLog.record("Routine: ${routine.name}", "stop_alarm", "", out, status = "OK", source = "routine")
                }
                "speak_weather" -> {
                    val weather = fetchAndSpeakWeather()
                    results.add(weather)
                    actionLog.record("Routine: ${routine.name}", "speak_weather", "", weather, status = "OK", source = "routine")
                }
                "read_calendar_events" -> {
                    val calMsg = readAndSpeakTodayCalendar()
                    results.add(calMsg)
                    actionLog.record("Routine: ${routine.name}", "read_calendar_events", "", calMsg, status = "OK", source = "routine")
                }
                "summarize_notifications" -> {
                    val speak = step.args["speak"] as? Boolean ?: true
                    val summary = notificationSummaryManager.buildSummary(speakAloud = speak)
                    results.add(summary)
                    actionLog.record("Routine: ${routine.name}", "summarize_notifications", "", summary, status = "OK", source = "routine")
                }
                "morning_briefing" -> {
                    val brief = buildMorningBriefing()
                    ttsManager.speak(brief, "hinglish")
                    results.add(brief)
                    actionLog.record("Routine: ${routine.name}", "morning_briefing", "", brief, status = "OK", source = "routine")
                }
                else -> {
                    val res = executor.execute(step.tool, step.args)
                    results.add("${step.tool}: ${res.output}")
                    actionLog.record(
                        commandText = "Routine: ${routine.name}",
                        toolName = step.tool,
                        argsSummary = step.args.toString(),
                        resultText = res.output,
                        targetApp = step.args["app_name"]?.toString().orEmpty(),
                        status = if (res.ok) "OK" else "ERR",
                        source = "routine"
                    )
                }
            }
        }
        return "Routine '${routine.name}' complete: ${results.joinToString(" | ")}"
    }

    private suspend fun dismissActiveAlarm(): String {
        val dismissed = runCatching {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        executor.execute("media_control", mapOf("action" to "stop"))
        return if (dismissed) "Alarm dismiss triggered." else "Alarm stop signal sent."
    }

    private suspend fun fetchAndSpeakWeather(): String {
        if (keyStore.settings.value.dataSaverEnabled) {
            return "Weather skipped (Data Saver mode ON)."
        }
        val weatherText = withTimeoutOrNull(3800L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url("https://wttr.in/?format=3")
                        .header("User-Agent", "BabyAssistant/1.0")
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string()?.trim() else null
                    }
                }.getOrNull()
            }
        }
        val spoken = if (!weatherText.isNullOrBlank()) {
            "Aaj ka weather: $weatherText"
        } else {
            "Weather update abhi offline/unavailable hai."
        }
        if (keyStore.settings.value.ttsRepliesEnabled) {
            ttsManager.speak(spoken, "hinglish")
        }
        return spoken
    }

    private fun readAndSpeakTodayCalendar(): String {
        val msg = if (permissionHelper.hasPermission(Manifest.permission.READ_CALENDAR)) {
            val events = queryTodayCalendarEvents()
            if (events.isNotEmpty()) {
                "Aaj ke calendar events: ${events.joinToString(", ")}."
            } else {
                "Aaj calendar par koi event nahi hai."
            }
        } else {
            "Calendar permission off hai, events skip kiye."
        }
        if (keyStore.settings.value.ttsRepliesEnabled) {
            ttsManager.speak(msg, "hinglish")
        }
        return msg
    }

    private suspend fun buildMorningBriefing(): String {
        val parts = mutableListOf<String>()
        parts.add(dismissActiveAlarm())
        parts.add(fetchAndSpeakWeather())
        parts.add(readAndSpeakTodayCalendar())
        parts.add(notificationSummaryManager.buildSummary(speakAloud = false))
        return parts.joinToString(" ")
    }

    private fun queryTodayCalendarEvents(): List<String> {
        val list = mutableListOf<String>()
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
        }
        runCatching {
            val uri = CalendarContract.Events.CONTENT_URI
            val proj = arrayOf(CalendarContract.Events.TITLE)
            val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val args = arrayOf(startCal.timeInMillis.toString(), endCal.timeInMillis.toString())
            context.contentResolver.query(uri, proj, sel, args, "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
                while (c.moveToNext() && list.size < 4) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { list.add(it) }
                }
            }
        }
        return list
    }

    fun scheduleWorkIfNeeded(routine: RoutineEntity) {
        if (!routine.isEnabled || routine.scheduleType == "MANUAL" ||
            routine.scheduleType == "HEADPHONES" || routine.scheduleType == SCHEDULE_TYPE_COMMAND
        ) return
        val wm = WorkManager.getInstance(context)
        val input = Data.Builder().putLong("routine_id", routine.id).build()
        val constraintsBuilder = Constraints.Builder()
        if (routine.scheduleType == "CHARGING") {
            constraintsBuilder.setRequiresCharging(true)
        }
        val request = PeriodicWorkRequestBuilder<RoutineTriggerWorker>(
            if (routine.scheduleType == "TIME") 24 else 6,
            TimeUnit.HOURS
        )
            .setConstraints(constraintsBuilder.build())
            .setInputData(input)
            .build()
        wm.enqueueUniquePeriodicWork(
            "routine_${routine.id}",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun sanitizeSteps(steps: List<RoutineStep>): List<RoutineStep> =
        steps.filterNot { ToolDefinitions.isSensitiveAction(it.tool, it.args) }

    companion object {
        const val SCHEDULE_TYPE_COMMAND = "COMMAND"

        fun encodeSteps(steps: List<RoutineStep>): String {
            val arr = JSONArray()
            steps.forEach { step ->
                arr.put(
                    JSONObject().apply {
                        put("tool", step.tool)
                        put("args", JSONObject(step.args))
                    }
                )
            }
            return arr.toString()
        }

        fun decodeSteps(json: String): List<RoutineStep> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { idx ->
                val obj = arr.getJSONObject(idx)
                val tool = obj.optString("tool")
                val argsObj = obj.optJSONObject("args") ?: JSONObject()
                val argsMap = mutableMapOf<String, Any?>()
                argsObj.keys().forEach { k -> argsMap[k] = argsObj.get(k) }
                RoutineStep(tool, argsMap)
            }
        }.getOrDefault(emptyList())
    }
}
