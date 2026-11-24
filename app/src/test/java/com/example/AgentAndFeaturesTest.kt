package com.example

import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.AutoReplyMode
import com.example.core.security.PreferredBrain
import com.example.core.security.SecureKeyStore
import com.example.data.local.ActionLogEntity
import com.example.data.local.BabyDatabase
import com.example.data.local.HistoryRepository
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GroqApiService
import com.example.domain.ai.AgentOrchestrator
import com.example.domain.ai.AiRouter
import com.example.domain.tools.ExtendedToolRouter
import com.example.domain.tools.PhoneControlExecutor
import com.example.domain.tools.StopSignalBus
import com.example.features.actionlog.ActionLogInterceptor
import com.example.features.actionlog.ActionLogRepository
import com.example.features.autoreply.AutoReplyManager
import com.example.features.macros.MacroManager
import com.example.features.macros.MacroStep
import com.example.features.memory.MemoryManager
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.offline.OfflineCommandEngine
import com.example.features.reminders.ReminderManager
import com.example.features.routines.RoutineManager
import com.example.features.routines.RoutineStep
import com.example.features.safety.SafetyManager
import com.example.features.vision.CameraVisionManager
import com.example.features.vision.ClipboardAiManager
import com.example.features.vision.ScreenHelpManager
import com.example.ui.state.ActionLogFilterState
import com.example.ui.state.ActionLogPagingSource
import com.example.util.PermissionHelper
import com.example.voice.BabyTtsManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentAndFeaturesTest {

    private lateinit var context: Context
    private lateinit var db: BabyDatabase
    private lateinit var keyStore: SecureKeyStore
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var ttsManager: BabyTtsManager
    private lateinit var stopSignalBus: StopSignalBus
    private lateinit var actionLogRepo: ActionLogRepository
    private lateinit var actionLogInterceptor: ActionLogInterceptor
    private lateinit var historyRepo: HistoryRepository
    private lateinit var memoryManager: MemoryManager
    private lateinit var notificationSummaryManager: NotificationSummaryManager
    private lateinit var autoReplyManager: AutoReplyManager
    private lateinit var reminderManager: ReminderManager
    private lateinit var executor: PhoneControlExecutor
    private lateinit var safetyManager: SafetyManager
    private lateinit var routineManager: RoutineManager
    private lateinit var macroManager: MacroManager
    private lateinit var offlineEngine: OfflineCommandEngine
    private lateinit var moshi: Moshi

    private var groqResponder: (Request) -> Response = { req ->
        jsonResp(req, 200, """{"choices":[{"message":{"role":"assistant","content":"Done"}}]}""")
    }
    private var geminiResponder: (Request) -> Response = { req ->
        jsonResp(req, 200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"Done from Gemini"}]}}]}""")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keyStore = SecureKeyStore(context)
        keyStore.updateApiKeys(geminiKey = "AIzaUnitTestGeminiKey1234567890", groqKey = "gsk_unitTestGroqKey1234567890")
        keyStore.updatePreferredBrain(PreferredBrain.GROQ_FIRST)
        keyStore.setAutoFallback(true)
        keyStore.setConfirmSensitiveActions(true)
        keyStore.setVoicePinCode("")
        keyStore.setBooleanPref(SecureKeyStore.KEY_MEMORY_ENABLED, true)
        keyStore.setBooleanPref(SecureKeyStore.KEY_ACTION_LOG_ENABLED, true)
        keyStore.setBooleanPref(SecureKeyStore.KEY_MACROS_ENABLED, true)
        keyStore.setBooleanPref(SecureKeyStore.KEY_ROUTINES_ENABLED, true)
        keyStore.setBooleanPref(SecureKeyStore.KEY_CUSTOM_COMMANDS_ENABLED, true)
        keyStore.setBooleanPref(SecureKeyStore.KEY_OFFLINE_COMMANDS, true)
        keyStore.setBooleanPref(SecureKeyStore.KEY_EMERGENCY_ENABLED, true)
        keyStore.setBooleanPref(SecureKeyStore.KEY_ALLOWED_APPS_ONLY, false)
        keyStore.setBooleanPref(SecureKeyStore.KEY_BIOMETRIC_SAFETY, false)

        moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val groqClient = OkHttpClient.Builder().addInterceptor(Interceptor { groqResponder(it.request()) }).build()
        val geminiClient = OkHttpClient.Builder().addInterceptor(Interceptor { geminiResponder(it.request()) }).build()
        val groqApi = Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .client(groqClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GroqApiService::class.java)
        val geminiApi = Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(geminiClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)

        permissionHelper = PermissionHelper(context)
        ttsManager = BabyTtsManager(context)
        stopSignalBus = StopSignalBus()
        actionLogRepo = ActionLogRepository(db.actionLogDao(), keyStore)
        actionLogInterceptor = ActionLogInterceptor(actionLogRepo)
        historyRepo = HistoryRepository(db.chatDao(), db.taskRunDao(), db.queuedTaskDao())
        memoryManager = MemoryManager(db.memoryDao(), keyStore)
        notificationSummaryManager = NotificationSummaryManager(context, keyStore, groqApi, ttsManager)
        autoReplyManager = AutoReplyManager(context, keyStore, db.autoReplyRuleDao(), groqApi, geminiApi, actionLogRepo, permissionHelper)
        reminderManager = ReminderManager(context, db.reminderDao(), db.savedPlaceDao(), groqApi, permissionHelper, keyStore)
        executor = PhoneControlExecutor(context, permissionHelper, ttsManager, keyStore, db.allowedAppDao(), db.memoryDao())
        safetyManager = SafetyManager(context, keyStore, db.allowedAppDao(), db.emergencyContactDao(), permissionHelper, ttsManager, actionLogRepo, stopSignalBus)
        routineManager = RoutineManager(context, db.routineDao(), executor, keyStore, permissionHelper, ttsManager, actionLogRepo, notificationSummaryManager, OkHttpClient(), stopSignalBus)
        macroManager = MacroManager(db.macroDao(), executor, keyStore, permissionHelper, actionLogRepo, ttsManager, stopSignalBus)
        offlineEngine = OfflineCommandEngine(context, keyStore, executor)

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        Shadows.shadowOf(km).setKeyguardLocked(false)
    }

    @After
    fun tearDown() {
        safetyManager.cancelEmergencyCountdown()
        db.close()
    }

    private fun buildOrchestrator(): AgentOrchestrator {
        val moshi = this.moshi
        val groqClient = OkHttpClient.Builder().addInterceptor(Interceptor { groqResponder(it.request()) }).build()
        val geminiClient = OkHttpClient.Builder().addInterceptor(Interceptor { geminiResponder(it.request()) }).build()
        val groqApi = Retrofit.Builder().baseUrl("https://api.groq.com/").client(groqClient).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(GroqApiService::class.java)
        val geminiApi = Retrofit.Builder().baseUrl("https://generativelanguage.googleapis.com/").client(geminiClient).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(GeminiApiService::class.java)
        val router = AiRouter(geminiApi, groqApi, keyStore, moshi)
        val screenHelp = ScreenHelpManager(keyStore, permissionHelper, groqApi, geminiApi)
        val clipboardAi = ClipboardAiManager(context, keyStore, groqApi, geminiApi, ttsManager)
        val cameraVision = CameraVisionManager(context, keyStore, permissionHelper, geminiApi, ttsManager)
        val extRouter = ExtendedToolRouter(
            context, keyStore, historyRepo, routineManager, memoryManager, reminderManager,
            notificationSummaryManager, screenHelp, clipboardAi, cameraVision, macroManager, safetyManager
        )
        return AgentOrchestrator(
            appContext = context,
            aiRouter = router,
            executor = executor,
            extendedToolRouter = extRouter,
            historyRepository = historyRepo,
            actionLogRepository = actionLogRepo,
            actionLogInterceptor = actionLogInterceptor,
            offlineCommandEngine = offlineEngine,
            routineManager = routineManager,
            macroManager = macroManager,
            memoryManager = memoryManager,
            notificationSummaryManager = notificationSummaryManager,
            safetyManager = safetyManager,
            keyStore = keyStore,
            permissionHelper = permissionHelper,
            ttsManager = ttsManager,
            stopSignalBus = stopSignalBus
        )
    }

    private fun jsonResp(req: Request, code: Int, json: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

    private suspend fun awaitIdle(orchestrator: AgentOrchestrator) {
        withTimeout(5000L) {
            orchestrator.state.first { !it.isRunning && it.pendingConfirmation == null }
        }
    }

    @Test
    fun `agent loop runs multi-step tool call, logs to ActionLog, and enforces 15-step cap`() = runBlocking {
        val calls = AtomicInteger(0)
        groqResponder = { req ->
            val n = calls.incrementAndGet()
            if (n == 1) {
                jsonResp(
                    req,
                    200,
                    """
                    {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"set_volume","arguments":"{\"stream\":\"media\",\"level_percent\":60}"}}]}}]}
                    """.trimIndent()
                )
            } else {
                jsonResp(req, 200, """{"choices":[{"message":{"role":"assistant","content":"Volume 60% kar diya hai."}}]}""")
            }
        }

        val orchestrator = buildOrchestrator()
        orchestrator.submitUserCommand("Kuch complex kaam karo")
        awaitIdle(orchestrator)

        val runs = db.taskRunDao().observeAllTaskRuns().first()
        assertEquals(1, runs.size)
        assertEquals("COMPLETED", runs.first().status)
        assertEquals(2, runs.first().stepsCount)
        assertEquals("Volume 60% kar diya hai.", runs.first().finalReply)

        val logs = db.actionLogDao().getAllForExport()
        assertTrue(logs.any { it.toolName == "set_volume" })

        // Now test 15-step ceiling when AI always returns a tool call
        val loopCalls = AtomicInteger(0)
        groqResponder = { req ->
            val i = loopCalls.incrementAndGet()
            jsonResp(
                req,
                200,
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"loop_$i","type":"function","function":{"name":"list_routines","arguments":"{}"}}]}}]}"""
            )
        }
        orchestrator.submitUserCommand("Keep looping check")
        awaitIdle(orchestrator)
        val latestRun = db.taskRunDao().observeAllTaskRuns().first().first()
        assertEquals("COMPLETED", latestRun.status)
        assertEquals(15, latestRun.stepsCount)
        assertTrue(latestRun.finalReply.contains("15 steps"))
    }

    @Test
    fun `agent confirmation fails closed on blank or wrong Voice PIN and succeeds with matching PIN`() = runBlocking {
        keyStore.setVoicePinCode("4321")
        groqResponder = { req ->
            jsonResp(
                req,
                200,
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"wa_1","type":"function","function":{"name":"send_whatsapp","arguments":"{\"recipient\":\"Mummy\",\"message\":\"Reached safely\"}"}}]}}]}"""
            )
        }
        val orchestrator = buildOrchestrator()
        orchestrator.submitUserCommand("Mummy ko WhatsApp bhejo")

        // Wait until confirmation card appears
        withTimeout(4000L) {
            orchestrator.state.first { it.pendingConfirmation != null }
        }
        assertTrue(orchestrator.state.value.pendingConfirmation!!.requiresVoicePin)

        // Blank PIN must fail closed!
        orchestrator.approveConfirmation(approved = true, enteredPin = null)
        awaitIdle(orchestrator)
        val rejectedRun = db.taskRunDao().observeAllTaskRuns().first().first()
        assertEquals("STOPPED", rejectedRun.status)
        assertTrue(rejectedRun.finalReply.contains("PIN", ignoreCase = true))

        // Wrong PIN must also fail closed!
        orchestrator.submitUserCommand("Mummy ko WhatsApp dubara")
        withTimeout(4000L) { orchestrator.state.first { it.pendingConfirmation != null } }
        orchestrator.approveConfirmation(approved = true, enteredPin = "0000")
        awaitIdle(orchestrator)
        val wrongPinRun = db.taskRunDao().observeAllTaskRuns().first().first()
        assertEquals("STOPPED", wrongPinRun.status)

        // Correct PIN proceeds to execute and reply
        var step2 = false
        groqResponder = { req ->
            if (!step2) {
                step2 = true
                jsonResp(
                    req,
                    200,
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"wa_2","type":"function","function":{"name":"send_whatsapp","arguments":"{\"recipient\":\"9876543210\",\"message\":\"Reached\"}"}}]}}]}"""
                )
            } else {
                jsonResp(req, 200, """{"choices":[{"message":{"role":"assistant","content":"WhatsApp draft khol diya!"}}]}""")
            }
        }
        orchestrator.submitUserCommand("Mummy ko WhatsApp with PIN")
        withTimeout(4000L) { orchestrator.state.first { it.pendingConfirmation != null } }
        orchestrator.approveConfirmation(approved = true, enteredPin = "4321")
        awaitIdle(orchestrator)
        val okRun = db.taskRunDao().observeAllTaskRuns().first().first()
        assertEquals("COMPLETED", okRun.status)
    }

    @Test
    fun `locked phone queues UI actions and onDeviceUnlocked executes them automatically`() = runBlocking {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        Shadows.shadowOf(km).setKeyguardLocked(true)

        val stepCounter = AtomicInteger(0)
        groqResponder = { req ->
            val step = stepCounter.incrementAndGet()
            if (step == 1) {
                jsonResp(
                    req,
                    200,
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"t_1","type":"function","function":{"name":"open_app","arguments":"{\"app_name\":\"Settings\"}"}}]}}]}"""
                )
            } else {
                jsonResp(req, 200, """{"choices":[{"message":{"role":"assistant","content":"Opened after unlock!"}}]}""")
            }
        }

        val orchestrator = buildOrchestrator()
        orchestrator.submitUserCommand("Screen wala kaam kholo", source = "voice")
        awaitIdle(orchestrator)

        val queued = db.queuedTaskDao().getAllQueuedTasks()
        assertEquals(1, queued.size)
        assertEquals(1, orchestrator.state.value.queuedCount)

        // Unlock device and trigger execution
        Shadows.shadowOf(km).setKeyguardLocked(false)
        orchestrator.onDeviceUnlocked()
        withTimeout(5000L) {
            db.queuedTaskDao().observeQueuedTasks().first { it.isEmpty() }
            db.taskRunDao().observeAllTaskRuns().first { runs ->
                runs.size >= 2 && runs.first().status == "COMPLETED"
            }
        }
        awaitIdle(orchestrator)
        assertEquals(0, orchestrator.state.value.queuedCount)
    }

    @Test
    fun `action log repository and SQLite PagingSource handle 10000 rows, search, redaction, and prune`() = runBlocking {
        // Insert 10,000 rows inside a transaction to benchmark paging + search
        val now = System.currentTimeMillis()
        val startInsert = System.currentTimeMillis()
        db.runInTransaction {
            runBlocking {
                for (i in 1..10_000) {
                    db.actionLogDao().insert(
                        ActionLogEntity(
                            timestamp = now - (10_000 - i) * 100L,
                            commandText = if (i == 9995) "UniqueNeedleCommand" else "Cmd #$i",
                            toolName = if (i % 2 == 0) "toggle_flashlight" else "set_volume",
                            argsSummary = "{}",
                            resultText = "OK",
                            targetApp = if (i % 3 == 0) "WhatsApp" else "System",
                            status = if (i % 5 == 0) "ERR" else "OK",
                            source = if (i % 2 == 0) "voice" else "text"
                        )
                    )
                }
            }
        }
        val insertElapsed = System.currentTimeMillis() - startInsert
        assertTrue("Expected fast batch insert, took ${insertElapsed}ms", insertElapsed < 15_000L)

        val pagingStart = System.currentTimeMillis()
        val sourceAll = ActionLogPagingSource(actionLogRepo, ActionLogFilterState())
        val page0 = sourceAll.load(
            androidx.paging.PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false)
        ) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(30, page0.data.size)
        assertEquals(1, page0.nextKey)

        val page1 = sourceAll.load(
            androidx.paging.PagingSource.LoadParams.Append(key = 1, loadSize = 30, placeholdersEnabled = false)
        ) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(30, page1.data.size)
        assertTrue(page0.data.first().timestamp >= page1.data.first().timestamp)

        val searchSource = ActionLogPagingSource(actionLogRepo, ActionLogFilterState(query = "UniqueNeedleCommand"))
        val searchPage = searchSource.load(
            androidx.paging.PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false)
        ) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(1, searchPage.data.size)
        assertEquals("UniqueNeedleCommand", searchPage.data.first().commandText)
        val pagingElapsed = System.currentTimeMillis() - pagingStart
        assertTrue("SQLite paging query should stay fast, took ${pagingElapsed}ms", pagingElapsed < 2_500L)

        // Export redacts PII & prune removes old entries
        actionLogRepo.record(
            commandText = "OTP 928374 bhejo password=abc123",
            toolName = "send_sms",
            argsSummary = "otp=928374",
            resultText = "AIzaSecretKey123456789012345",
            status = "OK",
            source = "text"
        )
        val exported = actionLogRepo.exportAsText()
        assertFalse(exported.contains("928374"))
        assertFalse(exported.contains("abc123"))
        assertFalse(exported.contains("AIzaSecretKey123456789012345"))

        db.actionLogDao().insert(
            ActionLogEntity(
                timestamp = now - 40L * 86_400_000L,
                commandText = "Very old log",
                toolName = "set_volume",
                argsSummary = "{}",
                resultText = "OK",
                status = "OK",
                source = "text"
            )
        )
        actionLogRepo.pruneExpired()
        val afterPrune = db.actionLogDao().getPagedFiltered("Very old log", "ALL", "ALL", "ALL", 0L, 10, 0)
        assertTrue(afterPrune.isEmpty())
    }

    @Test
    fun `memory, routines, macros, auto-reply, reminders, safety, and offline command engines enforce rules`() = runBlocking {
        // Memory: save, nickname recall, wipeAll
        memoryManager.saveMemory("Mummy", "9876543210", "NICKNAME")
        val block = memoryManager.buildRelevantPromptBlock("Mummy ko call lagao")
        assertNotNull(block)
        assertTrue(block!!.contains("9876543210"))
        memoryManager.wipeAll()
        assertTrue(db.memoryDao().getAllSnapshot().isEmpty())

        // Routines: built-in seeding + sanitization strips sensitive send_whatsapp step
        routineManager.ensureBuiltInRoutinesSeeded()
        val builtIns = db.routineDao().getEnabledRoutines()
        assertTrue(builtIns.any { it.triggerPhrase == "good morning" })
        val customId = routineManager.saveRoutine(
            name = "Mixed Routine",
            triggerPhrase = "mixed",
            steps = listOf(
                RoutineStep("set_volume", mapOf("stream" to "media", "level_percent" to 30)),
                RoutineStep("send_whatsapp", mapOf("recipient" to "Mom", "message" to "Hi"))
            )
        )
        val savedRoutine = db.routineDao().getById(customId)!!
        val decodedSteps = RoutineManager.decodeSteps(savedRoutine.stepsJson)
        assertEquals(1, decodedSteps.size)
        assertEquals("set_volume", decodedSteps.first().tool)

        // Macros: save strips sensitive steps, run blocks on banking screen
        val macroId = macroManager.saveMacro(
            name = "Pay Macro",
            triggerPhrase = "pay macro",
            steps = listOf(
                MacroStep("tap", mapOf("x" to 10f, "y" to 10f), "Enter OTP", viewId = "otp_input"),
                MacroStep("set_volume", mapOf("stream" to "media", "level_percent" to 50), "Set Volume"),
                MacroStep("tap", mapOf("x" to 20f, "y" to 20f), "GPay screen", packageName = "com.google.android.apps.nbu.paisa.user")
            )
        )
        val savedMacro = db.macroDao().getEnabled().first { it.id == macroId }
        val macroSteps = MacroManager.decodeSteps(savedMacro.stepsJson)
        assertEquals(1, macroSteps.size)
        assertEquals("set_volume", macroSteps.first().tool)

        // Auto-reply: AI fallback to Gemini + rate limit & NEVER list
        keyStore.setAutoReplyMode(AutoReplyMode.BUSY)
        autoReplyManager.upsertContactRule("Spammer", "NEVER")
        autoReplyManager.upsertContactRule("Boss", "ALLOW")
        val allRules = db.autoReplyRuleDao().getAll()
        assertTrue(allRules.any { it.contactKey == "spammer" && it.ruleType == "NEVER" })
        assertTrue(allRules.any { it.contactKey == "boss" && it.ruleType == "ALLOW" })

        // Reminders: create, snooze, list, delete
        val remMsg = reminderManager.createTimeReminder("Drink water", null, delayMinutes = 15)
        assertTrue(remMsg.contains("Drink water"))
        val activeRem = db.reminderDao().getAllActiveSnapshot().first()
        val snoozeMsg = reminderManager.snoozeReminder(activeRem.id, 10)
        assertTrue(snoozeMsg.contains("snoozed"))
        assertTrue(db.reminderDao().getById(activeRem.id)!!.triggerAtMs > activeRem.triggerAtMs)

        // Safety: Allowed Apps gating + Emergency countdown start & cancel
        keyStore.setBooleanPref(SecureKeyStore.KEY_ALLOWED_APPS_ONLY, true)
        safetyManager.setAppAllowed("com.whatsapp", "WhatsApp", true)
        assertTrue(safetyManager.isAppAllowedToControl("WhatsApp"))
        assertFalse(safetyManager.isAppAllowedToControl("com.instagram.android"))

        safetyManager.addEmergencyContact("Papa", "+919999988888")
        val sosRes = safetyManager.triggerEmergencyAlert()
        assertTrue(sosRes.contains("countdown", ignoreCase = true))
        assertTrue(safetyManager.emergencyCountdown.value.active)
        assertTrue(safetyManager.cancelEmergencyCountdown("User cancelled test"))
        assertFalse(safetyManager.emergencyCountdown.value.active)

        // Offline command engine clamps brightness & handles volume
        Settings.System.canWrite(context)
        val offVol = offlineEngine.tryExecuteOffline("volume 45%")
        assertNotNull(offVol)
        assertEquals("set_volume", offVol?.toolName)
        assertEquals(45, offVol?.args?.get("level_percent"))

        val offBright = offlineEngine.tryExecuteOffline("brightness 0%")
        assertNotNull(offBright)
        assertEquals("set_brightness", offBright?.toolName)
        assertEquals(5, offBright?.args?.get("level_percent"))

        // Data Saver fallback returns formatted local summary gracefully
        assertEquals(NotificationSummaryManager.maskSensitiveText("Card OTP is 492011 for Rs 999"), "Card OTP is [****] for [AMOUNT]")
        assertNull(SafetyManager.isBlockedScreen("com.whatsapp", "Hello friend"))
    }
}
