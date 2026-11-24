package com.example

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.security.SecureKeyStore
import com.example.features.routines.RoutineManager
import com.example.features.safety.SafetyManager
import com.example.features.widget.BabyWidgetProvider
import com.example.service.BabyQuickSettingsTileService
import com.example.ui.components.FullScreenVoiceOverlay
import com.example.ui.navigation.ActionsRoute
import com.example.ui.navigation.ChatRoute
import com.example.ui.navigation.HistoryRoute
import com.example.ui.navigation.MemoryRoute
import com.example.ui.navigation.MoreRoute
import com.example.ui.navigation.OnboardingRoute
import com.example.ui.navigation.PermissionsRoute
import com.example.ui.navigation.RemindersRoute
import com.example.ui.navigation.RoutinesRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.screens.ActionsScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.MoreScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.RemindersScreen
import com.example.ui.screens.RoutinesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.BabyMotionTokens
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.rememberReducedMotionEnabled
import com.example.ui.viewmodel.BabyExtrasViewModel
import com.example.ui.viewmodel.BabyViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private data class TopNavItem<T : Any>(
    val route: T,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var safetyManager: SafetyManager

    private val viewModel: BabyViewModel by viewModels()
    private val extrasViewModel: BabyExtrasViewModel by viewModels()
    private var lastVolumeDownMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLaunchIntent(intent)

        setContent {
            val uiPrefs by viewModel.uiPrefs.collectAsStateWithLifecycle()
            BabyTheme(
                themeMode = uiPrefs.themeMode,
                dynamicColor = uiPrefs.dynamicColorEnabled
            ) {
                BabyAppRoot(
                    viewModel = viewModel,
                    extrasViewModel = extrasViewModel
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        safetyManager.attachActivity(this)
    }

    override fun onPause() {
        safetyManager.attachActivity(null)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event?.repeatCount == 0) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeDownMs <= 550L && viewModel.agentState.value.isRunning) {
                lastVolumeDownMs = 0L
                viewModel.stopCurrentTask("Volume-down double-press shortcut")
                return true
            }
            lastVolumeDownMs = now
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val startVoiceBool = intent?.getBooleanExtra(BabyQuickSettingsTileService.EXTRA_START_VOICE, false) == true
        val startVoiceStr = intent?.getStringExtra(BabyQuickSettingsTileService.EXTRA_START_VOICE) == "true"
        if (startVoiceBool || startVoiceStr) {
            intent.removeExtra(BabyQuickSettingsTileService.EXTRA_START_VOICE)
            viewModel.toggleVoiceRecording()
        }
        val widgetCmd = intent?.getStringExtra(BabyWidgetProvider.EXTRA_WIDGET_COMMAND)
        if (!widgetCmd.isNullOrBlank()) {
            intent.removeExtra(BabyWidgetProvider.EXTRA_WIDGET_COMMAND)
            viewModel.sendTextCommand(widgetCmd)
        }
        if (intent?.getBooleanExtra(BabyWidgetProvider.EXTRA_EMERGENCY_TRIGGER, false) == true) {
            intent.removeExtra(BabyWidgetProvider.EXTRA_EMERGENCY_TRIGGER)
            extrasViewModel.triggerEmergencyNow()
        }
    }
}

@Composable
fun BabyAppRoot(
    viewModel: BabyViewModel,
    extrasViewModel: BabyExtrasViewModel
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiPrefs by viewModel.uiPrefs.collectAsStateWithLifecycle()
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val taskRuns by viewModel.taskRuns.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val micAmplitude by viewModel.micAmplitude.collectAsStateWithLifecycle()
    val isWakeListening by viewModel.isWakeListening.collectAsStateWithLifecycle()
    val isTtsSpeaking by viewModel.isTtsSpeaking.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
    val isRateLimited by viewModel.isRateLimited.collectAsStateWithLifecycle()
    val headerStatus by viewModel.headerStatus.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val voiceOverlayOpen by viewModel.voiceOverlayOpen.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val groqKeyTest by viewModel.groqKeyTest.collectAsStateWithLifecycle()
    val geminiKeyTest by viewModel.geminiKeyTest.collectAsStateWithLifecycle()

    val routines by extrasViewModel.routines.collectAsStateWithLifecycle()
    val memories by extrasViewModel.memories.collectAsStateWithLifecycle()
    val actionLogTotalCount by extrasViewModel.actionLogTotalCount.collectAsStateWithLifecycle()
    val availableActionApps by extrasViewModel.availableActionApps.collectAsStateWithLifecycle()
    val actionLogFilter by extrasViewModel.actionLogFilter.collectAsStateWithLifecycle()
    val reminders by extrasViewModel.reminders.collectAsStateWithLifecycle()
    val savedPlaces by extrasViewModel.savedPlaces.collectAsStateWithLifecycle()
    val macros by extrasViewModel.macros.collectAsStateWithLifecycle()
    val isRecordingMacro by extrasViewModel.isRecordingMacro.collectAsStateWithLifecycle()
    val recordedSteps by extrasViewModel.recordedSteps.collectAsStateWithLifecycle()
    val recordingWarning by extrasViewModel.recordingWarning.collectAsStateWithLifecycle()
    val autoReplyRules by extrasViewModel.autoReplyRules.collectAsStateWithLifecycle()
    val allowedApps by extrasViewModel.allowedApps.collectAsStateWithLifecycle()
    val emergencyContacts by extrasViewModel.emergencyContacts.collectAsStateWithLifecycle()
    val emergencyCountdown by extrasViewModel.emergencyCountdown.collectAsStateWithLifecycle()
    val statusToast by extrasViewModel.statusToast.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusToast) {
        statusToast?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            extrasViewModel.clearStatusToast()
        }
    }

    LaunchedEffect(settings.onboardingCompleted) {
        if (settings.onboardingCompleted && permissions.coreReady) {
            viewModel.startBackgroundService()
        }
    }

    if (!settings.onboardingCompleted) {
        OnboardingScreen(
            settings = settings,
            permissions = permissions,
            oemGuide = viewModel.oemGuide,
            permissionHelper = viewModel.permissionHelper,
            initialStep = uiPrefs.onboardingStep,
            groqKeyTest = groqKeyTest,
            geminiKeyTest = geminiKeyTest,
            onStepChange = viewModel::setOnboardingStep,
            onRefreshPermissions = viewModel::refreshPermissions,
            onSaveKeys = viewModel::saveApiKeys,
            onTestGroqKey = viewModel::testGroqKey,
            onTestGeminiKey = viewModel::testGeminiKey,
            onWakeWordToggle = viewModel::setWakeWordEnabled,
            onLanguageChange = viewModel::setPreferredLanguage,
            onPersonalityChange = extrasViewModel::setPersonality,
            onTtsToggle = viewModel::setTtsRepliesEnabled,
            onTrySampleCommand = viewModel::sendTextCommand,
            onFinishOnboarding = viewModel::completeOnboarding
        )
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val reducedMotion = rememberReducedMotionEnabled()

    val navItems = listOf(
        TopNavItem(ChatRoute, R.string.nav_chat, Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, "nav_chat_tab"),
        TopNavItem(ActionsRoute, R.string.nav_actions, Icons.Filled.Bolt, Icons.Outlined.Bolt, "nav_actions_tab"),
        TopNavItem(HistoryRoute, R.string.nav_history, Icons.Filled.History, Icons.Outlined.History, "nav_history_tab"),
        TopNavItem(MoreRoute, R.string.nav_more, Icons.Filled.GridView, Icons.Outlined.GridView, "nav_more_tab")
    )

    val isTopLevelDestination = currentDest == null ||
        currentDest.hasRoute<ChatRoute>() ||
        currentDest.hasRoute<ActionsRoute>() ||
        currentDest.hasRoute<HistoryRoute>() ||
        currentDest.hasRoute<MoreRoute>()

    fun navigateTop(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail && isTopLevelDestination) {
                    NavigationBar {
                        navItems.forEach { item ->
                            val selected = currentDest?.hasRoute(item.route::class) == true
                            val label = stringResource(item.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTop(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail && isTopLevelDestination) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        navItems.forEach { item ->
                            val selected = currentDest?.hasRoute(item.route::class) == true
                            val label = stringResource(item.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTop(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ChatRoute,
                    enterTransition = {
                        fadeIn(BabyMotionTokens.standardTween(reducedMotion)) +
                            slideInHorizontally(BabyMotionTokens.slideOffsetTween(reducedMotion)) { it / 12 }
                    },
                    exitTransition = {
                        fadeOut(BabyMotionTokens.fastTween(reducedMotion))
                    },
                    popEnterTransition = {
                        fadeIn(BabyMotionTokens.standardTween(reducedMotion))
                    },
                    popExitTransition = {
                        fadeOut(BabyMotionTokens.fastTween(reducedMotion)) +
                            slideOutHorizontally(BabyMotionTokens.slideOffsetTween(reducedMotion)) { it / 10 }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    composable<OnboardingRoute> {
                        OnboardingScreen(
                            settings = settings,
                            permissions = permissions,
                            oemGuide = viewModel.oemGuide,
                            permissionHelper = viewModel.permissionHelper,
                            initialStep = uiPrefs.onboardingStep,
                            groqKeyTest = groqKeyTest,
                            geminiKeyTest = geminiKeyTest,
                            onStepChange = viewModel::setOnboardingStep,
                            onRefreshPermissions = viewModel::refreshPermissions,
                            onSaveKeys = viewModel::saveApiKeys,
                            onTestGroqKey = viewModel::testGroqKey,
                            onTestGeminiKey = viewModel::testGeminiKey,
                            onWakeWordToggle = viewModel::setWakeWordEnabled,
                            onLanguageChange = viewModel::setPreferredLanguage,
                            onPersonalityChange = extrasViewModel::setPersonality,
                            onTtsToggle = viewModel::setTtsRepliesEnabled,
                            onTrySampleCommand = viewModel::sendTextCommand,
                            onFinishOnboarding = viewModel::completeOnboarding
                        )
                    }
                    composable<ChatRoute> {
                        ChatScreen(
                            messages = messages,
                            agentState = agentState,
                            settings = settings,
                            uiPrefs = uiPrefs,
                            permissions = permissions,
                            headerStatus = headerStatus,
                            isRecording = isRecording,
                            micAmplitude = micAmplitude,
                            isWakeListening = isWakeListening,
                            isTtsSpeaking = isTtsSpeaking,
                            isOffline = isOffline,
                            isRateLimited = isRateLimited,
                            onSendCommand = viewModel::sendTextCommand,
                            onToggleVoice = viewModel::toggleVoiceRecording,
                            onStartHoldToTalk = viewModel::startHoldToTalk,
                            onStopHoldToTalk = viewModel::stopHoldToTalk,
                            onOpenVoiceOverlay = viewModel::openVoiceOverlay,
                            onStopTask = { viewModel.stopCurrentTask("In-app STOP button") },
                            onApproveConfirmation = viewModel::approveConfirmation,
                            onClearChat = viewModel::clearChat,
                            onNavigateToSettings = { navController.navigate(SettingsRoute("keys")) },
                            onOpenPermissions = { navController.navigate(PermissionsRoute) },
                            onRegenerateMessage = viewModel::regenerateFromAssistantMessage,
                            onSpeakMessage = viewModel::speakMessage,
                            onDismissApiKeyBanner = viewModel::dismissApiKeyBanner,
                            onDismissRateLimitBanner = viewModel::dismissRateLimitBanner,
                            onDismissError = viewModel::clearAgentError,
                            onToggleQuickCommandPin = viewModel::toggleQuickCommandPin,
                            onEditQuickCommandPhrase = viewModel::updateQuickCommandPhrase,
                            onWakeWordToggle = viewModel::setWakeWordEnabled,
                            onAutoReplyToggle = { enabled ->
                                extrasViewModel.setFeatureToggle(SecureKeyStore.KEY_AUTO_REPLY_ENABLED, enabled)
                            },
                            onAutoReplyModeChange = extrasViewModel::setAutoReplyMode,
                            onDataSaverToggle = { enabled ->
                                extrasViewModel.setFeatureToggle(SecureKeyStore.KEY_DATA_SAVER, enabled)
                            },
                            onTtsToggle = viewModel::setTtsRepliesEnabled,
                            onSoundCuesToggle = viewModel::setListeningSoundCuesEnabled
                        )
                    }
                    composable<ActionsRoute> {
                        val routineOnlyCount = routines.count { it.scheduleType != RoutineManager.SCHEDULE_TYPE_COMMAND }
                        val commandsOnlyCount = routines.count { it.scheduleType == RoutineManager.SCHEDULE_TYPE_COMMAND }
                        val activeRemindersCount = reminders.count { !it.isCompleted }
                        ActionsScreen(
                            settings = settings,
                            routinesCount = routineOnlyCount,
                            commandsCount = commandsOnlyCount,
                            macrosCount = macros.size,
                            activeRemindersCount = activeRemindersCount,
                            memoriesCount = memories.size,
                            isRefreshing = isRefreshing,
                            onRefresh = viewModel::refreshUiSnapshot,
                            onToggleFeature = extrasViewModel::setFeatureToggle,
                            onAutoReplyToggle = { enabled ->
                                extrasViewModel.setFeatureToggle(SecureKeyStore.KEY_AUTO_REPLY_ENABLED, enabled)
                            },
                            onOpenRoutinesTab = { tab -> navController.navigate(RoutinesRoute(initialTab = tab)) },
                            onOpenReminders = { navController.navigate(RemindersRoute) },
                            onOpenMemory = { navController.navigate(MemoryRoute) },
                            onOpenSettingsSection = { sec -> navController.navigate(SettingsRoute(initialSection = sec)) },
                            onRunNotifSummaryNow = {
                                viewModel.sendTextCommand("Notifications batao")
                                navigateTop(ChatRoute)
                            }
                        )
                    }
                    composable<HistoryRoute> {
                        HistoryScreen(
                            taskRuns = taskRuns,
                            pagedActionLogsFlow = extrasViewModel.pagedActionLogs,
                            totalActionLogCount = actionLogTotalCount,
                            availableActionApps = availableActionApps,
                            externalActionFilter = actionLogFilter,
                            onActionFilterChange = extrasViewModel::updateActionLogFilter,
                            dismissedTaskIds = uiPrefs.dismissedTaskRunIds,
                            dismissedLogIds = uiPrefs.dismissedActionLogIds,
                            isRefreshing = isRefreshing,
                            onRefresh = viewModel::refreshUiSnapshot,
                            onDismissTaskRun = viewModel::dismissTaskRun,
                            onRestoreTaskRun = viewModel::restoreTaskRun,
                            onDismissActionLog = viewModel::dismissActionLog,
                            onRestoreActionLog = viewModel::restoreActionLog,
                            onClearHistory = viewModel::clearHistory,
                            onClearActionLogs = {
                                viewModel.clearDismissedActionLogs()
                                extrasViewModel.clearActionLog()
                            },
                            onExportActionLogs = extrasViewModel::exportActionLog,
                            onExportActionLogsToUri = extrasViewModel::exportActionLogToUri,
                            onOpenChatForCommand = { navigateTop(ChatRoute) }
                        )
                    }
                    composable<MoreRoute> {
                        MoreScreen(
                            settings = settings,
                            permissions = permissions,
                            emergencyContactsCount = emergencyContacts.size,
                            actionLogsCount = actionLogTotalCount,
                            memoriesCount = memories.size,
                            onOpenSettings = { sec -> navController.navigate(SettingsRoute(initialSection = sec)) },
                            onOpenPermissions = { navController.navigate(PermissionsRoute) },
                            onOpenRoutinesTab = { tab -> navController.navigate(RoutinesRoute(initialTab = tab)) },
                            onOpenReminders = { navController.navigate(RemindersRoute) },
                            onOpenMemory = { navController.navigate(MemoryRoute) },
                            onExportActionLogsShare = extrasViewModel::exportActionLog,
                            onExportActionLogsToUri = extrasViewModel::exportActionLogToUri,
                            onReopenOnboarding = viewModel::reopenOnboarding
                        )
                    }
                    composable<RoutinesRoute> { entry ->
                        val args = entry.toRoute<RoutinesRoute>()
                        RoutinesScreen(
                            settings = settings,
                            permissions = permissions,
                            allRoutines = routines,
                            macros = macros,
                            isRecordingMacro = isRecordingMacro,
                            recordedSteps = recordedSteps,
                            recordingWarning = recordingWarning,
                            initialTab = args.initialTab,
                            routineIcons = uiPrefs.routineIcons,
                            onSelectRoutineIcon = viewModel::setRoutineIcon,
                            onBack = { navController.popBackStack() },
                            onRunRoutine = extrasViewModel::runRoutine,
                            onToggleRoutine = extrasViewModel::toggleRoutine,
                            onCreateRoutine = extrasViewModel::createRoutine,
                            onUpdateRoutine = extrasViewModel::updateRoutine,
                            onDeleteRoutine = extrasViewModel::deleteRoutine,
                            onCreateCustomCommand = extrasViewModel::createCustomCommand,
                            onUpdateCustomCommand = extrasViewModel::updateCustomCommand,
                            onRunCustomCommand = extrasViewModel::runCustomCommand,
                            onStartMacroRecording = extrasViewModel::startMacroRecording,
                            onStopAndSaveMacro = extrasViewModel::stopAndSaveMacro,
                            onCancelMacroRecording = extrasViewModel::cancelMacroRecording,
                            onToggleMacro = extrasViewModel::toggleMacro,
                            onRunMacro = extrasViewModel::runMacro,
                            onDeleteMacro = extrasViewModel::deleteMacro
                        )
                    }
                    composable<RemindersRoute> {
                        RemindersScreen(
                            settings = settings,
                            permissions = permissions,
                            reminders = reminders,
                            savedPlaces = savedPlaces,
                            onBack = { navController.popBackStack() },
                            onCreateTimeReminder = extrasViewModel::createTimeReminder,
                            onCreateLocationReminder = extrasViewModel::createLocationReminder,
                            onUpdateReminder = extrasViewModel::updateReminder,
                            onSnoozeReminder = extrasViewModel::snoozeReminder,
                            onDeleteReminder = extrasViewModel::deleteReminder,
                            onSavePlace = extrasViewModel::savePlace,
                            onSaveCurrentGpsAsPlace = extrasViewModel::saveCurrentGpsAsPlace,
                            onDeleteSavedPlace = extrasViewModel::deleteSavedPlace,
                            onGeocodeAddress = extrasViewModel::geocodeAddress,
                            onFetchCurrentGps = extrasViewModel::getCurrentGpsLocation,
                            onOpenExactAlarmSettings = viewModel.permissionHelper::openExactAlarmSettings,
                            onRefreshPermissions = viewModel::refreshPermissions
                        )
                    }
                    composable<SettingsRoute> { entry ->
                        val args = entry.toRoute<SettingsRoute>()
                        SettingsScreen(
                            settings = settings,
                            permissions = permissions,
                            oemGuide = viewModel.oemGuide,
                            permissionHelper = viewModel.permissionHelper,
                            initialSection = args.initialSection,
                            uiPrefs = uiPrefs,
                            groqKeyTest = groqKeyTest,
                            geminiKeyTest = geminiKeyTest,
                            onBack = { navController.popBackStack() },
                            onRefreshPermissions = viewModel::refreshPermissions,
                            onSaveKeys = viewModel::saveApiKeys,
                            onTestGroqKey = viewModel::testGroqKey,
                            onTestGeminiKey = viewModel::testGeminiKey,
                            onClearKeys = viewModel::clearApiKeys,
                            onThemeModeChange = viewModel::setThemeMode,
                            onDynamicColorChange = viewModel::setDynamicColorEnabled,
                            onSoundCuesChange = viewModel::setListeningSoundCuesEnabled,
                            onGeminiModelChange = viewModel::updateGeminiModel,
                            onGroqModelChange = viewModel::updateGroqModel,
                            onPreferredBrainChange = viewModel::updatePreferredBrain,
                            onAutoFallbackChange = viewModel::setAutoFallback,
                            onWakeWordToggle = viewModel::setWakeWordEnabled,
                            onSensitivityChange = viewModel::setWakeWordSensitivity,
                            onLanguageChange = viewModel::setPreferredLanguage,
                            onConfirmSensitiveToggle = viewModel::setConfirmSensitiveActions,
                            onTtsRepliesToggle = viewModel::setTtsRepliesEnabled,
                            onFloatingBubbleToggle = viewModel::setFloatingBubbleEnabled,
                            onReopenOnboarding = viewModel::reopenOnboarding,
                            onOpenPermissions = { navController.navigate(PermissionsRoute) },
                            onClearHistory = viewModel::clearHistory,
                            onClearActionLogs = {
                                viewModel.clearDismissedActionLogs()
                                extrasViewModel.clearActionLog()
                            },
                            onWipeAllMemories = extrasViewModel::wipeAllMemories,
                            autoReplyRules = autoReplyRules,
                            allowedApps = allowedApps,
                            emergencyContacts = emergencyContacts,
                            onFeatureToggle = extrasViewModel::setFeatureToggle,
                            onRetentionDaysChange = extrasViewModel::setActionLogRetentionDays,
                            onAutoReplyModeChange = extrasViewModel::setAutoReplyMode,
                            onSaveAutoReplyTemplates = extrasViewModel::updateAutoReplyTemplates,
                            onAutoReplyScheduleChange = extrasViewModel::setAutoReplySchedule,
                            onUpsertAutoReplyRule = extrasViewModel::upsertAutoReplyRule,
                            onDeleteAutoReplyRule = extrasViewModel::deleteAutoReplyRule,
                            onPersonalityChange = extrasViewModel::setPersonality,
                            onSetVoicePin = extrasViewModel::setVoicePin,
                            onSaveEmergencyMessage = extrasViewModel::setEmergencyCustomMessage,
                            onAddAllowedApp = extrasViewModel::addAllowedApp,
                            onToggleAllowedApp = extrasViewModel::toggleAllowedApp,
                            onRemoveAllowedApp = extrasViewModel::removeAllowedApp,
                            onAddEmergencyContact = extrasViewModel::addEmergencyContact,
                            onDeleteEmergencyContact = extrasViewModel::deleteEmergencyContact,
                            onTriggerSos = extrasViewModel::triggerEmergencyNow,
                            onOpenMemory = { navController.navigate(MemoryRoute) }
                        )
                    }
                    composable<MemoryRoute> {
                        MemoryScreen(
                            memories = memories,
                            onBack = { navController.popBackStack() },
                            onSaveMemory = extrasViewModel::saveMemory,
                            onUpdateMemory = extrasViewModel::updateMemory,
                            onDeleteMemory = extrasViewModel::deleteMemory,
                            onWipeAll = extrasViewModel::wipeAllMemories
                        )
                    }
                    composable<PermissionsRoute> {
                        PermissionsScreen(
                            permissions = permissions,
                            oemGuide = viewModel.oemGuide,
                            permissionHelper = viewModel.permissionHelper,
                            isRefreshing = isRefreshing,
                            onRefresh = viewModel::refreshUiSnapshot,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        FullScreenVoiceOverlay(
            visible = voiceOverlayOpen,
            isRecording = isRecording,
            isSpeaking = isTtsSpeaking,
            micAmplitude = micAmplitude,
            agentState = agentState,
            transcriptOrReply = agentState.statusText.ifBlank {
                messages.lastOrNull()?.content.orEmpty()
            },
            isOffline = isOffline,
            onTapOrb = viewModel::interruptOrStopVoice,
            onStopTask = { viewModel.stopCurrentTask("Voice overlay STOP") },
            onDismiss = viewModel::closeVoiceOverlay
        )

        if (emergencyCountdown.active) {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(
                        "Emergency SOS in ${emergencyCountdown.secondsRemaining}s",
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = {
                    Text(
                        "Live location ke saath SOS SMS ${emergencyCountdown.contactCount} emergency contact(s) ko bheja jayega. Cancel button dabao ya \"Cancel\" / \"Ruko\" bolo."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = extrasViewModel::cancelEmergencyCountdown,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("cancel_emergency_sos_btn")
                    ) {
                        Text("Cancel SOS")
                    }
                }
            )
        }
    }
}
