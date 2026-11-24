package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.core.security.AiModelConfig
import com.example.core.security.BabySettings
import com.example.data.local.ActionLogEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.ReminderEntity
import com.example.data.local.TaskRunEntity
import com.example.domain.ai.AgentExecutionState
import com.example.domain.ai.ConfirmationRequest
import com.example.ui.screens.ActionsScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.MoreScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.RemindersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.state.BabyUiPreferences
import com.example.ui.state.ModelTestItem
import com.example.ui.state.ModelsTestUiState
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.viewmodel.BabyHeaderStatus
import com.example.util.OemAutostartGuide
import com.example.util.PermissionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BabyUiComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun testPermissions(allGranted: Boolean = true) = PermissionSnapshot(
        recordAudio = allGranted,
        postNotifications = allGranted,
        accessibility = allGranted,
        notificationListener = allGranted,
        overlayBubble = allGranted,
        batteryOptimizationIgnored = allGranted,
        callPhone = allGranted,
        sendSms = allGranted,
        readContacts = allGranted,
        writeSettings = allGranted,
        dndAccess = allGranted,
        camera = allGranted,
        readCalendar = allGranted,
        fineLocation = allGranted,
        exactAlarm = allGranted
    )

    @Test
    fun `onboarding 5-step flow navigates forward, backward, and skips cleanly`() {
        var finished = false
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                var step by remember { mutableIntStateOf(0) }
                OnboardingScreen(
                    settings = BabySettings(),
                    permissions = testPermissions(false),
                    oemGuide = OemAutostartGuide("Xiaomi", listOf("Allow Autostart"), emptyList()),
                    permissionHelper = null,
                    initialStep = step,
                    onStepChange = { step = it },
                    onRefreshPermissions = {},
                    onSaveKeys = { _, _ -> },
                    onFinishOnboarding = { finished = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Namaste! Main hoon Baby").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Add Your AI Brain Keys").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_groq_key_input").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Permissions Setup").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add Your AI Brain Keys").assertIsDisplayed()

        composeRule.onNodeWithText("Skip for now").performClick()
        composeRule.waitForIdle()
        assertTrue(finished)
    }

    @Test
    fun `chat screen greeting renders and header tap opens QuickStatusSheet`() {
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                ChatScreen(
                    messages = emptyList(),
                    agentState = AgentExecutionState(),
                    settings = BabySettings(geminiApiKey = "", groqApiKey = ""),
                    uiPrefs = BabyUiPreferences(apiKeyBannerDismissed = false),
                    permissions = testPermissions(true),
                    headerStatus = BabyHeaderStatus.NO_API_KEY,
                    isRecording = false,
                    micAmplitude = 0f,
                    isWakeListening = false,
                    onSendCommand = {},
                    onToggleVoice = {},
                    onStopTask = {},
                    onApproveConfirmation = { _, _ -> },
                    onClearChat = {},
                    onNavigateToSettings = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compact_greeting_card").assertIsDisplayed()
        composeRule.onNodeWithTag("missing_keys_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("chat_status_header").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_status_wake_switch").assertIsDisplayed()
    }

    @Test
    fun `chat confirmation card, quick command chip, and banner dismiss work`() {
        var sentCommand: String? = null
        var dismissedBanner = false
        var approvedResult: Boolean? = null

        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.LIGHT) {
                var msgs by remember { mutableStateOf(emptyList<ChatMessageEntity>()) }
                var bannerDismissed by remember { mutableStateOf(false) }
                var pendingReq by remember {
                    mutableStateOf<ConfirmationRequest?>(
                        ConfirmationRequest(
                            toolName = "send_whatsapp",
                            summary = "Send WhatsApp to Mummy",
                            argsPreview = "Say hello"
                        )
                    )
                }
                ChatScreen(
                    messages = msgs,
                    agentState = AgentExecutionState(
                        pendingConfirmation = pendingReq
                    ),
                    settings = BabySettings(geminiApiKey = "", groqApiKey = ""),
                    uiPrefs = BabyUiPreferences(apiKeyBannerDismissed = bannerDismissed),
                    permissions = testPermissions(true),
                    headerStatus = BabyHeaderStatus.NO_API_KEY,
                    isRecording = false,
                    micAmplitude = 0f,
                    isWakeListening = false,
                    onSendCommand = { cmd ->
                        sentCommand = cmd
                        msgs = msgs + ChatMessageEntity(id = 10L, role = "user", content = cmd)
                    },
                    onToggleVoice = {},
                    onStopTask = {},
                    onApproveConfirmation = { ok, _ ->
                        approvedResult = ok
                        pendingReq = null
                    },
                    onClearChat = {},
                    onNavigateToSettings = {},
                    onDismissApiKeyBanner = {
                        dismissedBanner = true
                        bannerDismissed = true
                    }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dismiss_keys_banner").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissedBanner)

        composeRule.onNodeWithTag("confirm_action_button").performClick()
        composeRule.waitForIdle()
        assertEquals(true, approvedResult)

        composeRule.onNodeWithTag("quick_command_qc_flash").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("Flashlight on karo", sentCommand)
        composeRule.onNodeWithTag("compact_greeting_card").assertDoesNotExist()
    }

    @Test
    fun `actions screen filters features cleanly`() {
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.AMOLED) {
                ActionsScreen(
                    settings = BabySettings(),
                    routinesCount = 4,
                    commandsCount = 1,
                    macrosCount = 2,
                    activeRemindersCount = 3,
                    memoriesCount = 5,
                    onToggleFeature = { _, _ -> },
                    onAutoReplyToggle = {},
                    onOpenRoutinesTab = {},
                    onOpenReminders = {},
                    onOpenMemory = {},
                    onOpenSettingsSection = {},
                    onRunNotifSummaryNow = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("action_card_routines").assertIsDisplayed()
        composeRule.onNodeWithTag("actions_search_field").performTextInput("Macro")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("action_card_macros").assertIsDisplayed()
        composeRule.onNodeWithTag("action_card_routines").assertDoesNotExist()
    }

    @Test
    fun `more screen global search filters destinations`() {
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                MoreScreen(
                    settings = BabySettings(),
                    permissions = testPermissions(false),
                    emergencyContactsCount = 1,
                    actionLogsCount = 12,
                    memoriesCount = 4,
                    onOpenSettings = {},
                    onOpenPermissions = {},
                    onOpenRoutinesTab = {},
                    onOpenReminders = {},
                    onOpenMemory = {},
                    onExportActionLogsShare = {},
                    onExportActionLogsToUri = {},
                    onReopenOnboarding = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("more_global_search_field").performTextInput("SOS")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Emergency SOS").assertIsDisplayed()
    }

    @Test
    fun `permissions screen renders checklist cards`() {
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                PermissionsScreen(
                    permissions = testPermissions(false).copy(recordAudio = true, postNotifications = true),
                    oemGuide = OemAutostartGuide("Samsung", listOf("Allow background activity"), emptyList()),
                    permissionHelper = null,
                    onRefresh = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("perm_card_mic").assertIsDisplayed()
    }

    @Test
    fun `reminders screen groups Today and Upcoming and triggers one-tap 10m snooze`() {
        var snoozedPair: Pair<Long, Int>? = null
        val now = System.currentTimeMillis()
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                RemindersScreen(
                    settings = BabySettings(),
                    permissions = testPermissions(true),
                    reminders = listOf(
                        ReminderEntity(id = 1L, title = "Doodh lena hai", triggerType = "TIME", triggerAtMs = now + 600_000L),
                        ReminderEntity(id = 2L, title = "Trip packing", triggerType = "TIME", triggerAtMs = now + 2 * 86_400_000L)
                    ),
                    savedPlaces = emptyList(),
                    onCreateTimeReminder = { _, _ -> },
                    onCreateLocationReminder = { _, _, _, _ -> },
                    onUpdateReminder = { _, _, _, _, _, _, _, _ -> },
                    onSnoozeReminder = { id, mins -> snoozedPair = id to mins },
                    onDeleteReminder = {},
                    onSavePlace = { _, _, _, _, _ -> },
                    onSaveCurrentGpsAsPlace = {},
                    onDeleteSavedPlace = {},
                    onGeocodeAddress = { null },
                    onFetchCurrentGps = { null },
                    onOpenExactAlarmSettings = {},
                    onRefreshPermissions = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminders_group_today"))
        composeRule.onNodeWithTag("reminders_group_today").assertIsDisplayed()
        composeRule.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("quick_snooze_10m_1"))
        composeRule.onNodeWithTag("quick_snooze_10m_1").performClick()
        composeRule.waitForIdle()
        assertEquals(1L to 10, snoozedPair)
        composeRule.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminders_group_upcoming"))
        composeRule.onNodeWithTag("reminders_group_upcoming").assertIsDisplayed()
    }

    @Test
    fun `memory screen category filter chips filter list accurately`() {
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.LIGHT) {
                MemoryScreen(
                    memories = listOf(
                        MemoryEntity(id = 1L, category = "NICKNAME", memoryKey = "Mummy", memoryValue = "Sunita"),
                        MemoryEntity(id = 2L, category = "FACT", memoryKey = "Bike", memoryValue = "Honda Activa")
                    ),
                    onBack = {},
                    onSaveMemory = { _, _, _ -> },
                    onUpdateMemory = {},
                    onDeleteMemory = {},
                    onWipeAll = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memory_card_1").assertIsDisplayed()
        composeRule.onNodeWithTag("memory_card_2").assertIsDisplayed()
        composeRule.onNodeWithTag("memory_filter_FACT").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memory_card_2").assertIsDisplayed()
        composeRule.onNodeWithTag("memory_card_1").assertDoesNotExist()
    }

    @Test
    fun `history screen displays task runs and opens detail sheet on item tap`() {
        val now = System.currentTimeMillis()
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                HistoryScreen(
                    taskRuns = listOf(
                        TaskRunEntity(
                            id = 1L,
                            commandText = "Flashlight on karo",
                            status = "COMPLETED",
                            stepsCount = 1,
                            maxSteps = 15,
                            brainSummary = "Groq",
                            finalReply = "Flashlight turned ON",
                            startedAt = now
                        )
                    ),
                    actionLogs = listOf(
                        ActionLogEntity(
                            id = 11L,
                            timestamp = now,
                            commandText = "Flashlight on karo",
                            toolName = "toggle_flashlight",
                            argsSummary = "{\"enable\":true}",
                            resultText = "ON",
                            targetApp = "System",
                            status = "OK",
                            source = "voice"
                        )
                    ),
                    onClearHistory = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Flashlight on karo").assertIsDisplayed()
        composeRule.onNodeWithText("Flashlight on karo").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Task Details").assertIsDisplayed()
    }

    @Test
    fun `settings screen renders in large fontScale 2f without crashing`() {
        composeRule.setContent {
            val cur = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(cur.density, fontScale = 2f)) {
                BabyTheme(themeMode = BabyThemeMode.AMOLED) {
                    SettingsScreen(
                        settings = BabySettings(),
                        permissions = testPermissions(true),
                        oemGuide = OemAutostartGuide("Pixel", listOf("No extra steps"), emptyList()),
                        permissionHelper = null,
                        onRefreshPermissions = {},
                        onSaveKeys = { _, _ -> },
                        onClearKeys = {},
                        onGeminiModelChange = {},
                        onGroqModelChange = {},
                        onPreferredBrainChange = {},
                        onAutoFallbackChange = {},
                        onWakeWordToggle = {},
                        onSensitivityChange = {},
                        onLanguageChange = {},
                        onConfirmSensitiveToggle = {},
                        onTtsRepliesToggle = {},
                        onFloatingBubbleToggle = {},
                        onReopenOnboarding = {}
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_search_input").assertIsDisplayed()
        composeRule.onNodeWithTag("theme_mode_amoled").assertIsDisplayed()
    }

    @Test
    fun `settings AI Models section displays editable fields, live models refresh, and Test models results`() {
        var refreshedKey: String? = null
        var testedConfig: AiModelConfig? = null
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                SettingsScreen(
                    settings = BabySettings(groqApiKey = "gsk_test", geminiApiKey = "AIzaTest"),
                    permissions = testPermissions(true),
                    oemGuide = OemAutostartGuide("Pixel", listOf("None"), emptyList()),
                    permissionHelper = null,
                    initialSection = "ai",
                    liveGroqModels = listOf("openai/gpt-oss-120b", "qwen/qwen3.6-27b"),
                    modelsTestState = ModelsTestUiState(
                        isTesting = false,
                        items = listOf(
                            ModelTestItem("Chat / Tools", "openai/gpt-oss-120b", true, "Pass (HTTP 200)"),
                            ModelTestItem("Gemini Flash", "gemini-2.5-flash", false, "Fail (HTTP 503)")
                        )
                    ),
                    onRefreshPermissions = {},
                    onSaveKeys = { _, _ -> },
                    onClearKeys = {},
                    onGeminiModelChange = {},
                    onGroqModelChange = {},
                    onRefreshLiveGroqModels = { refreshedKey = it },
                    onTestAllModels = { cfg, _, _ -> testedConfig = cfg },
                    onPreferredBrainChange = {},
                    onAutoFallbackChange = {},
                    onWakeWordToggle = {},
                    onSensitivityChange = {},
                    onLanguageChange = {},
                    onConfirmSensitiveToggle = {},
                    onTtsRepliesToggle = {},
                    onFloatingBubbleToggle = {},
                    onReopenOnboarding = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_chat_model_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_fast_model_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_stt_model_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_gemini_model_input").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("load_live_groq_models_button").performScrollTo().performClick()
        assertEquals("gsk_test", refreshedKey)

        composeRule.onNodeWithTag("test_models_button").performScrollTo().performClick()
        assertEquals(AiModelConfig.DEFAULT_CHAT_MODEL, testedConfig?.chatModel)

        composeRule.onNodeWithTag("models_test_results").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pass (HTTP 200)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Fail (HTTP 503)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `more screen provides direct entry points to routines, commands, macros, reminders, and memory`() {
        var openedRoutinesTab = -1
        var openedReminders = false
        var openedMemory = false
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                MoreScreen(
                    settings = BabySettings(),
                    permissions = testPermissions(true),
                    emergencyContactsCount = 1,
                    actionLogsCount = 5,
                    memoriesCount = 2,
                    onOpenSettings = {},
                    onOpenPermissions = {},
                    onOpenRoutinesTab = { openedRoutinesTab = it },
                    onOpenReminders = { openedReminders = true },
                    onOpenMemory = { openedMemory = true },
                    onExportActionLogsShare = {},
                    onExportActionLogsToUri = {},
                    onReopenOnboarding = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("more_open_routines").assertIsDisplayed().performClick()
        assertEquals(0, openedRoutinesTab)

        composeRule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_open_commands"))
        composeRule.onNodeWithTag("more_open_commands").performClick()
        assertEquals(1, openedRoutinesTab)

        composeRule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_open_macros"))
        composeRule.onNodeWithTag("more_open_macros").performClick()
        assertEquals(2, openedRoutinesTab)

        composeRule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_open_reminders"))
        composeRule.onNodeWithTag("more_open_reminders").performClick()
        assertTrue(openedReminders)

        composeRule.onNodeWithTag("more_list").performScrollToNode(hasTestTag("more_open_memory"))
        composeRule.onNodeWithTag("more_open_memory").performClick()
        assertTrue(openedMemory)
    }

    @Test
    fun `chat screen renders error banner retry, open ai settings, collapsible details, and chat error bubble details`() {
        var retriedCmd: String? = null
        var dismissed = false
        var openedAiSettings = false
        val largeMessages = List(1000) { idx ->
            when (idx) {
                998 -> ChatMessageEntity(id = idx + 1L, role = "user", content = "Last command")
                999 -> ChatMessageEntity(
                    id = idx + 1L,
                    role = "assistant",
                    content = "Groq server abhi busy hai (503). Retry karo ya Settings > AI Models check karo.",
                    brainUsed = "local",
                    toolName = "ai_error",
                    toolResult = "Provider: Groq | Model: openai/gpt-oss-120b | HTTP: 503"
                )
                else -> ChatMessageEntity(
                    id = idx + 1L,
                    role = if (idx % 2 == 0) "user" else "assistant",
                    content = "Message #$idx"
                )
            }
        }
        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.LIGHT) {
                ChatScreen(
                    messages = largeMessages,
                    agentState = AgentExecutionState(
                        isRunning = false,
                        lastError = "Groq model abhi available nahi hai.",
                        lastErrorDetails = "Provider: Groq | HTTP: 503 | Attempts: 4"
                    ),
                    settings = BabySettings(groqApiKey = "gsk_demo"),
                    permissions = testPermissions(true),
                    isRecording = false,
                    micAmplitude = 0f,
                    isWakeListening = false,
                    onSendCommand = { retriedCmd = it },
                    onToggleVoice = {},
                    onStopTask = {},
                    onApproveConfirmation = { _, _ -> },
                    onClearChat = {},
                    onNavigateToSettings = {},
                    onOpenAiSettings = { openedAiSettings = true },
                    onDismissError = { dismissed = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_error_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("agent_error_details_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_error_details_text").assertIsDisplayed()

        composeRule.onNodeWithTag("agent_error_open_ai_settings_button").performClick()
        assertTrue(openedAiSettings)

        composeRule.onNodeWithTag("chat_error_details_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_error_details_body", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("agent_error_retry_button").performClick()
        assertTrue(dismissed)
        assertEquals("Last command", retriedCmd)
    }
}

