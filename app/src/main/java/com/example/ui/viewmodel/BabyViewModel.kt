package com.example.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.security.AiModelConfig
import com.example.core.security.AiRole
import com.example.core.security.AppLanguage
import com.example.core.security.BabySettings
import com.example.core.security.PreferredBrain
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.core.telemetry.LatencyRecord
import com.example.core.telemetry.LatencyTracker
import com.example.data.local.AppAliasEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.HistoryRepository
import com.example.data.local.TaskRunEntity
import com.example.domain.ai.AgentExecutionState
import com.example.domain.ai.AgentOrchestrator
import com.example.domain.tools.AppLaunchAttemptLog
import com.example.domain.tools.AppResolver
import com.example.domain.tools.IndexedApp
import com.example.features.searchplay.SearchPlayEngine
import com.example.service.BabyForegroundService
import com.example.ui.state.ApiKeyHealthTester
import com.example.ui.state.BabyUiPreferences
import com.example.ui.state.CompareBenchmarkUiState
import com.example.ui.state.KeyTestResult
import com.example.ui.state.ModelBenchmarkRunner
import com.example.ui.state.ModelsTestUiState
import com.example.ui.state.UiPreferencesStore
import com.example.ui.theme.BabySoundCues
import com.example.ui.theme.BabyThemeMode
import com.example.util.OemAutostartGuide
import com.example.util.PermissionHelper
import com.example.util.PermissionSnapshot
import com.example.voice.AudioRecorderManager
import com.example.voice.BabyTtsManager
import com.example.voice.WakeWordDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BabyHeaderStatus {
    LISTENING,
    THINKING,
    SPEAKING,
    LOCKED_QUEUE_PENDING,
    RATE_LIMITED,
    OFFLINE,
    NO_API_KEY,
    PAUSED
}

@HiltViewModel
class BabyViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val keyStore: SecureKeyStore,
    private val historyRepository: HistoryRepository,
    private val orchestrator: AgentOrchestrator,
    private val audioRecorder: AudioRecorderManager,
    private val wakeWordDetector: WakeWordDetector,
    private val ttsManager: BabyTtsManager,
    val permissionHelper: PermissionHelper,
    private val uiPreferencesStore: UiPreferencesStore,
    private val apiKeyHealthTester: ApiKeyHealthTester,
    private val modelBenchmarkRunner: ModelBenchmarkRunner,
    private val latencyTracker: LatencyTracker,
    private val appResolver: AppResolver,
    private val searchPlayEngine: SearchPlayEngine
) : ViewModel() {

    val settings: StateFlow<BabySettings> = keyStore.settings
    val uiPrefs: StateFlow<BabyUiPreferences> = uiPreferencesStore.state

    val chatMessages: StateFlow<List<ChatMessageEntity>> = historyRepository.chatMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taskRuns: StateFlow<List<TaskRunEntity>> = historyRepository.taskRuns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agentState: StateFlow<AgentExecutionState> = orchestrator.state
    val isRecording: StateFlow<Boolean> = audioRecorder.isRecording
    val micAmplitude: StateFlow<Float> = audioRecorder.amplitude
    val isWakeListening: StateFlow<Boolean> = wakeWordDetector.isListening
    val isTtsSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

    private val _permissions = MutableStateFlow(permissionHelper.getSnapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    val oemGuide: OemAutostartGuide = permissionHelper.getOemAutostartGuide()

    private val _voiceOverlayOpen = MutableStateFlow(false)
    val voiceOverlayOpen: StateFlow<Boolean> = _voiceOverlayOpen.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _rateLimitDismissed = MutableStateFlow(false)
    private val _inceptionKeyTest = MutableStateFlow<KeyTestResult>(KeyTestResult.Idle)
    val inceptionKeyTest: StateFlow<KeyTestResult> = _inceptionKeyTest.asStateFlow()

    private val _groqKeyTest = MutableStateFlow<KeyTestResult>(KeyTestResult.Idle)
    val groqKeyTest: StateFlow<KeyTestResult> = _groqKeyTest.asStateFlow()

    private val _geminiKeyTest = MutableStateFlow<KeyTestResult>(KeyTestResult.Idle)
    val geminiKeyTest: StateFlow<KeyTestResult> = _geminiKeyTest.asStateFlow()

    private val _liveMercuryModels = MutableStateFlow<List<String>>(emptyList())
    val liveMercuryModels: StateFlow<List<String>> = _liveMercuryModels.asStateFlow()

    private val _liveGroqModels = MutableStateFlow<List<String>>(emptyList())
    val liveGroqModels: StateFlow<List<String>> = _liveGroqModels.asStateFlow()

    private val _liveGeminiModels = MutableStateFlow<List<String>>(emptyList())
    val liveGeminiModels: StateFlow<List<String>> = _liveGeminiModels.asStateFlow()

    private val _liveModelsLoading = MutableStateFlow(false)
    val liveModelsLoading: StateFlow<Boolean> = _liveModelsLoading.asStateFlow()

    private val _liveModelsError = MutableStateFlow<String?>(null)
    val liveModelsError: StateFlow<String?> = _liveModelsError.asStateFlow()

    private val _modelsTestState = MutableStateFlow(ModelsTestUiState())
    val modelsTestState: StateFlow<ModelsTestUiState> = _modelsTestState.asStateFlow()

    private val _compareState = MutableStateFlow(CompareBenchmarkUiState())
    val compareState: StateFlow<CompareBenchmarkUiState> = _compareState.asStateFlow()

    val latencyRecords: StateFlow<List<LatencyRecord>> = latencyTracker.records
    val appLaunchAttempts: StateFlow<List<AppLaunchAttemptLog>> = appResolver.launchAttempts
    val appAliases: StateFlow<List<AppAliasEntity>> = (appResolver.observeAliases()
        ?: MutableStateFlow(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val micPipelineState: StateFlow<com.example.voice.MicPipelineState> = wakeWordDetector.pipelineState
    val wakeRmsLevel: StateFlow<Float> = wakeWordDetector.rmsLevel
    val wakeScore: StateFlow<Float> = wakeWordDetector.wakeScore
    val watchdogRestarts: StateFlow<Int> = wakeWordDetector.watchdogRestarts
    val watchdogLogs: StateFlow<List<String>> = wakeWordDetector.watchdogLogs
    val spotterEngineName: String
        get() = wakeWordDetector.spotterEngineName

    private val _installedApps = MutableStateFlow<List<IndexedApp>>(emptyList())
    val installedApps: StateFlow<List<IndexedApp>> = _installedApps.asStateFlow()

    val searchRecipes: StateFlow<List<com.example.data.local.SearchRecipeEntity>> =
        (searchPlayEngine.observeRecipes() ?: MutableStateFlow(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _recipeTestOutput = MutableStateFlow<String?>(null)
    val recipeTestOutput: StateFlow<String?> = _recipeTestOutput.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            appResolver.ensureDefaultAliasesSeeded()
            searchPlayEngine.ensureDefaultRecipesSeeded()
            _installedApps.value = appResolver.getInstalledAppsSnapshot()
        }
    }

    val isOffline: StateFlow<Boolean> = callbackFlow {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        fun currentOffline(): Boolean {
            if (cm == null) return false
            val active = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(active) ?: return true
            return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        trySend(currentOffline())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentOffline())
            }

            override fun onLost(network: Network) {
                trySend(currentOffline())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        val registered = runCatching {
            cm?.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)
        awaitClose {
            if (registered) {
                runCatching { cm?.unregisterNetworkCallback(callback) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isRateLimited: StateFlow<Boolean> = combine(
        agentState,
        chatMessages,
        _rateLimitDismissed
    ) { exec, messages, dismissed ->
        if (dismissed || exec.isRunning || !exec.lastError.isNullOrBlank()) {
            false
        } else {
            val latestAssistant = messages.lastOrNull { it.role != "user" && it.role != "tool" }?.content.orEmpty()
            val textToCheck = "${exec.statusText} $latestAssistant".lowercase()
            textToCheck.contains("429") ||
                textToCheck.contains("rate limit") ||
                textToCheck.contains("quota exceeded") ||
                textToCheck.contains("too many requests")
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val headerStatus: StateFlow<BabyHeaderStatus> = combine(
        isRecording,
        agentState,
        isTtsSpeaking,
        isOffline,
        settings
    ) { recording, exec, speaking, offline, currentSettings ->
        Triple(recording, exec, Triple(speaking, offline, currentSettings))
    }.combine(combine(isWakeListening, isRateLimited) { wake, rate -> wake to rate }) { first, second ->
        val (recording, exec, triple) = first
        val (speaking, offline, currentSettings) = triple
        val (wake, rateLimited) = second
        when {
            recording -> BabyHeaderStatus.LISTENING
            exec.isRunning -> BabyHeaderStatus.THINKING
            speaking -> BabyHeaderStatus.SPEAKING
            exec.queuedCount > 0 -> BabyHeaderStatus.LOCKED_QUEUE_PENDING
            rateLimited -> BabyHeaderStatus.RATE_LIMITED
            offline -> BabyHeaderStatus.OFFLINE
            !currentSettings.hasAnyApiKey -> BabyHeaderStatus.NO_API_KEY
            wake || currentSettings.wakeWordEnabled -> BabyHeaderStatus.LISTENING
            else -> BabyHeaderStatus.PAUSED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BabyHeaderStatus.PAUSED)

    fun refreshPermissions() {
        _permissions.value = permissionHelper.getSnapshot()
    }

    fun refreshUiSnapshot() {
        viewModelScope.launch {
            _isRefreshing.value = true
            refreshPermissions()
            delay(350L)
            _isRefreshing.value = false
        }
    }

    fun startBackgroundService() {
        runCatching {
            BabyForegroundService.start(appContext)
        }
    }

    fun sendTextCommand(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _rateLimitDismissed.value = false
        uiPreferencesStore.recordQuickCommandUsage(clean)
        orchestrator.submitUserCommand(clean, source = "text")
    }

    fun regenerateFromAssistantMessage(assistantMessageId: Long) {
        val list = chatMessages.value
        val idx = list.indexOfFirst { it.id == assistantMessageId }
        if (idx <= 0) return
        val precedingUser = list.subList(0, idx).lastOrNull { it.role == "user" } ?: return
        sendTextCommand(precedingUser.content)
    }

    fun speakMessage(text: String) {
        if (text.isBlank()) return
        ttsManager.speak(text, settings.value.preferredLanguage.name.lowercase())
    }

    fun openVoiceOverlay() {
        _voiceOverlayOpen.value = true
        if (!audioRecorder.isRecording.value) {
            toggleVoiceRecording()
        }
    }

    fun closeVoiceOverlay() {
        _voiceOverlayOpen.value = false
        if (audioRecorder.isRecording.value) {
            stopAndProcessVoice()
        }
    }

    fun startHoldToTalk() {
        if (!audioRecorder.isRecording.value) {
            toggleVoiceRecording()
        }
    }

    fun stopHoldToTalk() {
        if (audioRecorder.isRecording.value) {
            stopAndProcessVoice()
        }
    }

    fun interruptOrStopVoice() {
        if (audioRecorder.isRecording.value) {
            stopAndProcessVoice()
        } else if (ttsManager.isSpeaking.value) {
            ttsManager.stop()
        } else if (agentState.value.isRunning) {
            stopCurrentTask("Voice orb tap interrupt")
        } else {
            toggleVoiceRecording()
        }
    }

    fun toggleVoiceRecording() {
        if (audioRecorder.isRecording.value) {
            stopAndProcessVoice()
        } else {
            _rateLimitDismissed.value = false
            wakeWordDetector.pauseForRecording()
            ttsManager.stop()
            val started = audioRecorder.startRecording(
                maxDurationMs = 18_000L,
                autoSilenceStop = true,
                onSilenceDetected = {
                    viewModelScope.launch(Dispatchers.IO) {
                        stopAndProcessVoice()
                    }
                }
            )
            if (started) {
                BabySoundCues.playStartListening(uiPrefs.value.listeningSoundCuesEnabled)
            } else {
                wakeWordDetector.resumeAfterRecording()
                viewModelScope.launch {
                    historyRepository.addMessage(
                        ChatMessageEntity(
                            role = "assistant",
                            content = "Mic permission check karo ya microphone busy hai.",
                            brainUsed = "local"
                        )
                    )
                }
            }
        }
    }

    private fun stopAndProcessVoice() {
        BabySoundCues.playStopListening(uiPrefs.value.listeningSoundCuesEnabled)
        viewModelScope.launch(Dispatchers.IO) {
            val wav = audioRecorder.stopAndBuildWav()
            if (wav != null) {
                orchestrator.handleVoiceWav(wav)
            }
            wakeWordDetector.resumeAfterRecording()
        }
    }

    fun stopCurrentTask(reason: String = "Stopped by user button") {
        orchestrator.stopCurrentTask(reason)
    }

    fun approveConfirmation(approved: Boolean, enteredPin: String? = null) {
        orchestrator.approveConfirmation(approved, enteredPin)
    }

    fun clearAgentError() {
        orchestrator.clearLastError()
    }

    fun clearOneTimeNotice() {
        orchestrator.clearOneTimeNotice()
    }

    fun saveApiKeys(geminiKey: String, groqKey: String) {
        keyStore.updateApiKeys(geminiKey, groqKey)
        if (geminiKey.isNotBlank() || groqKey.isNotBlank()) {
            uiPreferencesStore.setApiKeyBannerDismissed(false)
        }
    }

    fun saveAllApiKeys(inceptionKey: String, geminiKey: String, groqKey: String) {
        keyStore.updateAllApiKeys(inceptionKey, geminiKey, groqKey)
        if (inceptionKey.isNotBlank() || geminiKey.isNotBlank() || groqKey.isNotBlank()) {
            uiPreferencesStore.setApiKeyBannerDismissed(false)
        }
    }

    fun testInceptionKey(key: String, baseUrlOverride: String? = null) {
        viewModelScope.launch {
            _inceptionKeyTest.value = KeyTestResult.Testing
            _inceptionKeyTest.value = apiKeyHealthTester.testInceptionKey(
                key,
                baseUrlOverride ?: settings.value.modelConfig.inceptionBaseUrl
            )
        }
    }

    fun testGroqKey(key: String) {
        viewModelScope.launch {
            _groqKeyTest.value = KeyTestResult.Testing
            _groqKeyTest.value = apiKeyHealthTester.testGroqKey(key, settings.value.modelConfig.groqBaseUrl)
        }
    }

    fun testGeminiKey(key: String) {
        viewModelScope.launch {
            _geminiKeyTest.value = KeyTestResult.Testing
            _geminiKeyTest.value = apiKeyHealthTester.testGeminiKey(key, settings.value.modelConfig.geminiBaseUrl)
        }
    }

    fun resetKeyTests() {
        _inceptionKeyTest.value = KeyTestResult.Idle
        _groqKeyTest.value = KeyTestResult.Idle
        _geminiKeyTest.value = KeyTestResult.Idle
    }

    fun clearApiKeys() {
        keyStore.clearApiKeys()
        _liveMercuryModels.value = emptyList()
        _liveGroqModels.value = emptyList()
        _liveGeminiModels.value = emptyList()
        _liveModelsError.value = null
        _modelsTestState.value = ModelsTestUiState()
        resetKeyTests()
    }

    fun updateModelConfig(config: AiModelConfig) {
        keyStore.updateModelConfig(config)
    }

    fun resetModelConfig() {
        keyStore.resetModelConfig()
    }

    fun refreshLiveGroqModels(keyOverride: String? = null) {
        val keyToUse = (keyOverride ?: settings.value.groqApiKey).trim()
        if (keyToUse.isBlank()) {
            _liveModelsError.value = "Groq API key pehle daalo."
            return
        }
        viewModelScope.launch {
            _liveModelsLoading.value = true
            _liveModelsError.value = null
            apiKeyHealthTester.fetchGroqModels(keyToUse, settings.value.modelConfig.groqBaseUrl).fold(
                onSuccess = { models -> _liveGroqModels.value = models },
                onFailure = { err -> _liveModelsError.value = SensitiveDataSanitizer.sanitizeError(err.message, keyToUse) }
            )
            _liveModelsLoading.value = false
        }
    }

    fun refreshAllLiveModels(
        inceptionKeyOverride: String? = null,
        geminiKeyOverride: String? = null,
        groqKeyOverride: String? = null
    ) {
        val incKey = (inceptionKeyOverride ?: settings.value.inceptionApiKey).trim()
        val gemKey = (geminiKeyOverride ?: settings.value.geminiApiKey).trim()
        val gKey = (groqKeyOverride ?: settings.value.groqApiKey).trim()
        val cfg = settings.value.modelConfig
        viewModelScope.launch {
            _liveModelsLoading.value = true
            _liveModelsError.value = null
            val errs = mutableListOf<String>()
            if (incKey.isNotBlank()) {
                apiKeyHealthTester.fetchMercuryModels(incKey, cfg.inceptionBaseUrl).fold(
                    onSuccess = { _liveMercuryModels.value = it },
                    onFailure = { errs += "Mercury: ${SensitiveDataSanitizer.sanitizeError(it.message, incKey)}" }
                )
            }
            if (gemKey.isNotBlank()) {
                apiKeyHealthTester.fetchGeminiModels(gemKey, cfg.geminiBaseUrl).fold(
                    onSuccess = { _liveGeminiModels.value = it },
                    onFailure = { errs += "Gemini: ${SensitiveDataSanitizer.sanitizeError(it.message, gemKey)}" }
                )
            }
            if (gKey.isNotBlank()) {
                apiKeyHealthTester.fetchGroqModels(gKey, cfg.groqBaseUrl).fold(
                    onSuccess = { _liveGroqModels.value = it },
                    onFailure = { errs += "Groq: ${SensitiveDataSanitizer.sanitizeError(it.message, gKey)}" }
                )
            }
            _liveModelsError.value = errs.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            _liveModelsLoading.value = false
        }
    }

    fun testSingleRole(
        role: AiRole,
        config: AiModelConfig = settings.value.modelConfig,
        inceptionKeyOverride: String? = null,
        geminiKeyOverride: String? = null,
        groqKeyOverride: String? = null
    ) {
        val incKey = (inceptionKeyOverride ?: settings.value.inceptionApiKey).trim()
        val gemKey = (geminiKeyOverride ?: settings.value.geminiApiKey).trim()
        val gKey = (groqKeyOverride ?: settings.value.groqApiKey).trim()
        keyStore.updateModelConfig(config)
        viewModelScope.launch {
            _modelsTestState.value = _modelsTestState.value.copy(isTesting = true)
            val result = apiKeyHealthTester.testSingleRole(role, config, incKey, gemKey, gKey)
            val existing = _modelsTestState.value.items.filterNot { it.role == role && it.label == result.label }
            _modelsTestState.value = ModelsTestUiState(isTesting = false, items = existing + result)
        }
    }

    fun testAllAiModels(
        config: AiModelConfig = settings.value.modelConfig,
        groqKeyOverride: String? = null,
        geminiKeyOverride: String? = null,
        inceptionKeyOverride: String? = null
    ) {
        val incKey = (inceptionKeyOverride ?: settings.value.inceptionApiKey).trim()
        val gKey = (groqKeyOverride ?: settings.value.groqApiKey).trim()
        val gemKey = (geminiKeyOverride ?: settings.value.geminiApiKey).trim()
        keyStore.updateModelConfig(config)
        viewModelScope.launch {
            _modelsTestState.value = ModelsTestUiState(isTesting = true)
            _modelsTestState.value = apiKeyHealthTester.testAllRoles(incKey, gKey, gemKey, config)
        }
    }

    fun runCompareBenchmark() {
        val s = settings.value
        viewModelScope.launch {
            _compareState.value = CompareBenchmarkUiState(isRunning = true, statusText = "Starting 30 Hinglish commands...")
            val finalState = modelBenchmarkRunner.runBenchmark(
                config = s.modelConfig,
                inceptionKey = s.inceptionApiKey,
                geminiKey = s.geminiApiKey,
                groqKey = s.groqApiKey,
                onProgress = { progress -> _compareState.value = progress }
            )
            _compareState.value = finalState
        }
    }

    fun updateGeminiModel(model: String) {
        keyStore.updateGeminiModel(model)
    }

    fun updateGroqModel(model: String) {
        keyStore.updateChatModel(model)
    }

    fun updatePreferredBrain(brain: PreferredBrain) {
        keyStore.updatePreferredBrain(brain)
    }

    fun setAutoFallback(enabled: Boolean) {
        keyStore.setAutoFallback(enabled)
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        keyStore.setWakeWordEnabled(enabled)
        if (enabled) startBackgroundService()
    }

    fun setWakeWordSensitivity(value: Float) {
        keyStore.setWakeWordSensitivity(value)
    }

    fun setWakeWordBatterySaver(enabled: Boolean) {
        keyStore.setWakeWordBatterySaver(enabled)
    }

    fun triggerTestWake() {
        wakeWordDetector.simulateManualWakeTrigger()
    }

    fun setConfirmSensitiveActions(enabled: Boolean) {
        keyStore.setConfirmSensitiveActions(enabled)
    }

    fun setPreferredLanguage(language: AppLanguage) {
        keyStore.setPreferredLanguage(language)
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        keyStore.setFloatingBubbleEnabled(enabled)
        if (enabled) startBackgroundService()
    }

    fun setTtsRepliesEnabled(enabled: Boolean) {
        keyStore.setTtsRepliesEnabled(enabled)
    }

    fun setTtsTierMode(mode: com.example.core.security.TtsTierMode) {
        keyStore.setTtsTierMode(mode)
    }

    fun setTtsVoiceName(voiceName: String) {
        keyStore.setTtsVoiceName(voiceName)
    }

    fun setTtsSpeed(speed: Float) {
        keyStore.setTtsSpeed(speed)
    }

    fun setTtsVolume(volume: Float) {
        keyStore.setTtsVolume(volume)
    }

    fun previewTtsVoice() {
        ttsManager.previewVoice(settings.value.preferredLanguage.name.lowercase())
    }

    fun completeOnboarding() {
        uiPreferencesStore.setOnboardingStep(0)
        keyStore.setOnboardingCompleted(true)
        startBackgroundService()
    }

    fun reopenOnboarding() {
        uiPreferencesStore.setOnboardingStep(0)
        keyStore.setOnboardingCompleted(false)
    }

    fun setOnboardingStep(step: Int) {
        uiPreferencesStore.setOnboardingStep(step)
    }

    fun clearChat() {
        viewModelScope.launch { historyRepository.clearChat() }
    }

    fun clearHistory() {
        uiPreferencesStore.clearDismissedTasks()
        viewModelScope.launch { historyRepository.clearTaskHistory() }
    }

    fun clearDismissedActionLogs() {
        uiPreferencesStore.clearDismissedLogs()
    }

    // ---- UI-only preferences & controls ----
    fun setThemeMode(mode: BabyThemeMode) = uiPreferencesStore.setThemeMode(mode)
    fun setDynamicColorEnabled(enabled: Boolean) = uiPreferencesStore.setDynamicColorEnabled(enabled)
    fun setListeningSoundCuesEnabled(enabled: Boolean) = uiPreferencesStore.setListeningSoundCuesEnabled(enabled)
    fun dismissApiKeyBanner() = uiPreferencesStore.setApiKeyBannerDismissed(true)
    fun dismissRateLimitBanner() { _rateLimitDismissed.value = true }
    fun toggleQuickCommandPin(id: String) = uiPreferencesStore.toggleQuickCommandPin(id)
    fun updateQuickCommandPhrase(id: String, phrase: String) = uiPreferencesStore.updateQuickCommandPhrase(id, phrase)
    fun addQuickCommand(phrase: String) = uiPreferencesStore.addQuickCommand(phrase)
    fun dismissTaskRun(id: Long) = uiPreferencesStore.dismissTaskRun(id)
    fun restoreTaskRun(id: Long) = uiPreferencesStore.restoreTaskRun(id)
    fun dismissActionLog(id: Long) = uiPreferencesStore.dismissActionLog(id)
    fun restoreActionLog(id: Long) = uiPreferencesStore.restoreActionLog(id)
    fun setRoutineIcon(triggerKey: String, iconKey: String) = uiPreferencesStore.setRoutineIcon(triggerKey, iconKey)

    fun clearLatencyRecords() = latencyTracker.clear()

    fun refreshInstalledAppsIndex() {
        viewModelScope.launch(Dispatchers.IO) {
            appResolver.invalidateIndex()
            _installedApps.value = appResolver.getInstalledAppsSnapshot(forceRefresh = true)
        }
    }

    fun saveAppAlias(alias: String, packageName: String, appLabel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appResolver.saveAlias(alias, packageName, appLabel)
        }
    }

    fun deleteAppAlias(alias: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appResolver.deleteAlias(alias)
        }
    }

    fun saveSearchRecipeJson(appKey: String, rawJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = searchPlayEngine.saveRecipeJson(appKey, rawJson)
            _recipeTestOutput.value = res.fold(
                onSuccess = { "Recipe '$appKey' saved." },
                onFailure = { "Invalid JSON: ${it.message}" }
            )
        }
    }

    fun resetSearchRecipe(appKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            searchPlayEngine.resetRecipeToDefault(appKey)
            _recipeTestOutput.value = "Recipe '$appKey' reset to default."
        }
    }

    fun runSearchPlayTest(command: String) {
        val intent = SearchPlayEngine.extractSearchPlayIntent(command)
        if (intent == null) {
            _recipeTestOutput.value = "Submitting \"$command\" to Baby orchestrator..."
            sendTextCommand(command)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val res = searchPlayEngine.executeSearchAndPlay(intent.appKey, intent.query, intent.autoPlay)
            _recipeTestOutput.value = res.output
        }
    }
}
