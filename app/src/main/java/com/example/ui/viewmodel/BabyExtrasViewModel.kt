package com.example.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.security.AutoReplyMode
import com.example.core.security.BabyPersonality
import com.example.core.security.CallAssistantMode
import com.example.core.security.SecureKeyStore
import com.example.core.security.UnknownCallerPolicy
import com.example.data.local.ActionLogEntity
import com.example.data.local.AllowedAppEntity
import com.example.data.local.AutoReplyContactRuleEntity
import com.example.data.local.CallScreeningLogEntity
import com.example.data.local.EmergencyContactEntity
import com.example.data.local.MacroEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.ReminderEntity
import com.example.data.local.RoutineEntity
import com.example.data.local.SavedPlaceEntity
import com.example.domain.ai.AgentOrchestrator
import com.example.features.actionlog.ActionLogRepository
import com.example.features.autoreply.AutoReplyManager
import com.example.features.calls.CallAssistantManager
import com.example.features.macros.MacroManager
import com.example.features.macros.MacroStep
import com.example.features.memory.MemoryManager
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.reminders.ReminderManager
import com.example.features.routines.RoutineManager
import com.example.features.routines.RoutineStep
import com.example.features.safety.EmergencyCountdownState
import com.example.features.safety.SafetyManager
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.ui.state.ActionLogFilterState
import com.example.ui.state.ActionLogPagingSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BabyExtrasViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val keyStore: SecureKeyStore,
    private val orchestrator: AgentOrchestrator,
    private val routineManager: RoutineManager,
    private val memoryManager: MemoryManager,
    private val actionLogRepository: ActionLogRepository,
    private val reminderManager: ReminderManager,
    private val macroManager: MacroManager,
    private val autoReplyManager: AutoReplyManager,
    private val notificationSummaryManager: NotificationSummaryManager,
    private val safetyManager: SafetyManager,
    private val callAssistantManager: CallAssistantManager
) : ViewModel() {

    init {
        viewModelScope.launch {
            routineManager.ensureBuiltInRoutinesSeeded()
        }
    }

    val routines: StateFlow<List<RoutineEntity>> = routineManager.routines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryEntity>> = memoryManager.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val actionLogs: StateFlow<List<ActionLogEntity>> = actionLogRepository.logs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val actionLogTotalCount: StateFlow<Int> = actionLogRepository.totalCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val availableActionApps: StateFlow<List<String>> = actionLogRepository.availableApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _actionLogFilter = MutableStateFlow(ActionLogFilterState())
    val actionLogFilter: StateFlow<ActionLogFilterState> = _actionLogFilter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedActionLogs: Flow<PagingData<ActionLogEntity>> = combine(
        _actionLogFilter,
        actionLogTotalCount
    ) { filter, _ -> filter }
        .flatMapLatest { filter ->
            Pager(
                config = PagingConfig(pageSize = 30, enablePlaceholders = false, initialLoadSize = 60),
                pagingSourceFactory = { ActionLogPagingSource(actionLogRepository, filter) }
            ).flow
        }
        .cachedIn(viewModelScope)

    fun updateActionLogFilter(filter: ActionLogFilterState) {
        _actionLogFilter.value = filter
    }

    val reminders: StateFlow<List<ReminderEntity>> = reminderManager.reminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedPlaces: StateFlow<List<SavedPlaceEntity>> = reminderManager.savedPlaces
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val macros: StateFlow<List<MacroEntity>> = macroManager.macros
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isRecordingMacro: StateFlow<Boolean> = macroManager.isRecordingMacro
    val recordedSteps: StateFlow<List<MacroStep>> = macroManager.recordedSteps
    val recordingWarning: StateFlow<String?> = macroManager.recordingWarning

    val autoReplyRules: StateFlow<List<AutoReplyContactRuleEntity>> = autoReplyManager.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allowedApps: StateFlow<List<AllowedAppEntity>> = safetyManager.allowedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val emergencyContacts: StateFlow<List<EmergencyContactEntity>> = safetyManager.emergencyContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val emergencyCountdown: StateFlow<EmergencyCountdownState> = safetyManager.emergencyCountdown

    val callScreeningLogs: StateFlow<List<CallScreeningLogEntity>> = callAssistantManager.screeningLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    fun clearStatusToast() {
        _statusToast.value = null
    }

    // ---- Routines ----
    fun runRoutine(routine: RoutineEntity) {
        orchestrator.submitUserCommand(routine.triggerPhrase, source = "routine_ui")
    }

    fun toggleRoutine(routine: RoutineEntity, enabled: Boolean) {
        viewModelScope.launch { routineManager.toggleRoutineEnabled(routine, enabled) }
    }

    fun createRoutine(
        name: String,
        triggerPhrase: String,
        steps: List<RoutineStep>,
        scheduleType: String,
        scheduleTime: String
    ) {
        viewModelScope.launch {
            routineManager.saveRoutine(name, triggerPhrase, steps, scheduleType, scheduleTime)
            _statusToast.value = "Routine '$name' saved"
        }
    }

    fun updateRoutine(
        id: Long,
        name: String,
        triggerPhrase: String,
        steps: List<RoutineStep>,
        scheduleType: String,
        scheduleTime: String
    ) {
        viewModelScope.launch {
            val allKept = routineManager.updateRoutine(id, name, triggerPhrase, steps, scheduleType, scheduleTime)
            _statusToast.value = if (allKept) "Routine '$name' updated" else "Routine updated (sensitive steps blocked)"
        }
    }

    fun deleteRoutine(id: Long) {
        viewModelScope.launch { routineManager.deleteRoutine(id) }
    }

    // ---- Custom Commands (reuses RoutineManager engine) ----
    fun createCustomCommand(name: String, triggerPhrase: String, steps: List<RoutineStep>) {
        viewModelScope.launch {
            routineManager.saveCustomCommand(name, triggerPhrase, steps)
            _statusToast.value = "Custom command '$triggerPhrase' saved"
        }
    }

    fun updateCustomCommand(id: Long, name: String, triggerPhrase: String, steps: List<RoutineStep>) {
        viewModelScope.launch {
            routineManager.updateCustomCommand(id, name, triggerPhrase, steps)
            _statusToast.value = "Custom command '$name' updated"
        }
    }

    fun runCustomCommand(cmd: RoutineEntity) {
        orchestrator.submitUserCommand(cmd.triggerPhrase, source = "command_ui")
    }

    // ---- Macros ----
    fun startMacroRecording() {
        macroManager.startRecording()
        _statusToast.value = "Macro recording started! Do actions in any app, then Stop & Save."
    }

    fun stopAndSaveMacro(name: String, triggerPhrase: String) {
        viewModelScope.launch {
            macroManager.stopAndSaveRecording(name, triggerPhrase)
            _statusToast.value = "Macro '$name' saved!"
        }
    }

    fun cancelMacroRecording() {
        macroManager.cancelRecording()
    }

    fun toggleMacro(macro: MacroEntity, enabled: Boolean) {
        viewModelScope.launch { macroManager.toggleMacroEnabled(macro, enabled) }
    }

    fun runMacro(macro: MacroEntity) {
        orchestrator.submitUserCommand("${macro.triggerPhrase} chalao", source = "macro_ui")
    }

    fun deleteMacro(id: Long) {
        viewModelScope.launch { macroManager.deleteMacro(id) }
    }

    // ---- Reminders & Saved Places ----
    fun createTimeReminder(title: String, timeExpr: String) {
        viewModelScope.launch {
            val msg = reminderManager.createNaturalReminder(title, timeExpr, null)
            _statusToast.value = msg
        }
    }

    fun createLocationReminder(title: String, placeLabel: String, lat: Double?, lon: Double?) {
        viewModelScope.launch {
            val msg = reminderManager.createLocationReminder(title, placeLabel, lat, lon)
            _statusToast.value = msg
        }
    }

    fun updateReminder(
        id: Long,
        title: String,
        triggerType: String,
        timeExpr: String?,
        placeLabel: String?,
        lat: Double?,
        lon: Double?,
        radiusMeters: Float = 200f
    ) {
        viewModelScope.launch {
            _statusToast.value = reminderManager.updateReminder(
                id, title, triggerType, timeExpr, placeLabel, lat, lon, radiusMeters
            )
        }
    }

    fun snoozeReminder(id: Long, minutes: Int) {
        viewModelScope.launch {
            _statusToast.value = reminderManager.snoozeReminder(id, minutes)
        }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch {
            reminderManager.deleteReminder(id)
            _statusToast.value = "Reminder deleted"
        }
    }

    fun savePlace(label: String, lat: Double, lon: Double, radiusMeters: Float, addressHint: String) {
        viewModelScope.launch {
            _statusToast.value = reminderManager.savePlace(label, lat, lon, radiusMeters, addressHint)
        }
    }

    fun saveCurrentGpsAsPlace(label: String) {
        viewModelScope.launch {
            _statusToast.value = reminderManager.saveCurrentLocationAsPlace(label)
        }
    }

    fun deleteSavedPlace(id: Long) {
        viewModelScope.launch {
            reminderManager.deleteSavedPlace(id)
            _statusToast.value = "Saved place removed"
        }
    }

    suspend fun geocodeAddress(query: String): Pair<Double, Double>? =
        reminderManager.geocodeAddress(query)

    suspend fun getCurrentGpsLocation(): Pair<Double, Double>? =
        reminderManager.getCurrentOrLastLocation()

    // ---- Memory ----
    fun saveMemory(key: String, value: String, category: String) {
        viewModelScope.launch {
            _statusToast.value = memoryManager.saveMemory(key, value, category)
        }
    }

    fun updateMemory(entity: MemoryEntity) {
        viewModelScope.launch { memoryManager.updateMemory(entity) }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { memoryManager.deleteMemory(id) }
    }

    fun wipeAllMemories() {
        viewModelScope.launch {
            _statusToast.value = memoryManager.wipeAll()
        }
    }

    // ---- Action Log ----
    fun clearActionLog() {
        viewModelScope.launch { actionLogRepository.clearAll() }
    }

    fun exportActionLog() {
        viewModelScope.launch {
            val text = actionLogRepository.exportAsText()
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Baby Action Log", text))
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Baby Assistant Action Log")
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                appContext.startActivity(
                    Intent.createChooser(shareIntent, "Export Action Log").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            _statusToast.value = "Action log copied to clipboard & share opened"
        }
    }

    fun exportActionLogToUri(uri: Uri) {
        viewModelScope.launch {
            val text = actionLogRepository.exportAsText()
            val saved = runCatching {
                appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    true
                } ?: false
            }.getOrDefault(false)
            _statusToast.value = if (saved) "Action log exported to .txt file!" else "Could not write .txt export file"
        }
    }

    fun setActionLogRetentionDays(days: Int) {
        keyStore.setActionLogRetentionDays(days)
        viewModelScope.launch { actionLogRepository.pruneExpired() }
        val label = if (days < 0) "Forever" else "$days days"
        _statusToast.value = "Action Log retention set to $label"
    }

    // ---- Feature Toggles & Configs ----
    fun setFeatureToggle(prefKey: String, enabled: Boolean) {
        keyStore.setBooleanPref(prefKey, enabled)
        if (prefKey == SecureKeyStore.KEY_SUMMARY_MORNING ||
            prefKey == SecureKeyStore.KEY_SUMMARY_EVENING ||
            prefKey == SecureKeyStore.KEY_NOTIFICATION_SUMMARY_ENABLED
        ) {
            notificationSummaryManager.syncScheduledSummaries()
        }
        if (prefKey == SecureKeyStore.KEY_AUTO_REPLY_ENABLED || prefKey == SecureKeyStore.KEY_AUTO_REPLY_IGNORE_GROUPS) {
            com.example.service.BabyForegroundService.refreshNotificationIfRunning(appContext)
        }
    }

    fun setAutoReplyMode(mode: AutoReplyMode) {
        keyStore.setAutoReplyMode(mode)
        com.example.service.BabyForegroundService.refreshNotificationIfRunning(appContext)
    }

    fun updateAutoReplyTemplates(busy: String, driving: String, sleeping: String, custom: String) {
        keyStore.updateAutoReplyTemplates(busy, driving, sleeping, custom)
        _statusToast.value = "Auto-reply templates saved"
    }

    fun setAutoReplySchedule(enabled: Boolean, startH: Int, startM: Int, endH: Int, endM: Int) {
        keyStore.setAutoReplySchedule(enabled, startH, startM, endH, endM)
        _statusToast.value = if (enabled) {
            "Auto-reply schedule set: %02d:%02d - %02d:%02d".format(startH, startM, endH, endM)
        } else {
            "Auto-reply schedule turned OFF"
        }
    }

    fun upsertAutoReplyRule(displayName: String, ruleType: String) {
        viewModelScope.launch { autoReplyManager.upsertContactRule(displayName, ruleType) }
    }

    fun deleteAutoReplyRule(contactKey: String) {
        viewModelScope.launch { autoReplyManager.deleteRule(contactKey) }
    }

    fun setVoicePin(pin: String) {
        keyStore.setVoicePinCode(pin)
        _statusToast.value = if (pin.isBlank()) "Voice PIN cleared" else "Voice PIN hashed & saved in Keystore"
    }

    fun setEmergencyCustomMessage(message: String) {
        keyStore.setEmergencyCustomMessage(message)
        _statusToast.value = "Emergency SOS message saved"
    }

    fun setPersonality(personality: BabyPersonality) {
        keyStore.setPersonality(personality)
    }

    fun setEmotionIntensity(intensity: com.example.core.security.EmotionIntensity) {
        keyStore.setEmotionIntensity(intensity)
    }

    fun addAllowedApp(appNameOrPackage: String) {
        val clean = appNameOrPackage.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            safetyManager.setAppAllowed(packageName = clean.lowercase(), appLabel = clean, allowed = true)
        }
    }

    fun toggleAllowedApp(entity: AllowedAppEntity, allowed: Boolean) {
        viewModelScope.launch {
            safetyManager.setAppAllowed(entity.packageName, entity.appLabel, allowed)
        }
    }

    fun removeAllowedApp(packageName: String) {
        viewModelScope.launch { safetyManager.removeAllowedApp(packageName) }
    }

    fun addEmergencyContact(name: String, phone: String) {
        viewModelScope.launch {
            val added = safetyManager.addEmergencyContact(name, phone)
            if (!added) {
                _statusToast.value = "Maximum ${SafetyManager.MAX_EMERGENCY_CONTACTS} emergency contacts allowed."
            }
        }
    }

    fun deleteEmergencyContact(id: Long) {
        viewModelScope.launch { safetyManager.deleteEmergencyContact(id) }
    }

    fun triggerEmergencyNow() {
        orchestrator.submitUserCommand("Baby help", source = "sos_button")
    }

    fun cancelEmergencyCountdown() {
        safetyManager.cancelEmergencyCountdown("Cancelled from SOS screen button")
    }

    // ---- Call Assistant ----
    fun setCallAssistantMode(mode: CallAssistantMode) {
        keyStore.setCallAssistantMode(mode)
        _statusToast.value = "Call Assistant mode: ${mode.label}"
    }

    fun setUnknownCallerPolicy(policy: UnknownCallerPolicy) {
        keyStore.setUnknownCallerPolicy(policy)
        _statusToast.value = "Unknown caller policy: ${policy.label}"
    }

    fun setCallAssistantReplyTemplate(template: String) {
        keyStore.setCallAssistantReplyTemplate(template)
        _statusToast.value = "Call reply template saved"
    }

    fun clearCallScreeningLogs() {
        viewModelScope.launch {
            callAssistantManager.clearLogs()
            _statusToast.value = "Call screening logs cleared"
        }
    }
}
