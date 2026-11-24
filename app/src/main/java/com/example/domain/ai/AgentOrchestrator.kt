package com.example.domain.ai

import android.content.Context
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.core.telemetry.ExecutionPathType
import com.example.core.telemetry.LatencyTracker
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
import com.example.features.searchplay.SearchPlayEngine
import com.example.features.widget.BabyWidgetProvider
import com.example.service.BabyAccessibilityService
import com.example.ui.theme.BabyMood
import com.example.ui.theme.BabyMoodResolver
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
    val lastError: String? = null,
    val lastErrorDetails: String? = null,
    val oneTimeNotice: String? = null,
    val streamingReply: String = "",
    val streamingSources: List<WebSourceLink> = emptyList(),
    val activeMood: BabyMood = BabyMood.CALM
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
    private val stopSignalBus: StopSignalBus,
    private val latencyTracker: LatencyTracker,
    private val searchPlayEngine: SearchPlayEngine? = null
) {
    // Legacy 17-arg constructor for unit tests
    constructor(
        appContext: Context,
        aiRouter: AiRouter,
        executor: PhoneControlExecutor,
        extendedToolRouter: ExtendedToolRouter,
        historyRepository: HistoryRepository,
        actionLogRepository: ActionLogRepository,
        actionLogInterceptor: ActionLogInterceptor,
        offlineCommandEngine: OfflineCommandEngine,
        routineManager: RoutineManager,
        macroManager: MacroManager,
        memoryManager: MemoryManager,
        notificationSummaryManager: NotificationSummaryManager,
        safetyManager: SafetyManager,
        keyStore: SecureKeyStore,
        permissionHelper: PermissionHelper,
        ttsManager: BabyTtsManager,
        stopSignalBus: StopSignalBus
    ) : this(
        appContext = appContext,
        aiRouter = aiRouter,
        executor = executor,
        extendedToolRouter = extendedToolRouter,
        historyRepository = historyRepository,
        actionLogRepository = actionLogRepository,
        actionLogInterceptor = actionLogInterceptor,
        offlineCommandEngine = offlineCommandEngine,
        routineManager = routineManager,
        macroManager = macroManager,
        memoryManager = memoryManager,
        notificationSummaryManager = notificationSummaryManager,
        safetyManager = safetyManager,
        keyStore = keyStore,
        permissionHelper = permissionHelper,
        ttsManager = ttsManager,
        stopSignalBus = stopSignalBus,
        latencyTracker = LatencyTracker(),
        searchPlayEngine = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeTaskId: Long? = null
    private var pendingConfirmDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var lastSttDurationMs: Long = 0L

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
        _state.value = _state.value.copy(lastError = null, lastErrorDetails = null)
    }

    fun clearOneTimeNotice() {
        _state.value = _state.value.copy(oneTimeNotice = null)
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
            lastErrorDetails = null,
            pendingConfirmation = null,
            activeMood = if (keyStore.settings.value.emotionIntensity == com.example.core.security.EmotionIntensity.OFF) {
                BabyMood.CALM
            } else {
                BabyMood.THINKING
            }
        )

        activeJob = scope.launch {
            runAgentLoop(command, source, languageHint ?: detectLanguageHint(command))
        }
    }

    suspend fun handleVoiceWav(wavBytes: ByteArray): String? {
        _state.value = _state.value.copy(
            statusText = "Whisper se samajh rahi hoon...",
            lastError = null,
            lastErrorDetails = null
        )
        val sttStart = System.currentTimeMillis()
        val res = aiRouter.transcribeAudio(wavBytes)
        lastSttDurationMs = (System.currentTimeMillis() - sttStart).coerceAtLeast(0L)
        return res.fold(
            onSuccess = { transcript ->
                if (!transcript.modelSwitchedNotice.isNullOrBlank()) {
                    _state.value = _state.value.copy(oneTimeNotice = transcript.modelSwitchedNotice)
                }
                if (transcript.text.isBlank()) {
                    _state.value = _state.value.copy(statusText = "Kuch sunai nahi diya")
                    null
                } else if (safetyManager.emergencyCountdown.value.active &&
                    SafetyManager.isEmergencyCancelCommand(transcript.text)
                ) {
                    safetyManager.cancelEmergencyCountdown("Voice cancel: ${transcript.text}")
                    _state.value = AgentExecutionState(
                        statusText = "SOS Cancelled",
                        oneTimeNotice = _state.value.oneTimeNotice
                    )
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
                val failure = (err as? AiCallException)?.failure
                val friendlyErr = failure?.userMessage ?: SensitiveDataSanitizer.sanitizeError(
                    err.message ?: "Voice transcribe nahi ho paya",
                    settings.inceptionApiKey,
                    settings.groqApiKey,
                    settings.geminiApiKey
                )
                val techDetails = failure?.technicalDetails ?: friendlyErr
                historyRepository.addMessage(
                    ChatMessageEntity(
                        role = "assistant",
                        content = friendlyErr,
                        brainUsed = "local",
                        toolName = "ai_error",
                        toolResult = techDetails
                    )
                )
                _state.value = _state.value.copy(
                    statusText = "Error",
                    lastError = friendlyErr,
                    lastErrorDetails = techDetails
                )
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
        var finalResolvedMood = BabyMood.CALM

        // Safety-first distress & helpline guard (before any tool or cloud call)
        BabyMoodResolver.detectDistressAndHelpline(command)?.let { helplineReply ->
            finalResolvedMood = BabyMood.EMPATHETIC
            historyRepository.addMessage(
                ChatMessageEntity(role = "assistant", content = helplineReply, brainUsed = "local")
            )
            historyRepository.updateTaskRun(
                id = taskId,
                status = "COMPLETED",
                stepsCount = 1,
                brainSummary = "Local",
                finalReply = helplineReply,
                stepLogsJson = "[]",
                finishedAt = System.currentTimeMillis()
            )
            speakIfEnabled(helplineReply, languageHint, finalResolvedMood)
            if (activeTaskId == taskId) {
                activeTaskId = null
                _state.value = AgentExecutionState(
                    statusText = "Ready",
                    activeMood = finalResolvedMood
                )
            }
            return
        }

        // Explicit confirmation gate for "Sab bhool jao" before wiping all memories
        if (MemoryManager.isWipeMemoryPhrase(command)) {
            val wipeReply = confirmAndWipeMemories(command, source, languageHint, settings)
            finalResolvedMood = BabyMoodResolver.resolveMood(
                rawReplyText = wipeReply,
                userCommand = command,
                intensity = settings.emotionIntensity,
                understandUserMood = settings.understandMyMoodEnabled
            ).mood
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
            speakIfEnabled(wipeReply, languageHint, finalResolvedMood)
            if (activeTaskId == taskId) {
                activeTaskId = null
                _state.value = AgentExecutionState(statusText = "Ready", activeMood = finalResolvedMood)
            }
            return
        }

        val loopStartMs = System.currentTimeMillis()
        val sttMs = if (source == "voice") lastSttDurationMs.also { lastSttDurationMs = 0L } else 0L

        // Fast local deterministic paths before hitting Mercury/Gemini/Groq APIs
        val quickStartMs = System.currentTimeMillis()
        val quickLocalOutcome = tryQuickLocalExecution(command, source, languageHint, settings)
        if (quickLocalOutcome != null) {
            val quickExecMs = (System.currentTimeMillis() - quickStartMs).coerceAtLeast(1L)
            val isSos = command.lowercase().let {
                it == "baby help" || it.startsWith("baby help ") || it.contains("emergency sos") || it == "help me baby"
            }
            finalResolvedMood = BabyMoodResolver.resolveMood(
                rawReplyText = quickLocalOutcome,
                userCommand = command,
                intensity = settings.emotionIntensity,
                understandUserMood = settings.understandMyMoodEnabled,
                isSosOrAlert = isSos
            ).mood
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
            val ttsStartMs = System.currentTimeMillis()
            speakIfEnabled(quickLocalOutcome, languageHint, finalResolvedMood)
            val ttsMs = (System.currentTimeMillis() - ttsStartMs).coerceAtLeast(0L)
            latencyTracker.recordSimple(
                command = command,
                pathType = ExecutionPathType.FAST_PATH,
                sttMs = sttMs,
                intentOrLlmMs = quickExecMs,
                toolMs = 0L,
                ttsMs = ttsMs,
                firstSpokenMs = quickExecMs,
                totalMs = (System.currentTimeMillis() - loopStartMs).coerceAtLeast(1L),
                status = "OK"
            )
            updateWidgetLastTask(command, quickLocalOutcome)
            if (activeTaskId == taskId) {
                activeTaskId = null
                _state.value = AgentExecutionState(statusText = "Ready", activeMood = finalResolvedMood)
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
        var stepFailedErrorDetails: String? = null
        var lastStepCompleted = 0
        var totalLlmMs = 0L
        var totalToolMs = 0L
        var firstSpokenMs = 0L
        var lastBrainUsed: BrainType = BrainType.MERCURY
        var ackSpoken = false

        if (source == "voice" && isLikelyMultiStepCommand(command)) {
            ackSpoken = true
            firstSpokenMs = (System.currentTimeMillis() - loopStartMs).coerceAtLeast(1L)
            speakIfEnabled("Theek hai, dekh rahi hoon...", languageHint)
        }

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

                _state.value = _state.value.copy(
                    isRunning = true,
                    currentStep = step + 1,
                    statusText = "Soch rahi hoon (${step + 1}/15)...",
                    streamingReply = ""
                )

                val streamBuf = StringBuilder()
                var streamedFirstSentenceSpoken = false
                var spokenStreamingPrefix = ""
                val llmStepStartMs = System.currentTimeMillis()
                val planRes = aiRouter.planNextStep(
                    turns = turns,
                    screenContext = screenContext,
                    screenshotBase64 = latestScreenshot,
                    stepIndex = step,
                    isLocked = isLocked,
                    userLanguageHint = languageHint,
                    memoryBlock = relevantMemoryBlock,
                    onStreamingToken = { token ->
                        streamBuf.append(token)
                        val cleanStreamRaw = AiResponseParser.stripReasoningForStreaming(streamBuf.toString())
                        val cleanStream = BabyMoodResolver.stripMoodTagForStreaming(cleanStreamRaw)
                        if (cleanStream.isNotBlank()) {
                            _state.value = _state.value.copy(streamingReply = cleanStream)
                            if (!streamedFirstSentenceSpoken &&
                                settings.ttsRepliesEnabled &&
                                !cleanStream.trimStart().startsWith("{")
                            ) {
                                val firstSentence = BabyTtsManager.splitSentences(cleanStream).firstOrNull()
                                val hasBoundary = cleanStream.any { it == '.' || it == '!' || it == '?' || it == '।' || it == '\n' }
                                if (hasBoundary && !firstSentence.isNullOrBlank() && firstSentence.length >= 12) {
                                    streamedFirstSentenceSpoken = true
                                    spokenStreamingPrefix = firstSentence
                                    if (firstSpokenMs == 0L) {
                                        firstSpokenMs = (System.currentTimeMillis() - loopStartMs).coerceAtLeast(1L)
                                    }
                                    ttsManager.speakStreamingSentence(firstSentence, languageHint, flushFirst = !ackSpoken)
                                }
                            }
                        }
                    }
                )
                totalLlmMs += (System.currentTimeMillis() - llmStepStartMs).coerceAtLeast(0L)
                latestScreenshot = null

                if (planRes.isFailure) {
                    val curKeys = keyStore.settings.value
                    val err = planRes.exceptionOrNull()
                    val failure = (err as? AiCallException)?.failure

                    // Try offline fallback when cloud providers fail on supported device commands
                    if (step == 0) {
                        val offlineMatch = offlineCommandEngine.tryExecuteOffline(command, fallbackMode = true)
                        if (offlineMatch != null) {
                            actionLogRepository.record(
                                commandText = command,
                                toolName = offlineMatch.toolName,
                                argsSummary = offlineMatch.args.toString(),
                                resultText = offlineMatch.executionResult.output,
                                targetApp = offlineMatch.args["app_name"]?.toString().orEmpty(),
                                status = if (offlineMatch.executionResult.ok) "OK" else "ERR",
                                source = "offline_fallback"
                            )
                            if (offlineMatch.toolName == "open_app" && offlineMatch.executionResult.ok) {
                                memoryManager.recordAppUsageHabit(offlineMatch.args["app_name"]?.toString().orEmpty())
                            }
                            finalReply = "Cloud AI abhi available nahi hai, isliye offline mode mein handle kar diya: ${offlineMatch.friendlyReply}"
                            historyRepository.addMessage(
                                ChatMessageEntity(
                                    role = "assistant",
                                    content = finalReply,
                                    brainUsed = "local"
                                )
                            )
                            historyRepository.updateTaskRun(
                                id = taskId,
                                status = "COMPLETED",
                                stepsCount = step + 1,
                                brainSummary = "Offline Fallback",
                                finalReply = finalReply,
                                stepLogsJson = stepLogs.toString(),
                                finishedAt = System.currentTimeMillis()
                            )
                            speakIfEnabled(finalReply, languageHint)
                            updateWidgetLastTask(command, finalReply)
                            return
                        }
                    }

                    val friendlyMsg = failure?.userMessage ?: SensitiveDataSanitizer.sanitizeError(
                        err?.message ?: "AI abhi jawab nahi de pa rahi hai. Retry karo ya Settings > AI Models check karo.",
                        curKeys.inceptionApiKey,
                        curKeys.groqApiKey,
                        curKeys.geminiApiKey
                    )
                    val techDetails = failure?.technicalDetails ?: friendlyMsg
                    stepFailedError = friendlyMsg
                    stepFailedErrorDetails = techDetails
                    finalReply = friendlyMsg
                    finalResolvedMood = BabyMoodResolver.resolveMood(
                        rawReplyText = friendlyMsg,
                        userCommand = command,
                        intensity = curKeys.emotionIntensity,
                        understandUserMood = curKeys.understandMyMoodEnabled,
                        isError = true
                    ).mood
                    historyRepository.addMessage(
                        ChatMessageEntity(
                            role = "assistant",
                            content = friendlyMsg,
                            brainUsed = "local",
                            toolName = "ai_error",
                            toolResult = techDetails
                        )
                    )
                    historyRepository.updateTaskRun(
                        id = taskId,
                        status = "FAILED",
                        stepsCount = step + 1,
                        brainSummary = brainsUsed.joinToString(", "),
                        finalReply = friendlyMsg,
                        stepLogsJson = stepLogs.toString(),
                        finishedAt = System.currentTimeMillis()
                    )
                    speakIfEnabled(friendlyMsg, languageHint, finalResolvedMood)
                    return
                }

                val plan = planRes.getOrThrow()
                lastBrainUsed = plan.brainUsed
                brainsUsed.add(plan.brainUsed.label)
                _state.value = _state.value.copy(
                    activeBrain = plan.brainUsed,
                    oneTimeNotice = plan.modelSwitchedNotice ?: _state.value.oneTimeNotice,
                    streamingSources = plan.sources
                )

                if (plan.toolCalls.isEmpty()) {
                    val curSettings = keyStore.settings.value
                    val moodOutcome = BabyMoodResolver.resolveMood(
                        rawReplyText = plan.replyText ?: "Ho gaya!",
                        userCommand = command,
                        intensity = curSettings.emotionIntensity,
                        understandUserMood = curSettings.understandMyMoodEnabled
                    )
                    val baseReply = moodOutcome.cleanedText.ifBlank { "Ho gaya!" }
                    finalResolvedMood = moodOutcome.mood
                    finalReply = if (plan.sources.isNotEmpty()) {
                        val sourceLines = plan.sources.take(3).joinToString("\n") { "- [${it.title}](${it.uri})" }
                        "$baseReply\n\nSources:\n$sourceLines"
                    } else baseReply
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
                    if (firstSpokenMs == 0L) {
                        firstSpokenMs = (System.currentTimeMillis() - loopStartMs).coerceAtLeast(1L)
                    }
                    val ttsStart = System.currentTimeMillis()
                    if (streamedFirstSentenceSpoken && spokenStreamingPrefix.isNotBlank()) {
                        val remaining = baseReply.removePrefix(spokenStreamingPrefix).trim()
                        if (remaining.isNotEmpty() && curSettings.ttsRepliesEnabled) {
                            ttsManager.speakStreamingSentence(remaining, languageHint, flushFirst = false)
                        }
                    } else {
                        speakIfEnabled(baseReply, languageHint, finalResolvedMood)
                    }
                    val ttsMs = (System.currentTimeMillis() - ttsStart).coerceAtLeast(0L)
                    val path = when (lastBrainUsed) {
                        BrainType.MERCURY -> ExecutionPathType.MERCURY
                        BrainType.GEMINI -> ExecutionPathType.GEMINI
                        BrainType.GROQ -> ExecutionPathType.GROQ
                    }
                    latencyTracker.recordSimple(
                        command = command,
                        pathType = path,
                        sttMs = sttMs,
                        intentOrLlmMs = totalLlmMs,
                        toolMs = totalToolMs,
                        ttsMs = ttsMs,
                        firstSpokenMs = firstSpokenMs,
                        totalMs = (System.currentTimeMillis() - loopStartMs).coerceAtLeast(1L),
                        status = "OK"
                    )
                    updateWidgetLastTask(command, baseReply)
                    return
                }

                if (!ackSpoken && source == "voice" && step == 0 && plan.toolCalls.isNotEmpty() &&
                    ToolDefinitions.requiresUnlockedScreen(plan.toolCalls.first().name)
                ) {
                    ackSpoken = true
                    firstSpokenMs = (System.currentTimeMillis() - loopStartMs).coerceAtLeast(1L)
                    speakIfEnabled("Theek hai, abhi karti hoon.", languageHint)
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
                    val toolStartMs = System.currentTimeMillis()
                    val result = actionLogInterceptor.executeLogged(
                        commandText = command,
                        toolName = call.name,
                        args = call.args,
                        source = source
                    ) {
                        extendedToolRouter.tryExecute(call.name, call.args)
                            ?: executor.execute(call.name, call.args)
                    }
                    totalToolMs += (System.currentTimeMillis() - toolStartMs).coerceAtLeast(0L)
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
            val failure = (t as? AiCallException)?.failure
            val safeErr = failure?.userMessage ?: SensitiveDataSanitizer.sanitizeError(
                t.message ?: "Task complete nahi ho paya. Retry karo.",
                curKeys.inceptionApiKey,
                curKeys.groqApiKey,
                curKeys.geminiApiKey
            )
            val details = failure?.technicalDetails ?: safeErr
            stepFailedError = safeErr
            stepFailedErrorDetails = details
            finalReply = safeErr
            finalResolvedMood = BabyMoodResolver.resolveMood(
                rawReplyText = safeErr,
                userCommand = command,
                intensity = curKeys.emotionIntensity,
                isError = true
            ).mood
            withContext(NonCancellable) {
                historyRepository.addMessage(
                    ChatMessageEntity(
                        role = "assistant",
                        content = safeErr,
                        brainUsed = "local",
                        toolName = "ai_error",
                        toolResult = details
                    )
                )
                historyRepository.updateTaskRun(
                    id = taskId,
                    status = "FAILED",
                    stepsCount = lastStepCompleted,
                    brainSummary = brainsUsed.joinToString(" -> "),
                    finalReply = safeErr,
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
                    lastError = stepFailedError,
                    lastErrorDetails = stepFailedErrorDetails,
                    oneTimeNotice = _state.value.oneTimeNotice,
                    activeMood = finalResolvedMood
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

        // 8. Search-and-Play recipes ("YouTube par Arijit Singh ka gaana bajao", "Spotify pe Kesariya chalao", etc.)
        if (searchPlayEngine != null && !permissionHelper.isDeviceLocked()) {
            SearchPlayEngine.extractSearchPlayIntent(command)?.let { intent ->
                val result = searchPlayEngine.executeSearchAndPlay(intent.appKey, intent.query, intent.autoPlay)
                actionLogRepository.record(
                    commandText = command,
                    toolName = "search_in_app",
                    argsSummary = "app=${intent.appKey}, query=${intent.query}, autoPlay=${intent.autoPlay}",
                    resultText = result.output,
                    targetApp = intent.appKey,
                    status = if (result.ok) "OK" else "ERR",
                    source = normSource
                )
                return result.output
            }
        }

        // 9. Offline deterministic commands (flashlight, volume, brightness, alarm, DND, open app, call contact)
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

    private fun speakIfEnabled(text: String, languageHint: String, mood: BabyMood = BabyMood.CALM) {
        val cur = keyStore.settings.value
        if (cur.ttsRepliesEnabled) {
            val style = BabyMoodResolver.ttsStyleDirective(mood, cur.emotionIntensity)
            ttsManager.speak(text, languageHint, styleDirective = style)
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

    private fun isLikelyMultiStepCommand(command: String): Boolean {
        val l = command.lowercase()
        return l.contains("youtube") ||
            l.contains("spotify") ||
            l.contains("amazon") ||
            l.contains("flipkart") ||
            l.contains("screen") ||
            l.contains("camera") ||
            l.contains("search") ||
            l.contains("bajao") ||
            l.contains("dhundo") ||
            l.contains("summarize")
    }
}
