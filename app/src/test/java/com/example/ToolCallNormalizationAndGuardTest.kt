package com.example

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.BabySettings
import com.example.core.security.PreferredBrain
import com.example.core.security.SecureKeyStore
import com.example.data.local.BabyDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.HistoryRepository
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GroqApiService
import com.example.data.remote.MercuryApiService
import com.example.domain.ai.AgentExecutionState
import com.example.domain.ai.AgentOrchestrator
import com.example.domain.ai.AgentStepTurn
import com.example.domain.ai.AiCallException
import com.example.domain.ai.AiRouter
import com.example.domain.ai.ToolCallNormalizer
import com.example.domain.tools.AppResolver
import com.example.domain.tools.ExtendedToolRouter
import com.example.domain.tools.PhoneControlExecutor
import com.example.domain.tools.StopSignalBus
import com.example.domain.tools.ToolDefinitions
import com.example.features.actionlog.ActionLogInterceptor
import com.example.features.actionlog.ActionLogRepository
import com.example.features.macros.MacroManager
import com.example.features.memory.MemoryManager
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.offline.FastPathIntentMatcher
import com.example.features.offline.OfflineCommandEngine
import com.example.features.reminders.ReminderManager
import com.example.features.routines.RoutineManager
import com.example.features.safety.SafetyManager
import com.example.features.searchplay.SearchPlayEngine
import com.example.features.vision.CameraVisionManager
import com.example.features.vision.ClipboardAiManager
import com.example.features.vision.ScreenHelpManager
import com.example.ui.components.ToolActionStatus
import com.example.ui.components.presentToolAction
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.collapseRepeatedToolErrors
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.util.PermissionHelper
import com.example.util.PermissionSnapshot
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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
class ToolCallNormalizationAndGuardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: BabyDatabase
    private lateinit var keyStore: SecureKeyStore
    private lateinit var moshi: Moshi
    private lateinit var permHelper: PermissionHelper
    private lateinit var ttsManager: BabyTtsManager
    private lateinit var stopBus: StopSignalBus

    private val capturedRequests = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keyStore = SecureKeyStore(context)
        keyStore.clearApiKeys()
        keyStore.resetModelConfig()
        keyStore.setAutoFallback(true)
        keyStore.setConfirmSensitiveActions(false)
        keyStore.setBooleanPref(SecureKeyStore.KEY_DATA_SAVER, false)
        keyStore.setBooleanPref(SecureKeyStore.KEY_ALLOWED_APPS_ONLY, false)
        keyStore.setBooleanPref(SecureKeyStore.KEY_ACTION_LOG_ENABLED, true)
        moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        permHelper = PermissionHelper(context)
        ttsManager = BabyTtsManager(context)
        stopBus = StopSignalBus()
        capturedRequests.clear()

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        Shadows.shadowOf(km).setKeyguardLocked(false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun readRequestBody(req: Request): String {
        val copy = req.newBuilder().build()
        val buf = Buffer()
        copy.body?.writeTo(buf)
        return buf.readUtf8()
    }

    private fun jsonResponse(req: Request, code: Int, json: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

    private fun buildRouter(
        mercuryHandler: (Request) -> Response = { req -> jsonResponse(req, 500, "{}") },
        geminiHandler: (Request) -> Response = { req -> jsonResponse(req, 500, "{}") },
        groqHandler: (Request) -> Response = { req -> jsonResponse(req, 500, "{}") }
    ): AiRouter {
        val mercuryClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedRequests += "MERCURY:${readRequestBody(req)}"
                mercuryHandler(req)
            })
            .build()
        val geminiClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedRequests += "GEMINI:${readRequestBody(req)}"
                geminiHandler(req)
            })
            .build()
        val groqClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedRequests += "GROQ:${readRequestBody(req)}"
                groqHandler(req)
            })
            .build()

        val mercuryApi = Retrofit.Builder().baseUrl("https://api.inceptionlabs.ai/")
            .client(mercuryClient).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(MercuryApiService::class.java)
        val geminiApi = Retrofit.Builder().baseUrl("https://generativelanguage.googleapis.com/")
            .client(geminiClient).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(GeminiApiService::class.java)
        val groqApi = Retrofit.Builder().baseUrl("https://api.groq.com/")
            .client(groqClient).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(GroqApiService::class.java)

        return AiRouter(geminiApi, groqApi, mercuryApi, keyStore, moshi).apply {
            sleeper = {}
            jitterRatioProvider = { 0.0 }
        }
    }

    private fun installMockLaunchableApp(packageName: String, label: String) {
        val pm = context.packageManager
        val shadowPm = Shadows.shadowOf(pm)
        val pkgInfo = PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.nonLocalizedLabel = label
            }
        }
        shadowPm.installPackage(pkgInfo)

        val actInfo = ActivityInfo().apply {
            this.packageName = packageName
            this.name = "$packageName.MainActivity"
            this.nonLocalizedLabel = label
            this.applicationInfo = pkgInfo.applicationInfo
            this.exported = true
        }
        val resolveInfo = ResolveInfo().apply {
            this.activityInfo = actInfo
            this.nonLocalizedLabel = label
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = packageName
        }
        shadowPm.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            resolveInfo
        )
        shadowPm.addResolveInfoForIntent(launcherIntent, resolveInfo)
        val filter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        shadowPm.addActivityIfNotPresent(ComponentName(packageName, actInfo.name))
        @Suppress("DEPRECATION")
        shadowPm.addIntentFilterForActivity(ComponentName(packageName, actInfo.name), filter)
    }

    // ---- 1. Cross-Provider Tool-Call Parsing & Schema Validation ----

    @Test
    fun `normalizer parses object args, string args, nested function, Gemini functionCall, and masks debug logs`(): Unit = runBlocking {
        val secretGroq = "gsk_superSecretGroqKey99887766"
        val secretGem = "AIzaSuperSecretGeminiKey11223344"
        keyStore.updateAllApiKeys(
            inceptionKey = "inc_superSecretMercuryKey5544",
            geminiKey = secretGem,
            groqKey = secretGroq
        )

        val debugLogs = mutableListOf<String>()

        // A. Mercury response with JSON OBJECT arguments + field aliases ("app", "q")
        keyStore.updatePreferredBrain(PreferredBrain.MERCURY_FIRST)
        val mercuryRouter = buildRouter(
            mercuryHandler = { req ->
                jsonResponse(
                    req,
                    200,
                    """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "tool_calls": [{
                            "id": "merc_1",
                            "type": "function",
                            "function": {
                              "name": "search_in_app",
                              "arguments": {"app": "YouTube", "q": "lofi"}
                            }
                          }]
                        }
                      }]
                    }
                    """.trimIndent()
                )
            }
        ).apply { debugLogSink = { debugLogs += it } }

        val mercPlan = mercuryRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "YouTube pe lofi search karo")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = "hinglish"
        ).getOrThrow()
        assertEquals(1, mercPlan.toolCalls.size)
        assertEquals("search_in_app", mercPlan.toolCalls.first().name)
        assertEquals("YouTube", mercPlan.toolCalls.first().args["app_name"])
        assertEquals("lofi", mercPlan.toolCalls.first().args["query"])
        assertTrue(debugLogs.any { it.contains("[Mercury/") && it.contains("raw_tool_calls=") && it.contains("YouTube") })

        // B. Groq response with JSON STRING arguments + package alias + literal "null" rejected
        keyStore.updatePreferredBrain(PreferredBrain.GROQ_FIRST)
        val groqRouter = buildRouter(
            groqHandler = { req ->
                jsonResponse(
                    req,
                    200,
                    """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "tool_calls": [{
                            "id": "groq_1",
                            "type": "function",
                            "function": {
                              "name": "send_whatsapp",
                              "arguments": "{\"name\":\"Mummy\",\"message\":\"Secret OTP $secretGroq\",\"extra\":\"null\"}"
                            }
                          }]
                        }
                      }]
                    }
                    """.trimIndent()
                )
            }
        ).apply { debugLogSink = { debugLogs += it } }

        val groqPlan = groqRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Message Mummy")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = "hinglish"
        ).getOrThrow()
        assertEquals("send_whatsapp", groqPlan.toolCalls.single().name)
        assertEquals("Mummy", groqPlan.toolCalls.single().args["recipient"])
        assertFalse(groqPlan.toolCalls.single().args.containsKey("extra"))

        // Verify debug log masked both secret key and message body content!
        val groqDebugLine = debugLogs.last { it.contains("[Groq/") && it.contains("raw_tool_calls=") }
        assertFalse(groqDebugLine.contains(secretGroq))
        assertFalse(groqDebugLine.contains("Secret OTP"))
        assertTrue(groqDebugLine.contains("[MASKED_CONTENT]") || groqDebugLine.contains("[REDACTED_KEY]"))

        // C. Gemini response with functionCall {name, args} using aliases ("package", "text")
        keyStore.updatePreferredBrain(PreferredBrain.GEMINI_FIRST)
        val geminiRouter = buildRouter(
            geminiHandler = { req ->
                jsonResponse(
                    req,
                    200,
                    """
                    {
                      "candidates": [{
                        "content": {
                          "role": "model",
                          "parts": [{
                            "functionCall": {
                              "name": "search_in_app",
                              "args": {"package": "Spotify", "text": "lofi", "auto_play": true}
                            }
                          }]
                        }
                      }]
                    }
                    """.trimIndent()
                )
            }
        ).apply { debugLogSink = { debugLogs += it } }

        val gemPlan = geminiRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Spotify pe lofi play karo")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = "hinglish"
        ).getOrThrow()
        assertEquals("search_in_app", gemPlan.toolCalls.single().name)
        assertEquals("Spotify", gemPlan.toolCalls.single().args["app_name"])
        assertEquals("lofi", gemPlan.toolCalls.single().args["query"])
        assertEquals(true, gemPlan.toolCalls.single().args["auto_play"])
        assertTrue(debugLogs.any { it.contains("[Gemini/") && it.contains("raw_tool_calls=") })

        // D. Direct nested {"function": {"name","arguments"}} and malformed JSON handling
        val nestedNormalized = ToolCallNormalizer.normalizeFromRaw(
            mapOf(
                "function" to mapOf(
                    "name" to "open_app",
                    "arguments" to mapOf("name" to "Instagram")
                )
            )
        )
        assertNotNull(nestedNormalized)
        assertEquals("open_app", nestedNormalized!!.name)
        assertEquals("Instagram", nestedNormalized.args["app_name"])
        assertTrue(nestedNormalized.missingRequired.isEmpty())

        val malformedNormalized = ToolCallNormalizer.normalizeFromRaw(
            mapOf(
                "function" to mapOf(
                    "name" to "open_app",
                    "arguments" to "{bad_json: ["
                )
            )
        )
        assertNotNull(malformedNormalized)
        assertTrue(malformedNormalized!!.isMalformedJson)
        assertEquals(listOf("app_name"), malformedNormalized.missingRequired)
    }

    @Test
    fun `missing required argument sends repair prompt once across Groq and Gemini and stops cleanly if still missing`(): Unit = runBlocking {
        keyStore.updateApiKeys(geminiKey = "AIzaTestGemKey1234567890", groqKey = "gsk_testGroqKey1234567890")
        keyStore.setAutoFallback(false)

        // 1. Groq sends open_app with literal "null" -> repaired once -> succeeds
        keyStore.updatePreferredBrain(PreferredBrain.GROQ_FIRST)
        val groqRepairAttempts = AtomicInteger(0)
        val repairOkRouter = buildRouter(
            groqHandler = { req ->
                if (groqRepairAttempts.incrementAndGet() == 1) {
                    jsonResponse(
                        req,
                        200,
                        """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"open_app","arguments":{"app":"null"}}}]}}]}"""
                    )
                } else {
                    jsonResponse(
                        req,
                        200,
                        """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c2","type":"function","function":{"name":"open_app","arguments":"{\"app_name\":\"YouTube\"}"}}]}}]}"""
                    )
                }
            }
        )
        val okPlan = repairOkRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "YouTube open karo")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = "hinglish"
        ).getOrThrow()
        assertEquals(2, groqRepairAttempts.get())
        assertEquals("open_app", okPlan.toolCalls.single().name)
        assertEquals("YouTube", okPlan.toolCalls.single().args["app_name"])
        assertTrue(capturedRequests.last().contains("Parameter 'app_name' is missing"))

        // 2. Gemini sends open_app missing required arg twice -> stops immediately with validation failure!
        capturedRequests.clear()
        keyStore.updatePreferredBrain(PreferredBrain.GEMINI_FIRST)
        val gemAttempts = AtomicInteger(0)
        val gemFailRouter = buildRouter(
            geminiHandler = { req ->
                gemAttempts.incrementAndGet()
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"open_app","args":{}}}]}}]}"""
                )
            }
        )
        val failRes = gemFailRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "App kholo")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = "hinglish"
        )
        assertTrue(failRes.isFailure)
        assertEquals(2, gemAttempts.get())
        val ex = failRes.exceptionOrNull() as AiCallException
        assertTrue(ex.failure.isValidationFailure)
        assertTrue(ex.message.orEmpty().contains("app_name"))
    }

    @Test
    fun `tool schema required keys match normalizer expectations across all required tools`() {
        val requiredSpecs = ToolDefinitions.allTools.filter { it.required.isNotEmpty() }
        assertTrue(requiredSpecs.isNotEmpty())
        for (spec in requiredSpecs) {
            val missingAll = ToolDefinitions.validateRequiredArgs(spec.name, emptyMap())
            assertEquals("Expected all required args missing for ${spec.name}", spec.required, missingAll)

            val allNullStrings = spec.required.associateWith { "null" }
            val missingWithLiteralNull = ToolDefinitions.validateRequiredArgs(spec.name, allNullStrings)
            assertEquals("Literal 'null' must fail validation for ${spec.name}", spec.required, missingWithLiteralNull)
        }
    }

    // ---- 2. UI Card Formatting, Details, and Repeated Error Collapsing ----

    @Test
    fun `presentToolAction never leaks placeholders or nullnull and formats running success and error states`() {
        val openYoutube = presentToolAction(
            toolName = "open_app",
            argsJson = """{"app":"YouTube"}""",
            resultText = "OK",
            detailText = "Opened YouTube"
        )
        assertEquals("Opening YouTube", openYoutube.title)
        assertEquals(ToolActionStatus.SUCCESS, openYoutube.status)

        val searchLofi = presentToolAction(
            toolName = "search_in_app",
            argsJson = """{"package":"YouTube","q":"lofi"}""",
            resultText = "OK",
            detailText = "Showing results"
        )
        assertEquals("Searching 'lofi' in YouTube", searchLofi.title)

        val brokenNullCard = presentToolAction(
            toolName = "search_in_app",
            argsJson = """{"app":"null","query":"null","_raw_call":"search_in_app({})"}""",
            resultText = "ERR",
            detailText = "App ka naam missing hai"
        )
        assertEquals("Working on it", brokenNullCard.title)
        assertEquals(ToolActionStatus.ERROR, brokenNullCard.status)
        assertFalse(brokenNullCard.title.contains("null", ignoreCase = true))
        assertFalse(brokenNullCard.title.contains("\$TOOL"))
        assertTrue(brokenNullCard.errorSummary.orEmpty().contains("missing"))
        assertTrue(brokenNullCard.rawCallDetails.contains("search_in_app"))
        assertTrue(brokenNullCard.rawResultDetails.contains("App ka naam missing hai"))

        val literalPlaceholder = presentToolAction(
            toolName = "\$TOOL NAME",
            argsJson = """{"app":null}""",
            resultText = "ERR",
            detailText = "ERR"
        )
        assertEquals("Working on it", literalPlaceholder.title)
        assertFalse(literalPlaceholder.title.contains("\$TOOL"))

        val runningCard = presentToolAction(
            toolName = "open_app",
            argsJson = """{"app_name":"Spotify"}""",
            resultText = null,
            isRunning = true
        )
        assertEquals(ToolActionStatus.RUNNING, runningCard.status)
        assertEquals("Opening Spotify", runningCard.title)
    }

    @Test
    fun `chat screen collapses repeated error cards into x5 counter, shows running card, and toggles Details section`() {
        val repeatedErrors = (1L..5L).map { id ->
            ChatMessageEntity(
                id = id,
                role = "tool",
                toolName = "open_app",
                toolArgsJson = """{"app_name":"BrokenApp","_raw_call":"open_app({\"app_name\":\"BrokenApp\"})"}""",
                toolResult = "ERR",
                content = "BrokenApp phone mein nahi mila."
            )
        }
        val okMsg = ChatMessageEntity(
            id = 6L,
            role = "tool",
            toolName = "toggle_flashlight",
            toolArgsJson = """{"enable":true}""",
            toolResult = "OK",
            content = "Flashlight ON kar di."
        )
        val allMsgs = repeatedErrors + okMsg

        val collapsed = collapseRepeatedToolErrors(allMsgs)
        assertEquals(2, collapsed.size)
        assertEquals(5, collapsed[0].repeatCount)
        assertEquals(1, collapsed[1].repeatCount)

        composeRule.setContent {
            BabyTheme(themeMode = BabyThemeMode.DARK) {
                Box(modifier = Modifier.size(width = 412.dp, height = 915.dp)) {
                    ChatScreen(
                        messages = allMsgs,
                        agentState = AgentExecutionState(
                            isRunning = true,
                            runningToolName = "search_in_app",
                            runningToolArgsJson = """{"app_name":"YouTube","query":"lofi"}"""
                        ),
                        settings = BabySettings(groqApiKey = "gsk_demo"),
                        permissions = PermissionSnapshot(
                            recordAudio = true, postNotifications = true, accessibility = true,
                            notificationListener = true, overlayBubble = true, batteryOptimizationIgnored = true,
                            callPhone = true, sendSms = true, readContacts = true, writeSettings = true,
                            dndAccess = true, camera = true, readCalendar = true, fineLocation = true, exactAlarm = true
                        ),
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
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_messages_list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_error_repeat_badge", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("x5", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Opening BrokenApp", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("BrokenApp phone mein nahi mila.", useUnmergedTree = true).assertIsDisplayed()

        // Tap collapsible "Details" on collapsed card (id = 5L is the latest representative)
        composeRule.onNodeWithTag("tool_card_details_toggle_5").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_card_details_body_5").assertIsDisplayed()

        composeRule.onNodeWithTag("chat_messages_list").performScrollToIndex(2)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("running_tool_card", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Searching 'lofi' in YouTube", useUnmergedTree = true).assertIsDisplayed()
    }

    // ---- 3. Agent Loop Guard: Stops After 2 Identical Failures ----

    @Test
    fun `agent loop guard stops after 2 identical failing tool calls instead of looping 15 times`(): Unit = runBlocking {
        keyStore.updateApiKeys(geminiKey = "AIzaTestGeminiKey1234567890", groqKey = "gsk_testGroqKey1234567890")
        keyStore.updatePreferredBrain(PreferredBrain.GROQ_FIRST)

        val llmCalls = AtomicInteger(0)
        val groqClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val n = llmCalls.incrementAndGet()
                jsonResponse(
                    chain.request(),
                    200,
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"bad_$n","type":"function","function":{"name":"open_app","arguments":"{\"app_name\":\"TotallyNonExistentAppZyx\"}"}}]}}]}"""
                )
            })
            .build()
        val groqApi = Retrofit.Builder().baseUrl("https://api.groq.com/").client(groqClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(GroqApiService::class.java)
        val geminiApi = Retrofit.Builder().baseUrl("https://generativelanguage.googleapis.com/")
            .client(OkHttpClient()).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(GeminiApiService::class.java)

        val router = AiRouter(geminiApi, groqApi, keyStore, moshi)
        val actionRepo = ActionLogRepository(db.actionLogDao(), keyStore)
        val historyRepo = HistoryRepository(db.chatDao(), db.taskRunDao(), db.queuedTaskDao())
        val memoryMgr = MemoryManager(db.memoryDao(), keyStore)
        val notifMgr = NotificationSummaryManager(context, keyStore, groqApi, ttsManager)
        val executor = PhoneControlExecutor(context, permHelper, ttsManager, keyStore, db.allowedAppDao(), db.memoryDao())
        val safetyMgr = SafetyManager(context, keyStore, db.allowedAppDao(), db.emergencyContactDao(), permHelper, ttsManager, actionRepo, stopBus)
        val routineMgr = RoutineManager(context, db.routineDao(), executor, keyStore, permHelper, ttsManager, actionRepo, notifMgr, OkHttpClient(), stopBus)
        val macroMgr = MacroManager(db.macroDao(), executor, keyStore, permHelper, actionRepo, ttsManager, stopBus)
        val reminderMgr = ReminderManager(context, db.reminderDao(), db.savedPlaceDao(), groqApi, permHelper, keyStore)
        val extRouter = ExtendedToolRouter(
            context, keyStore, historyRepo, routineMgr, memoryMgr, reminderMgr, notifMgr,
            ScreenHelpManager(keyStore, permHelper, groqApi, geminiApi),
            ClipboardAiManager(context, keyStore, groqApi, geminiApi, ttsManager),
            CameraVisionManager(context, keyStore, permHelper, geminiApi, ttsManager),
            macroMgr, safetyMgr
        )
        val orchestrator = AgentOrchestrator(
            appContext = context,
            aiRouter = router,
            executor = executor,
            extendedToolRouter = extRouter,
            historyRepository = historyRepo,
            actionLogRepository = actionRepo,
            actionLogInterceptor = ActionLogInterceptor(actionRepo),
            offlineCommandEngine = OfflineCommandEngine(context, keyStore, executor),
            routineManager = routineMgr,
            macroManager = macroMgr,
            memoryManager = memoryMgr,
            notificationSummaryManager = notifMgr,
            safetyManager = safetyMgr,
            keyStore = keyStore,
            permissionHelper = permHelper,
            ttsManager = ttsManager,
            stopSignalBus = stopBus
        )

        // Non-fast-path command so it enters AgentOrchestrator's LLM tool loop
        orchestrator.submitUserCommand("Check diagnostics and inspect TotallyNonExistentAppZyx via agent")
        withTimeout(5000L) {
            orchestrator.state.first { !it.isRunning && it.pendingConfirmation == null }
        }

        // Must stop on 2nd failure instead of looping 15 times
        assertEquals(2, llmCalls.get())
        val latestRun = db.taskRunDao().observeAllTaskRuns().first().first()
        assertEquals("FAILED", latestRun.status)
        assertEquals(2, latestRun.stepsCount)
        assertTrue(latestRun.finalReply.contains("do baar fail ho gaya"))
        assertTrue(latestRun.finalReply.contains("TotallyNonExistentAppZyx"))
        assertTrue(orchestrator.state.value.lastErrorDetails.orEmpty().contains("open_app"))
        safetyMgr.cancelEmergencyCountdown()
    }

    @Test
    fun `failed tool executions inside multi-tool turns count toward the 15-step ceiling`(): Unit = runBlocking {
        keyStore.updateApiKeys(geminiKey = "AIzaTestGeminiKey1234567890", groqKey = "gsk_testGroqKey1234567890")
        keyStore.updatePreferredBrain(PreferredBrain.GROQ_FIRST)

        val llmCalls = AtomicInteger(0)
        val groqClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val n = llmCalls.incrementAndGet()
                // 3 distinct failing tool calls per LLM turn -> 1 base step + 2 extra failed steps = 3 steps per turn -> 15 steps in 5 turns
                jsonResponse(
                    chain.request(),
                    200,
                    """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "tool_calls": [
                            {"id": "f_${n}_1", "type": "function", "function": {"name": "open_app", "arguments": "{\"app_name\":\"MissingApp_${n}_A\"}"}},
                            {"id": "f_${n}_2", "type": "function", "function": {"name": "open_app", "arguments": "{\"app_name\":\"MissingApp_${n}_B\"}"}},
                            {"id": "f_${n}_3", "type": "function", "function": {"name": "open_app", "arguments": "{\"app_name\":\"MissingApp_${n}_C\"}"}}
                          ]
                        }
                      }]
                    }
                    """.trimIndent()
                )
            })
            .build()
        val groqApi = Retrofit.Builder().baseUrl("https://api.groq.com/").client(groqClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(GroqApiService::class.java)
        val geminiApi = Retrofit.Builder().baseUrl("https://generativelanguage.googleapis.com/")
            .client(OkHttpClient()).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(GeminiApiService::class.java)

        val router = AiRouter(geminiApi, groqApi, keyStore, moshi)
        val actionRepo = ActionLogRepository(db.actionLogDao(), keyStore)
        val historyRepo = HistoryRepository(db.chatDao(), db.taskRunDao(), db.queuedTaskDao())
        val memoryMgr = MemoryManager(db.memoryDao(), keyStore)
        val notifMgr = NotificationSummaryManager(context, keyStore, groqApi, ttsManager)
        val executor = PhoneControlExecutor(context, permHelper, ttsManager, keyStore, db.allowedAppDao(), db.memoryDao())
        val safetyMgr = SafetyManager(context, keyStore, db.allowedAppDao(), db.emergencyContactDao(), permHelper, ttsManager, actionRepo, stopBus)
        val routineMgr = RoutineManager(context, db.routineDao(), executor, keyStore, permHelper, ttsManager, actionRepo, notifMgr, OkHttpClient(), stopBus)
        val macroMgr = MacroManager(db.macroDao(), executor, keyStore, permHelper, actionRepo, ttsManager, stopBus)
        val reminderMgr = ReminderManager(context, db.reminderDao(), db.savedPlaceDao(), groqApi, permHelper, keyStore)
        val extRouter = ExtendedToolRouter(
            context, keyStore, historyRepo, routineMgr, memoryMgr, reminderMgr, notifMgr,
            ScreenHelpManager(keyStore, permHelper, groqApi, geminiApi),
            ClipboardAiManager(context, keyStore, groqApi, geminiApi, ttsManager),
            CameraVisionManager(context, keyStore, permHelper, geminiApi, ttsManager),
            macroMgr, safetyMgr
        )
        val orchestrator = AgentOrchestrator(
            appContext = context,
            aiRouter = router,
            executor = executor,
            extendedToolRouter = extRouter,
            historyRepository = historyRepo,
            actionLogRepository = actionRepo,
            actionLogInterceptor = ActionLogInterceptor(actionRepo),
            offlineCommandEngine = OfflineCommandEngine(context, keyStore, executor),
            routineManager = routineMgr,
            macroManager = macroMgr,
            memoryManager = memoryMgr,
            notificationSummaryManager = notifMgr,
            safetyManager = safetyMgr,
            keyStore = keyStore,
            permissionHelper = permHelper,
            ttsManager = ttsManager,
            stopSignalBus = stopBus
        )

        orchestrator.submitUserCommand("Diagnose multiple missing targets via agent")
        withTimeout(10_000L) {
            orchestrator.state.first { !it.isRunning && it.pendingConfirmation == null }
        }

        // Because each of the 3 failing tools counts toward the 15-step limit, 5 LLM turns consume 15 steps
        assertEquals(5, llmCalls.get())
        val latestRun = db.taskRunDao().observeAllTaskRuns().first().first()
        assertEquals(15, latestRun.stepsCount)
        assertTrue(latestRun.finalReply.contains("15 steps"))
        safetyMgr.cancelEmergencyCountdown()
    }

    // ---- 4. Open App & Search-in-App End-to-End & Hinglish Phrase Tests ----

    @Test
    fun `open_app and search_in_app handle required Hinglish commands and run AppResolver cleanly`(): Unit = runBlocking {
        installMockLaunchableApp("com.google.android.youtube", "YouTube")
        installMockLaunchableApp("com.instagram.android", "Instagram")
        installMockLaunchableApp("com.spotify.music", "Spotify")

        val appResolver = AppResolver(context, db.appAliasDao(), db.allowedAppDao(), keyStore)
        val searchPlayEngine = SearchPlayEngine(context, db.searchRecipeDao(), appResolver, keyStore)
        val executor = PhoneControlExecutor(
            context, permHelper, ttsManager, keyStore, db.allowedAppDao(), db.memoryDao(), appResolver
        )
        val actionRepo = ActionLogRepository(db.actionLogDao(), keyStore)
        val historyRepo = HistoryRepository(db.chatDao(), db.taskRunDao(), db.queuedTaskDao())
        val memoryMgr = MemoryManager(db.memoryDao(), keyStore)
        val groqApi = Retrofit.Builder().baseUrl("https://api.groq.com/").client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(GroqApiService::class.java)
        val geminiApi = Retrofit.Builder().baseUrl("https://generativelanguage.googleapis.com/").client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(GeminiApiService::class.java)
        val notifMgr = NotificationSummaryManager(context, keyStore, groqApi, ttsManager)
        val safetyMgr = SafetyManager(context, keyStore, db.allowedAppDao(), db.emergencyContactDao(), permHelper, ttsManager, actionRepo, stopBus)
        val routineMgr = RoutineManager(context, db.routineDao(), executor, keyStore, permHelper, ttsManager, actionRepo, notifMgr, OkHttpClient(), stopBus)
        val macroMgr = MacroManager(db.macroDao(), executor, keyStore, permHelper, actionRepo, ttsManager, stopBus)
        val reminderMgr = ReminderManager(context, db.reminderDao(), db.savedPlaceDao(), groqApi, permHelper, keyStore)
        val extRouter = ExtendedToolRouter(
            context = context,
            keyStore = keyStore,
            historyRepository = historyRepo,
            routineManager = routineMgr,
            memoryManager = memoryMgr,
            reminderManager = reminderMgr,
            notificationSummaryManager = notifMgr,
            screenHelpManager = ScreenHelpManager(keyStore, permHelper, groqApi, geminiApi),
            clipboardAiManager = ClipboardAiManager(context, keyStore, groqApi, geminiApi, ttsManager),
            cameraVisionManager = CameraVisionManager(context, keyStore, permHelper, geminiApi, ttsManager),
            macroManager = macroMgr,
            safetyManager = safetyMgr,
            searchPlayEngine = searchPlayEngine,
            aiRouter = null
        )

        // Phrase 1: "YouTube open karo"
        val p1 = FastPathIntentMatcher.match("YouTube open karo")
        assertNotNull(p1)
        assertEquals("open_app", p1!!.toolName)
        assertEquals("youtube", p1.args["app_name"])
        val r1 = executor.execute(p1.toolName, p1.args)
        assertTrue("Expected YouTube open to succeed: ${r1.output}", r1.ok)

        // Phrase 2: "yutub kholo"
        val p2 = FastPathIntentMatcher.match("yutub kholo")
        assertNotNull(p2)
        assertEquals("open_app", p2!!.toolName)
        assertEquals("yutub", p2.args["app_name"])
        val r2 = executor.execute(p2.toolName, p2.args)
        assertTrue("Expected yutub kholo to resolve YouTube: ${r2.output}", r2.ok)

        // Also ensure open_app normalizes alias keys and rejects literal "null"
        val nullOpen = executor.execute("open_app", mapOf("app" to "null"))
        assertFalse(nullOpen.ok)
        assertFalse(nullOpen.output.contains("null", ignoreCase = true))

        // Phrase 3: "Instagram pe Virat Kohli search karo"
        val igIntent = SearchPlayEngine.extractSearchPlayIntent("Instagram pe Virat Kohli search karo")
        assertNotNull(igIntent)
        assertEquals("instagram", igIntent!!.appKey)
        assertEquals("Virat Kohli", igIntent.query)
        assertFalse(igIntent.autoPlay)
        val igToolResult = extRouter.tryExecute(
            "search_in_app",
            mapOf("app" to "Instagram", "q" to "Virat Kohli", "auto_play" to false)
        )
        assertNotNull(igToolResult)
        // SearchPlayEngine resolves Instagram via AppResolver & opens it; in Robolectric AccessibilityService is off
        assertTrue("Expected Instagram AppResolver launch message: ${igToolResult!!.output}", igToolResult.output.contains("Instagram"))
        assertFalse(igToolResult.output.contains("null", ignoreCase = true))
        assertTrue(igToolResult.ok || igToolResult.output.contains("Accessibility Service"))

        // Also test YouTube search_in_app via DEEP_LINK_URI with alias keys
        val ytSearchResult = extRouter.tryExecute(
            "search_in_app",
            mapOf("app" to "YouTube", "q" to "lofi", "auto_play" to false)
        )
        assertNotNull(ytSearchResult)
        assertTrue("Expected YouTube search launch ok: ${ytSearchResult!!.output}", ytSearchResult.ok)
        assertTrue(ytSearchResult.output.contains("YouTube"))

        // Phrase 4: "Spotify pe lofi play karo"
        val spIntent = SearchPlayEngine.extractSearchPlayIntent("Spotify pe lofi play karo")
        assertNotNull(spIntent)
        assertEquals("spotify", spIntent!!.appKey)
        assertEquals("lofi", spIntent.query)
        assertTrue(spIntent.autoPlay)
        val spToolResult = extRouter.tryExecute(
            "search_in_app",
            mapOf("package" to "Spotify", "search_query" to "lofi", "auto_play" to true)
        )
        assertNotNull(spToolResult)
        assertTrue("Expected Spotify play launch to succeed: ${spToolResult!!.output}", spToolResult.ok)
        assertTrue(spToolResult.output.contains("Spotify"))

        safetyMgr.cancelEmergencyCountdown()
    }
}
