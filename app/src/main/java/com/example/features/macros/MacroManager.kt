package com.example.features.macros

import com.example.core.security.SecureKeyStore
import com.example.data.local.MacroDao
import com.example.data.local.MacroEntity
import com.example.domain.tools.PhoneControlExecutor
import com.example.domain.tools.StopSignalBus
import com.example.domain.tools.ToolDefinitions
import com.example.features.actionlog.ActionLogRepository
import com.example.features.safety.SafetyManager
import com.example.service.BabyAccessibilityService
import com.example.util.PermissionHelper
import com.example.voice.BabyTtsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class MacroStep(
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
    val label: String = "",
    val viewId: String? = null,
    val targetText: String? = null,
    val contentDesc: String? = null,
    val packageName: String? = null
) {
    fun hasElementIdentifier(): Boolean =
        !viewId.isNullOrBlank() || !targetText.isNullOrBlank() || !contentDesc.isNullOrBlank()
}

@Singleton
class MacroManager @Inject constructor(
    private val macroDao: MacroDao,
    private val executor: PhoneControlExecutor,
    private val keyStore: SecureKeyStore,
    private val permissionHelper: PermissionHelper,
    private val actionLog: ActionLogRepository,
    private val ttsManager: BabyTtsManager,
    private val stopSignalBus: StopSignalBus
) {
    val macros: Flow<List<MacroEntity>> = macroDao.observeAll()

    private val _isRecordingMacro = MutableStateFlow(false)
    val isRecordingMacro: StateFlow<Boolean> = _isRecordingMacro.asStateFlow()

    private val _recordedSteps = MutableStateFlow<List<MacroStep>>(emptyList())
    val recordedSteps: StateFlow<List<MacroStep>> = _recordedSteps.asStateFlow()

    private val _recordingWarning = MutableStateFlow<String?>(null)
    val recordingWarning: StateFlow<String?> = _recordingWarning.asStateFlow()

    private var lastSensitiveWarnMs = 0L

    fun startRecording(): String {
        if (!keyStore.settings.value.macrosEnabled) {
            return "Macros Settings mein OFF hai."
        }
        _recordedSteps.value = emptyList()
        _recordingWarning.value = null
        _isRecordingMacro.value = true
        return "Macro recording shuru! Ab jo task record karna hai wo karo, phir bolo 'Macro save karo [naam]'."
    }

    fun reportSensitiveSkipped(reason: String) {
        if (!_isRecordingMacro.value) return
        _recordingWarning.value = reason
        val now = System.currentTimeMillis()
        if (now - lastSensitiveWarnMs >= 3500L) {
            lastSensitiveWarnMs = now
            ttsManager.speak("Warning: $reason", "hinglish")
        }
    }

    fun recordAccessibilityStep(
        tool: String,
        args: Map<String, Any?>,
        label: String,
        viewId: String? = null,
        targetText: String? = null,
        contentDesc: String? = null,
        packageName: String? = null
    ) {
        if (!_isRecordingMacro.value) return
        val current = _recordedSteps.value
        if (current.size >= MAX_RECORDED_STEPS) return

        val step = MacroStep(
            tool = tool,
            args = args,
            label = label,
            viewId = viewId,
            targetText = targetText,
            contentDesc = contentDesc,
            packageName = packageName
        )
        _recordedSteps.value = current + step
    }

    fun recordTextStepCoalesced(
        text: String,
        viewId: String? = null,
        contentDesc: String? = null,
        packageName: String? = null
    ) {
        if (!_isRecordingMacro.value) return
        val current = _recordedSteps.value
        val newStep = MacroStep(
            tool = "type_text",
            args = mapOf("text" to text),
            label = "Type \"${text.take(28)}\"",
            viewId = viewId,
            contentDesc = contentDesc,
            packageName = packageName
        )
        val last = current.lastOrNull()
        if (last != null && last.tool == "type_text" &&
            last.viewId == viewId && last.packageName == packageName
        ) {
            _recordedSteps.value = current.dropLast(1) + newStep
        } else if (current.size < MAX_RECORDED_STEPS) {
            _recordedSteps.value = current + newStep
        }
    }

    suspend fun stopAndSaveRecording(name: String, triggerPhrase: String): Long {
        _isRecordingMacro.value = false
        val steps = _recordedSteps.value
        _recordedSteps.value = emptyList()
        _recordingWarning.value = null
        val cleanName = name.trim().ifBlank { "My Macro" }
        val cleanTrigger = triggerPhrase.trim().lowercase().ifBlank { cleanName.lowercase() }
        return saveMacro(cleanName, cleanTrigger, steps)
    }

    fun cancelRecording() {
        _isRecordingMacro.value = false
        _recordedSteps.value = emptyList()
        _recordingWarning.value = null
    }

    suspend fun saveMacro(name: String, triggerPhrase: String, steps: List<MacroStep>): Long {
        val safeSteps = steps.filterNot { step ->
            ToolDefinitions.isSensitiveAction(step.tool, step.args) ||
                SafetyManager.isSensitiveField(step.viewId, step.targetText, step.contentDesc, false) ||
                SafetyManager.isBlockedScreen(step.packageName, step.targetText) != null
        }
        return macroDao.upsert(
            MacroEntity(
                name = name.trim(),
                triggerPhrase = triggerPhrase.trim().lowercase(),
                stepsJson = encodeSteps(safeSteps)
            )
        )
    }

    suspend fun toggleMacroEnabled(macro: MacroEntity, enabled: Boolean) {
        macroDao.upsert(macro.copy(isEnabled = enabled))
    }

    suspend fun deleteMacro(id: Long) = macroDao.deleteById(id)

    suspend fun listMacrosSummary(): String {
        val enabled = macroDao.getEnabled()
        if (enabled.isEmpty()) return "Abhi koi enabled macro saved nahi hai."
        return enabled.joinToString("\n") { m ->
            val count = decodeSteps(m.stepsJson).size
            "- ${m.name} (say \"${m.triggerPhrase} chalao\") • $count steps"
        }
    }

    suspend fun deleteMacroByQuery(query: String): String {
        val q = query.trim().lowercase()
        if (q.isBlank()) return "Kaunsa macro delete karna hai, naam batao."
        val target = macroDao.getEnabled().firstOrNull {
            it.name.lowercase().contains(q) || it.triggerPhrase.lowercase().contains(q)
        } ?: return "'$query' naam ka koi macro nahi mila."
        deleteMacro(target.id)
        return "Macro '${target.name}' delete kar diya hai."
    }

    suspend fun findMatchingMacro(command: String): MacroEntity? {
        if (!keyStore.settings.value.macrosEnabled) return null
        val lower = command.lowercase().trim()
        val stripped = lower
            .removeSuffix(" chalao")
            .removeSuffix(" run karo")
            .removeSuffix(" play karo")
            .trim()
        return macroDao.getEnabled().firstOrNull { macro ->
            val trig = macro.triggerPhrase.lowercase().trim()
            val name = macro.name.lowercase().trim()
            (trig.isNotBlank() && (lower == trig || stripped == trig || lower.contains(trig))) ||
                (name.isNotBlank() && (stripped == name || lower == "$name chalao"))
        }
    }

    suspend fun runMacro(
        macro: MacroEntity,
        onQueueUiCommand: suspend (String) -> Unit
    ): String {
        if (!keyStore.settings.value.macrosEnabled) {
            return "Macros feature Settings mein OFF hai."
        }
        val steps = decodeSteps(macro.stepsJson)
        if (steps.isEmpty()) return "Macro '${macro.name}' mein koi valid steps nahi hain."

        if (permissionHelper.isDeviceLocked() && steps.any { ToolDefinitions.requiresUnlockedScreen(it.tool) }) {
            onQueueUiCommand("${macro.triggerPhrase} chalao")
            actionLog.record(
                commandText = "Macro: ${macro.name}",
                toolName = "run_macro",
                argsSummary = macro.triggerPhrase,
                resultText = "Queued full macro until phone unlock",
                status = "QUEUED",
                source = "macro"
            )
            return "Phone locked hai — unlock karte hi macro '${macro.name}' chal jayega."
        }

        val gen = stopSignalBus.currentGeneration()
        var executedCount = 0

        for ((index, step) in steps.withIndex()) {
            if (stopSignalBus.isStoppedSince(gen)) {
                actionLog.record("Macro: ${macro.name}", "run_macro", "", "Stopped by Panic Stop at step ${index + 1}", status = "STOPPED", source = "macro")
                return "Macro '${macro.name}' step ${index + 1} se pehle Panic Stop se rok diya gaya."
            }

            if (ToolDefinitions.isSensitiveAction(step.tool, step.args) ||
                SafetyManager.isBlockedScreen(step.packageName, step.targetText) != null
            ) {
                actionLog.record("Macro: ${macro.name}", step.tool, step.args.toString(), "Blocked sensitive macro step", status = "BLOCKED", source = "macro")
                return "Macro '${macro.name}' step ${index + 1} (${step.label.ifBlank { step.tool }}) safety rule se block kiya gaya."
            }

            // Ensure target app is launched if needed or foreground isn't a blocked screen
            val acc = BabyAccessibilityService.instance
            val fgPkg = acc?.getActivePackageName()
            val blockedReason = SafetyManager.isBlockedScreen(fgPkg, null)
            if (blockedReason != null) {
                actionLog.record("Macro: ${macro.name}", step.tool, "", "Blocked on $blockedReason", targetApp = fgPkg.orEmpty(), status = "BLOCKED", source = "macro")
                return "Safety guard: $blockedReason screen par macro automate nahi hoga (step ${index + 1})."
            }

            var stepOk = attemptSingleStep(step, acc)
            if (!stepOk) {
                delay(750L)
                if (stopSignalBus.isStoppedSince(gen)) {
                    return "Macro '${macro.name}' Panic Stop se rok diya gaya."
                }
                stepOk = attemptSingleStep(step, BabyAccessibilityService.instance)
            }

            if (!stepOk) {
                val stepTitle = step.label.ifBlank { step.targetText ?: step.viewId ?: step.tool }
                val failMsg = "Macro '${macro.name}' step ${index + 1} (\"$stepTitle\") par fail hua: expected element screen par nahi mila (1 retry ke baad rok diya)."
                actionLog.record(
                    commandText = "Macro: ${macro.name}",
                    toolName = step.tool,
                    argsSummary = step.args.toString(),
                    resultText = failMsg,
                    targetApp = step.packageName.orEmpty(),
                    status = "ERR",
                    source = "macro"
                )
                return failMsg
            }

            executedCount++
            actionLog.record(
                commandText = "Macro: ${macro.name}",
                toolName = step.tool,
                argsSummary = step.args.toString(),
                resultText = "Verified step ${index + 1}: ${step.label.ifBlank { step.tool }}",
                targetApp = step.packageName.orEmpty(),
                status = "OK",
                source = "macro"
            )
            if (ToolDefinitions.requiresUnlockedScreen(step.tool)) {
                delay(480L)
            }
        }

        return "Macro '${macro.name}' safely complete ($executedCount/${steps.size} steps verified)."
    }

    private suspend fun attemptSingleStep(
        step: MacroStep,
        acc: BabyAccessibilityService?
    ): Boolean {
        val isUiElementStep = step.tool == "tap" || step.tool == "long_press" ||
            step.tool == "type_text" || step.tool == "scroll"

        if (isUiElementStep && step.hasElementIdentifier()) {
            if (acc == null) return false
            val textArg = step.args["text"]?.toString()
            val dirArg = step.args["direction"]?.toString()
            return acc.findAndActByIdentifier(
                tool = step.tool,
                viewId = step.viewId,
                targetText = step.targetText,
                contentDesc = step.contentDesc,
                expectedPackage = step.packageName,
                textToType = textArg,
                scrollDirection = dirArg
            )
        }

        return executor.execute(step.tool, step.args).ok
    }

    companion object {
        const val MAX_RECORDED_STEPS = 30

        fun encodeSteps(steps: List<MacroStep>): String {
            val arr = JSONArray()
            steps.forEach { step ->
                arr.put(
                    JSONObject().apply {
                        put("tool", step.tool)
                        put("label", step.label)
                        put("args", JSONObject(step.args))
                        if (!step.viewId.isNullOrBlank()) put("viewId", step.viewId)
                        if (!step.targetText.isNullOrBlank()) put("targetText", step.targetText)
                        if (!step.contentDesc.isNullOrBlank()) put("contentDesc", step.contentDesc)
                        if (!step.packageName.isNullOrBlank()) put("packageName", step.packageName)
                    }
                )
            }
            return arr.toString()
        }

        fun decodeSteps(json: String): List<MacroStep> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { idx ->
                val obj = arr.getJSONObject(idx)
                val argsObj = obj.optJSONObject("args") ?: JSONObject()
                val argsMap = mutableMapOf<String, Any?>()
                argsObj.keys().forEach { k -> argsMap[k] = argsObj.get(k) }
                MacroStep(
                    tool = obj.optString("tool"),
                    args = argsMap,
                    label = obj.optString("label"),
                    viewId = obj.optString("viewId").takeIf { it.isNotBlank() },
                    targetText = obj.optString("targetText").takeIf { it.isNotBlank() },
                    contentDesc = obj.optString("contentDesc").takeIf { it.isNotBlank() },
                    packageName = obj.optString("packageName").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }
}
