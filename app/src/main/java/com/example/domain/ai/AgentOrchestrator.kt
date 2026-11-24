package com.example.domain.ai

import android.content.Context
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.local.ChatMessageEntity
import com.example.data.local.HistoryRepository
import com.example.domain.tools.ExtendedToolRouter
import com.example.domain.tools.PhoneControlExecutor
import com.example.domain.tools.StopSignalBus
import com.example.domain.tools.ToolDefinitions
import com.example.features.actionlog.ActionLogInterceptor
import com.example.features.actionlog.ActionLogRepository
import com.example.features.macros.MacroManager
import com.example.features.memory.MemoryManager
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.offline.OfflineCommandEngine
import com.example.features.routines.RoutineManager
import com.example.features.safety.SafetyManager
import com.example.features.widget.BabyWidgetProvider
import com.example.service.BabyAccessibilityService
import com.example.util.PermissionHelper
import com.example.voice.BabyTtsManager
import com.example.voice.WakeWordDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ConfirmationRequest(
    val toolName: String,
    val summary: String,
    val argsPreview: String,
    val requiresVoicePin: Boolean = false
)

data class AgentExecutionState(
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val maxSteps: Int = MAX_STEPS,
    val activeBrain: BrainType? = null,
    val statusText: String = "Ready",
    val pendingConfirmation: ConfirmationRequest? = null,
    val queuedCount: Int = 0,
    val lastError: String? = null
) {
    companion object {
        const val MAX_STEPS = 15
    }
}

@Singleton
class AgentOrchestrator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val aiRouter: AiRouter,
    private val executor: PhoneControlExecutor,
    private val extendedToolRouter: ExtendedToolRouter,
    private val historyRepository: HistoryRepository,
    private val actionLogRepository: ActionLogRepository,
    private val actionLogInterceptor: ActionLogInterceptor,
    private val offlineCommandEngine: OfflineCommandEngine,
    private val routineManager: RoutineManager,
    private val macroManager: MacroManager,
    private val memoryManager: MemoryManager,
    private val notificationSummaryManager: NotificationSummaryManager,
    private val safetyManager: SafetyManager,
    private val keyStore: SecureKeyStore,
    private val permissionHelper: PermissionHelper,
    private val ttsManager: BabyTtsManager,
    private val stopSignalBus: StopSignalBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeTaskId: Long? = null
    private var pendingConfirmDeferred: CompletableDeferred<Boolean>? = null

    private val _state = MutableStateFlow(AgentExecutionState())
    val state: StateFlow<AgentExecutionState> = _state.asStateFlow()

    init {
        scope.launch {
            stopSignalBus.stopEvents.collect { reason ->
                stopCurrentTask(reason)
            }
        }
    }

    fun clearLastError() {
        _state.value = _state.value.copy(lastError = null)
    }

    fun submitUserCommand(
        rawCommand: String,
        source: String = "text",
        languageHint: String? = null
    ) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        if (safetyManager.emergencyCountdown.value.active && SafetyManager.isEmergencyCancelCommand(command)) {
            safetyManager.cancelEmergencyCountdown("Voice/Text cancel: $command")
            _state.value = AgentExecutionState(statusText = "SOS Cancelled")
            return
        }

        if (WakeWordDetector.isStopCommand(command)) {
            stopCurrentTask("User said stop")
            return
        }

        // Cancel previous running task if a brand-new command arrives
        activeJob?.cancel()
        pendingConfirmDeferred?.complete(false)
        pendingConfirmDeferred = null
        _state.value = _state.value.copy(
            isRunning = true,
            currentStep = 0,
            statusText = "Starting...",
            lastError = null,
            pendingConfirmation = null
        )

        activeJob = scope.launch {
            runAgentLoop(command, source, languageHint ?: detectLanguageHint(command))
        }
    }

    suspend fun handleVoiceWav(wavBytes: ByteArray): String? {
        _state.value = _state.value.copy(statusText = "Whisper se samajh rahi hoon...", lastError = null)
        val res = aiRouter.transcribeAudio(wavBytes)
        return res.fold(
            onSuccess = { transcript ->
                if (transcript.text.isBlank()) {
                    _state.value = _state.value.copy(statusText = "Kuch sunai nahi diya")
                    null
                } else if (safetyManager.emergencyCountdown.value.active &&
                    SafetyManager.isEmergencyCancelCommand(transcript.text)
                ) {
                    safetyManager.cancelEmergencyCountdown("Voice cancel: ${transcript.text}")
                    _state.value = AgentExecutionState(statusText = "SOS Cancelled")
                    transcript.text
                } else if (WakeWordDetector.isStopCommand(transcript.text)) {
                    stopCurrentTask("Voice: Baby stop")
                    transcript.text
                } else {
                    submitUserCommand(
                        rawCommand = transcript.text,
                        source = "voice",
                        languageHint = transcript.detectedLanguage
                    )
                    transcript.text
                }
            },
            onFailure = { err ->
                if (err is CancellationException) throw err
                val settings = keyStore.settings.value
                val safeErr = SensitiveDataSanitizer.sanitizeError(err.message, settings.groqApiKey, settings.geminiApiKey)
                historyRepository.addMessage(
                    ChatMessageEntity(
                        role = "assistant",
                        content = "Voice transcribe nahi ho paya: $safeErr",
                        brainUsed = "local"
                    )
                )
                _state.value = _state.value.copy(statusText = "Ready", lastError = safeErr)
                null
            }
        )
    }

    fun approveConfirmation(approved: Boolean, enteredPin: String? = null) {
        val currentReq = _state.value.pendingConfirmation
        val finalApproved = if (approved && currentReq?.requiresVoicePin == true) {
            !enteredPin.isNullOrBlank() && safetyManager.verifyVoicePin(enteredPin)
        } else {
            approved
        }
        pendingConfirmDeferred?.complete(finalApproved)
        pendingConfirmDeferred = null
        _state.value = _state.value.copy(pendingConfirmation = null)
    }

    fun stopCurrentTask(reason: String = "Stopped by user") {
        val cancelledSos = safetyManager.cancelEmergencyCountdown(reason)
        val prevState = _state.value
        val wasRunning = activeJob?.isActive == true || prevState.isRunning || cancelledSos
        val lastStep = prevState.currentStep
        pendingConfirmDeferred?.complete(false)
        pendingConfirmDeferred = null
        activeJob?.cancel()
        activeJob = null
        ttsManager.stop()

        val taskId = activeTaskId
        activeTaskId = null
        _state.value = AgentExecutionState(statusText = "Stopped", queuedCount = prevState.queuedCount)

        if (wasRunning) {
            scope.launch {
                if (taskId != null) {
                    historyRepository.updateTaskRun(
                        id = taskId,
                        status = "STOPPED",
                        stepsCount = lastStep,
                        brainSummary = reason,
                        finalReply = "Theek hai, ruk gayi hoon! ($reason)",
                        stepLogsJson = "[]",
                        finishedAt = System.currentTimeMillis()
                    )
                }
                historyRepository.addMessage(
                    ChatMessageEntity(
                        role = "assistant",
                        content = "Theek hai, kaam yahin rok diya hai.",
                        brainUsed = "local"
                    )
                )
            }
        }
    }

    fun onDeviceUnlocked() {
        _state.value = _state.value.copy(isRunning = true, statusText = "Unlock ho gaya, checking queue...")
        scope.launch {
            val queued = historyRepository.popAllQueuedTasks()
            if (queued.isEmpty()) {
                if (activeTaskId == null) {
                    _state.value = _state.value.copy(isRunning = false, statusText = "Ready")
                }
                return@launch
            }
            _state.value = _state.value.copy(isRunning = true, queuedCount = 0)
            for (task in queued) {
                runAgentLoop(
                    command = task.commandText,
                    source = "${task.source}_unlocked",
                    languageHint = detectLanguageHint(task.commandText)
                )
            }
        }
    }

    private suspend fun runAgentLoop(
        command: String,
        source: String,
        languageHint: String
    ) {
        val taskId = historyRepository.startTaskRun(SensitiveDataSanitizer.sanitizeForLogs(command))
        activeTaskId = taskId
        val stepLogs = JSONArray()
        val brainsUsed = linkedSetOf<String>()

        historyRepository.addMessage(
            ChatMessageEntity(
                role = "user",
                content = command,
                languageHint = languageHint
            )
        )

        val settings = keyStore.settings.value

        // Explicit confirmation gate for "Sab bhool jao" before wiping all memories
        if (MemoryManager.isWipeMemoryPhrase(command)) {
            val wipeReply = confirmAndWipeMemories(command, source, languageHint, settings)
            historyRepository.addMessage(
                ChatMessageEntity(role = "assistant", content = wipeReply, brainUsed = "local")
            )
            historyRepository.updateTaskRun(
                id = taskId,
                status = "COMPLETED",
                stepsCount = 1,
                brainSummary = "Local",
                finalReply = wipeReply,
                stepLogsJson = "[]",
                finishedAt = System.currentTimeMillis()
            )
            speakIfEnabled(wipeReply, languageHint)
            if (activeTaskId == taskId) {
                activeTaskId = null
                _state.value = AgentExecutionState(statusText = "Ready")
            }
            return
        }

        // Fast local deterministic paths before hitting Groq/Gemini APIs
        val quickLocalOutcome = tryQuickLocalExecution(command, source, languageHint, settings)
        if (quickLocalOutcome != null) {
            historyRepository.addMessage(
                ChatMessageEntity(role = "assistant", content = quickLocalOutcome, brainUsed = "local")
            )
            historyRepository.updateTaskRun(
                id = taskId,
                status = "COMPLETED",
                stepsCount = 1,
                brainSummary = "Local",
                finalReply = quickLocalOutcome,
                stepLogsJson = "[]",
                finishedAt = System.currentTimeMillis()
            )
            speakIfEnabled(quickLocalOutcome, languageHint)
            updateWidgetLastTask(command, quickLocalOutcome)
            if (activeTaskId == taskId) {
                activeTaskId = null
                _state.value = AgentExecutionState(statusText = "Ready")
            }
            return
        }

        val relevantMemoryBlock = memoryManager.buildRelevantPromptBlock(command)

        val recentHistory = historyRepository.getRecentConversation(if (settings.dataSaverEnabled) 4 else 10)
        val turns = mutableListOf<AgentStepTurn>()
        for (msg in recentHistory.dropLast(1)) {
            if (msg.role == "user" || msg.role == "assistant") {
                turns.add(AgentStepTurn(role = msg.role, text = msg.content))
            }
        }

        val turnsPrompt = command
        var latestScreenshot: String? = null
        turns.add(AgentStepTurn(role = "user", text = turnsPrompt))

        var finalReply = ""
        var stepFailedError: String? = null
        var lastStepCompleted = 0

        try {
            for (step in 0 until AgentExecutionState.MAX_STEPS) {
                lastStepCompleted = step + 1
                val isLocked = permissionHelper.isDeviceLocked()
                val screenContext = if (!isLocked) {
                    val acc = BabyAccessibilityService.instance
                    val pkg = acc?.getActivePackageName()
                    if (SafetyManager.isBlockedScreen(pkg, null) != null) {
                        "Blocked sensitive screen ($pkg) — hidden for safety."
                    } else {
                        val tree = acc?.readUiTree()
                        if (SafetyManager.isBlockedScreen(pkg, tree) != null) {
                            "Blocked sensitive screen ($pkg) — hidden for safety."
                        } else tree
                    }
                } else null

                _state.value = AgentExecutionState(
                    isRunning = true,
                    currentStep = step + 1,
                    activeBrain = _state.value.activeBrain,
                    statusText = "Soch rahi hoon (${step + 1}/15)...",
                    queuedCount = _state.value.queuedCount
                )

                val planRes = aiRouter.planNextStep(
                    turns = turns,
                    screenContext = screenContext,
                    screenshotBase64 = latestScreenshot,
                    stepIndex = step,
                    isLocked = isLocked,
                    userLanguageHint = languageHint,
                    memoryBlock = relevantMemoryBlock
                )
                latestScreenshot = null

                if (planRes.isFailure) {
                    val curKeys = keyStore.settings.value
                    val errMsg = SensitiveDataSanitizer.sanitizeError(
                        planRes.exceptionOrNull()?.message ?: "AI error",
                        curKeys.groqApiKey,
                        curKeys.geminiApiKey
                    )
                    stepFailedError = errMsg
                    finalReply = "Oops, issue aa gaya: $errMsg"
                    historyRepository.addMessage(
                        ChatMessageEntity(role = "assistant", content = finalReply, brainUsed = "local")
                    )
                    historyRepository.updateTaskRun(
                        id = taskId,
                        status = "FAILED",
                        stepsCount = step + 1,
                        brainSummary = brainsUsed.joinToString(", "),
                        finalReply = finalReply,
                        stepLogsJson = stepLogs.toString(),
                        finishedAt = System.currentTimeMillis()
                    )
                    speakIfEnabled(finalReply, languageHint)
                    return
                }

                val plan = planRes.getOrThrow()
                brainsUsed.add(plan.brainUsed.label)
                _state.value = _state.value.copy(activeBrain = plan.brainUsed)

                if (plan.toolCalls.isEmpty()) {
                    finalReply = plan.replyText ?: "Ho gaya!"
                    historyRepository.addMessage(
                        ChatMessageEntity(
                            role = "assistant",
                            content = finalReply,
                            brainUsed = plan.brainUsed.name.lowercase()
                        )
                    )
                    historyRepository.updateTaskRun(
                        id = taskId,
                        status = "COMPLETED",
                        stepsCount = step + 1,
                        brainSummary = brainsUsed.joinToString(" -> "),
                        finalReply = finalReply,
                        stepLogsJson = stepLogs.toString(),
                        finishedAt = System.currentTimeMillis()
                    )
                    speakIfEnabled(finalReply, languageHint)
                    updateWidgetLastTask(command, finalReply)
                    return
                }

                // Execute planned tool calls
                for (call in plan.toolCalls) {
                    // Check locked-phone gate for UI automation
                    if (permissionHelper.isDeviceLocked() && ToolDefinitions.requiresUnlockedScreen(call.name)) {
                        historyRepository.enqueueForUnlock(command, source)
                        val unlockMsg = "Unlock karo, kaam shuru karta hoon."
                        historyRepository.addMessage(
                            ChatMessageEntity(
                                role = "assistant",
                                content = "Phone locked hai — $unlockMsg",
                                brainUsed = "local"
                            )
                        )
                        historyRepository.updateTaskRun(
                            id = taskId,
                            status = "QUEUED_FOR_UNLOCK",
                            stepsCount = step + 1,
                            brainSummary = brainsUsed.joinToString(" -> "),
                            finalReply = unlockMsg,
                            stepLogsJson = stepLogs.toString(),
                            finishedAt = System.currentTimeMillis()
                        )
                        ttsManager.speak(unlockMsg, "hinglish")
                        _state.value = AgentExecutionState(
                            statusText = "Waiting for unlock...",
                            queuedCount = _state.value.queuedCount + 1
                        )
                        return
                    }

                    // Check sensitive confirmation gate (+ optional Voice PIN & Biometric)
                    val curSettings = keyStore.settings.value
                    val isSensitive = ToolDefinitions.isSensitiveAction(call.name, call.args)
                    if (isSensitive && curSettings.confirmSensitiveActions) {
                        val summary = ToolDefinitions.formatConfirmationSummary(call.name, call.args)
                        val needsPin = curSettings.hasVoicePin
                        val deferred = CompletableDeferred<Boolean>()
                        pendingConfirmDeferred = deferred
                        _state.value = _state.value.copy(
                            statusText = if (needsPin) "Voice PIN aur Confirmation chahiye..." else "Confirmation chahiye...",
                            pendingConfirmation = ConfirmationRequest(
                                toolName = call.name,
                                summary = summary,
                                argsPreview = SensitiveDataSanitizer.sanitizeForLogs(JSONObject(call.args).toString()),
                                requiresVoicePin = needsPin
                            )
                        )
                        speakIfEnabled("Kya main confirm karoon: $summary?", languageHint)

                        val approved = deferred.await()
                        _state.value = _state.value.copy(pendingConfirmation = null)
                        val bioOk = if (approved && safetyManager.requiresBiometricCheck()) {
                            safetyManager.verifyBiometric()
                        } else true

                        if (!approved || !bioOk) {
                            finalReply = if (!approved && needsPin) {
                                "Confirmation ya Voice PIN mismatch — action cancel kar diya."
                            } else {
                                "Theek hai, sensitive action cancel kar diya."
                            }
                            actionLogRepository.record(
                                commandText = command,
                                toolName = call.name,
                                argsSummary = JSONObject(call.args).toString(),
                                resultText = finalReply,
                                status = "CANCELLED",
                                source = ActionLogInterceptor.normalizeSource(source)
                            )
                            historyRepository.addMessage(
                                ChatMessageEntity(role = "assistant", content = finalReply, brainUsed = "local")
                            )
                            historyRepository.updateTaskRun(
                                id = taskId,
                                status = "STOPPED",
                                stepsCount = step + 1,
                                brainSummary = brainsUsed.joinToString(" -> "),
                                finalReply = finalReply,
                                stepLogsJson = stepLogs.toString(),
                                finishedAt = System.currentTimeMillis()
                            )
                            speakIfEnabled(finalReply, languageHint)
                            return
                        }
                    }

                    _state.value = _state.value.copy(statusText = "Running: ${call.name}")
                    val result = actionLogInterceptor.executeLogged(
                        commandText = command,
                        toolName = call.name,
                        args = call.args,
                        source = source
                    ) {
                        extendedToolRouter.tryExecute(call.name, call.args)
                            ?: executor.execute(call.name, call.args)
                    }
                    if (result.screenshotBase64 != null && !curSettings.dataSaverEnabled) {
                        latestScreenshot = result.screenshotBase64
                    }
                    if (call.name == "open_app" && result.ok) {
                        memoryManager.recordAppUsageHabit(call.args["app_name"]?.toString().orEmpty())
                    }

                    val argsJson = SensitiveDataSanitizer.sanitizeForLogs(JSONObject(call.args).toString())

                    stepLogs.put(
                        JSONObject().apply {
                            put("step", step + 1)
                            put("brain", plan.brainUsed.label)
                            put("tool", call.name)
                            put("args", argsJson)
                            put("ok", result.ok)
                            put("output", SensitiveDataSanitizer.sanitizeForLogs(result.output).take(300))
                        }
                    )

                    historyRepository.addMessage(
                        ChatMessageEntity(
                            role = "tool",
                            content = result.output,
                            brainUsed = plan.brainUsed.name.lowercase(),
                            toolName = call.name,
                            toolArgsJson = argsJson,
                            toolResult = if (result.ok) "OK" else "ERR"
                        )
                    )

                    turns.add(
                        AgentStepTurn(
                            role = "assistant",
                            text = plan.replyText,
                            toolName = call.name,
                            toolCallId = call.id,
                            toolArgs = call.args
                        )
                    )
                    turns.add(
                        AgentStepTurn(
                            role = "tool",
                            toolName = call.name,
                            toolCallId = call.id,
                            toolResult = result.output,
                            screenshotBase64 = if (curSettings.dataSaverEnabled) null else result.screenshotBase64
                        )
                    )

                    // Small settle delay after UI actions so target window updates before next Observe
                    if (ToolDefinitions.requiresUnlockedScreen(call.name)) {
                        delay(550L)
                    }
                }
            }

            finalReply = "15 steps poore ho gaye. Jo kar sakti thi kar diya!"
            historyRepository.addMessage(
                ChatMessageEntity(role = "assistant", content = finalReply, brainUsed = "local")
            )
            historyRepository.updateTaskRun(
                id = taskId,
                status = "COMPLETED",
                stepsCount = AgentExecutionState.MAX_STEPS,
                brainSummary = brainsUsed.joinToString(" -> "),
                finalReply = finalReply,
                stepLogsJson = stepLogs.toString(),
                finishedAt = System.currentTimeMillis()
            )
            speakIfEnabled(finalReply, languageHint)
            updateWidgetLastTask(command, finalReply)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                if (activeTaskId == taskId) {
                    historyRepository.updateTaskRun(
                        id = taskId,
                        status = "STOPPED",
                        stepsCount = lastStepCompleted,
                        brainSummary = brainsUsed.joinToString(" -> "),
                        finalReply = "Cancelled before completion.",
                        stepLogsJson = stepLogs.toString(),
                        finishedAt = System.currentTimeMillis()
                    )
                }
            }
            throw ce
        } catch (t: Throwable) {
            val curKeys = keyStore.settings.value
            val safeErr = SensitiveDataSanitizer.sanitizeError(t.message, curKeys.groqApiKey, curKeys.geminiApiKey)
            stepFailedError = safeErr
            finalReply = "Task ruk gaya: $safeErr"
            withContext(NonCancellable) {
                historyRepository.addMessage(
                    ChatMessageEntity(role = "assistant", content = finalReply, brainUsed = "local")
                )
                historyRepository.updateTaskRun(
                    id = taskId,
                    status = "FAILED",
                    stepsCount = lastStepCompleted,
                    brainSummary = brainsUsed.joinToString(" -> "),
                    finalReply = finalReply,
                    stepLogsJson = stepLogs.toString(),
                    finishedAt = System.currentTimeMillis()
                )
            }
        } finally {
            if (activeTaskId == taskId) {
                activeTaskId = null
                _state.value = AgentExecutionState(
                    statusText = if (stepFailedError != null) "Error" else "Ready",
                    queuedCount = _state.value.queuedCount,
                    lastError = stepFailedError
                )
            }
        }
    }

    private suspend fun confirmAndWipeMemories(
        command: String,
        source: String,
        languageHint: String,
        settings: com.example.core.security.BabySettings
    ): String {
        val needsPin = settings.hasVoicePin
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmDeferred = deferred
        _state.value = _state.value.copy(
            isRunning = true,
            statusText = "Confirmation chahiye (Sab bhool jao)...",
            pendingConfirmation = ConfirmationRequest(
                toolName = "wipe_memory",
                summary = "Kya aap pakka sabhi saved Baby memories delete karna chahte hain? (\"Sab bhool jao\")",
                argsPreview = "{}",
                requiresVoicePin = needsPin
            )
        )
        speakIfEnabled("Kya main sach mein aapki saari saved memories delete kar doon?", languageHint)

        val approved = deferred.await()
        _state.value = _state.value.copy(pendingConfirmation = null)
        val bioOk = if (approved && safetyManager.requiresBiometricCheck()) {
            safetyManager.verifyBiometric()
        } else true

        val normSource = ActionLogInterceptor.normalizeSource(source)
        return if (approved && bioOk) {
            val out = memoryManager.wipeAll()
            actionLogRepository.record(command, "wipe_memory", "", out, status = "OK", source = normSource)
            out
        } else {
            val cancelMsg = "Theek hai, saari memories safe hain — kuch delete nahi kiya."
            actionLogRepository.record(command, "wipe_memory", "", cancelMsg, status = "CANCELLED", source = normSource)
            cancelMsg
        }
    }

    private suspend fun tryQuickLocalExecution(
        command: String,
        source: String,
        languageHint: String,
        settings: com.example.core.security.BabySettings
    ): String? {
        val lower = command.lowercase().trim()
        val normSource = ActionLogInterceptor.normalizeSource(source)

        // 1. Emergency SOS
        if (settings.emergencyModeEnabled &&
            (lower == "baby help" || lower.startsWith("baby help ") || lower.contains("emergency sos") || lower == "help me baby")
        ) {
            return safetyManager.triggerEmergencyAlert()
        }

        // 2. Direct "yaad rakhna ..." memory save
        MemoryManager.extractYaadRakhna(command)?.let { (k, v) ->
            val out = memoryManager.saveMemory(k, v)
            actionLogRepository.record(command, "save_memory", "key=$k", out, status = "OK", source = normSource)
            return out
        }

        // 3. "Notifications batao" summary
        if (lower.contains("notifications batao") || lower.contains("notification batao") ||
            lower.contains("notifications summarize") || lower.contains("notification summary")
        ) {
            val scope = when {
                lower.contains("people") || lower.contains("message") -> "people"
                lower.contains("promo") || lower.contains("offer") -> "promos"
                lower.contains("whatsapp") -> "whatsapp"
                else -> "all"
            }
            val summary = notificationSummaryManager.buildSummary(scope = scope, speakAloud = false)
            actionLogRepository.record(command, "summarize_notifications", "{\"scope\":\"$scope\"}", summary, status = "OK", source = normSource)
            return summary
        }

        // 4. Macro Recording Start / Stop voice triggers
        if (lower.contains("macro record shuru") || lower.contains("macro recording shuru") ||
            lower.contains("start macro recording")
        ) {
            val out = macroManager.startRecording()
            actionLogRepository.record(command, "start_macro_recording", "", out, status = "OK", source = normSource)
            return out
        }

        val saveMacroMatch = Regex("""(?:macro\s+save\s+karo|save\s+macro)\s+(.+)""").find(lower)
        if (saveMacroMatch != null && macroManager.isRecordingMacro.value) {
            val rawName = saveMacroMatch.groupValues[1].trim()
            val count = macroManager.recordedSteps.value.size
            val id = macroManager.stopAndSaveRecording(rawName, rawName.lowercase())
            val out = "Macro '$rawName' (#$id, $count steps) save ho gaya! Bolna \"${rawName.lowercase()} chalao\" replay karne ke liye."
            actionLogRepository.record(command, "stop_macro_recording", "name=$rawName", out, status = "OK", source = normSource)
            return out
        }

        // 5. Custom Commands ("Movie mode", etc. reusing RoutineManager)
        routineManager.findMatchingCommand(command)?.let { customCmd ->
            return routineManager.runRoutine(customCmd) { queuedCmd ->
                historyRepository.enqueueForUnlock(queuedCmd, "command")
            }
        }

        // 6. Saved / Built-in Routines ("Good morning", "Good night", "Leaving home", "Reached home", etc.)
        routineManager.findMatchingRoutine(command)?.let { routine ->
            return routineManager.runRoutine(routine) { queuedCmd ->
                historyRepository.enqueueForUnlock(queuedCmd, "routine")
            }
        }

        // 7. Recorded Macros ("<name> chalao", etc.)
        macroManager.findMatchingMacro(command)?.let { macro ->
            return macroManager.runMacro(macro) { queuedCmd ->
                historyRepository.enqueueForUnlock(queuedCmd, "macro")
            }
        }

        // 8. Offline deterministic commands (flashlight, volume, brightness, alarm, DND, open app, call contact)
        offlineCommandEngine.tryExecuteOffline(command)?.let { match ->
            actionLogRepository.record(
                commandText = command,
                toolName = match.toolName,
                argsSummary = match.args.toString(),
                resultText = match.executionResult.output,
                targetApp = match.args["app_name"]?.toString().orEmpty(),
                status = if (match.executionResult.ok) "OK" else "ERR",
                source = normSource
            )
            if (match.toolName == "open_app" && match.executionResult.ok) {
                memoryManager.recordAppUsageHabit(match.args["app_name"]?.toString().orEmpty())
            }
            return match.friendlyReply
        }

        return null
    }

    private fun updateWidgetLastTask(command: String, reply: String) {
        val summary = "${command.take(36)} → ${reply.take(38)}".trim()
        keyStore.setLastTaskSummary(summary)
        BabyWidgetProvider.refreshAllWidgets(appContext)
    }

    private fun speakIfEnabled(text: String, languageHint: String) {
        if (keyStore.settings.value.ttsRepliesEnabled) {
            ttsManager.speak(text, languageHint)
        }
    }

    private fun detectLanguageHint(text: String): String {
        if (text.any { it in '\u0900'..'\u097F' }) return "hindi"
        val hinglishWords = listOf("karo", "kardo", "batao", "kholo", "bhejo", "sunao", "chalu", "band", "kaam", "haan", "nahi", "mera", "meri", "padho")
        val lower = text.lowercase()
        return if (hinglishWords.any { lower.contains(it) }) {
            "hinglish"
        } else {
            keyStore.settings.value.preferredLanguage.name.lowercase()
        }
    }
}
