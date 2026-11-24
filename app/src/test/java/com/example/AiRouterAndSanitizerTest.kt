package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.AiModelConfig
import com.example.core.security.PreferredBrain
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.local.BabyDatabase
import com.example.data.local.HistoryRepository
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GroqApiService
import com.example.domain.ai.AgentOrchestrator
import com.example.domain.ai.AgentStepTurn
import com.example.domain.ai.AiCallException
import com.example.domain.ai.AiResponseParser
import com.example.domain.ai.AiRouter
import com.example.domain.ai.BrainType
import com.example.domain.tools.ExtendedToolRouter
import com.example.domain.tools.PhoneControlExecutor
import com.example.domain.tools.StopSignalBus
import com.example.features.actionlog.ActionLogInterceptor
import com.example.features.actionlog.ActionLogRepository
import com.example.features.macros.MacroManager
import com.example.features.memory.MemoryManager
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.offline.OfflineCommandEngine
import com.example.features.reminders.ReminderManager
import com.example.features.routines.RoutineManager
import com.example.features.safety.SafetyManager
import com.example.features.vision.CameraVisionManager
import com.example.features.vision.ClipboardAiManager
import com.example.features.vision.ScreenHelpManager
import com.example.ui.state.ApiKeyHealthTester
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
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiRouterAndSanitizerTest {

    private lateinit var context: Context
    private lateinit var keyStore: SecureKeyStore
    private lateinit var moshi: Moshi

    private val capturedGroqBodies = mutableListOf<String>()
    private val capturedGeminiBodies = mutableListOf<String>()
    private val recordedSleepDelays = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyStore = SecureKeyStore(context)
        keyStore.clearApiKeys()
        keyStore.resetModelConfig()
        keyStore.setAutoFallback(true)
        keyStore.updatePreferredBrain(PreferredBrain.GROQ_FIRST)
        keyStore.setBooleanPref(SecureKeyStore.KEY_DATA_SAVER, false)
        moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        capturedGroqBodies.clear()
        capturedGeminiBodies.clear()
        recordedSleepDelays.clear()
    }

    private fun readRequestBody(req: Request): String {
        val copy = req.newBuilder().build()
        val buf = Buffer()
        copy.body?.writeTo(buf)
        return buf.readUtf8()
    }

    private fun buildJsonResponse(req: Request, code: Int, json: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Mock")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

    private fun buildRouter(
        groqHandler: (Request) -> Response,
        geminiHandler: (Request) -> Response
    ): AiRouter {
        val groqClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedGroqBodies += readRequestBody(req)
                groqHandler(req)
            })
            .build()
        val geminiClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedGeminiBodies += readRequestBody(req)
                geminiHandler(req)
            })
            .build()
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
        return AiRouter(geminiApi, groqApi, keyStore, moshi).apply {
            sleeper = { ms -> recordedSleepDelays += ms }
            jitterRatioProvider = { 0.0 }
        }
    }

    @Test
    fun `model config defaults use gpt-oss-120b, gpt-oss-20b, whisper-large-v3-turbo, gemini-2_5-flash and migrate legacy defaults`() {
        val cfg = AiModelConfig()
        assertEquals("openai/gpt-oss-120b", cfg.chatModel)
        assertEquals("openai/gpt-oss-20b", cfg.fastModel)
        assertEquals("whisper-large-v3-turbo", cfg.sttModel)
        assertEquals("gemini-2.5-flash", cfg.geminiModel)

        val prefs = context.getSharedPreferences("baby_secure_prefs_fallback", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("groq_model", "llama-3.3-70b-versatile")
            .putString("groq_fast_model", "llama-3.3-70b-versatile")
            .putString("gemini_model", "gemini-3.8-flash")
            .commit()

        val migratedStore = SecureKeyStore(context)
        val migrated = migratedStore.settings.value.modelConfig
        assertEquals(AiModelConfig.DEFAULT_CHAT_MODEL, migrated.chatModel)
        assertEquals(AiModelConfig.DEFAULT_FAST_MODEL, migrated.fastModel)
        assertEquals(AiModelConfig.DEFAULT_GEMINI_MODEL, migrated.geminiModel)
    }

    @Test
    fun `sensitive sanitizer redacts keys, tokens, otps, passwords, cards, and accounts`() {
        val raw = "AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz1234 gsk_abcdef1234567890abcdef " +
            "Bearer tok123.xyz password=mySecret99 482910 is your OTP " +
            "card 4111 2222 3333 4444 a/c 998877665544 IFSC SBIN0001234"
        val clean = SensitiveDataSanitizer.sanitizeForRemoteAi(raw)

        assertFalse(clean.contains("AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz1234"))
        assertFalse(clean.contains("gsk_abcdef1234567890abcdef"))
        assertFalse(clean.contains("tok123.xyz"))
        assertFalse(clean.contains("mySecret99"))
        assertFalse(clean.contains("482910"))
        assertFalse(clean.contains("4111 2222 3333 4444"))
        assertFalse(clean.contains("998877665544"))
        assertFalse(clean.contains("SBIN0001234"))
        assertTrue(clean.contains("[REDACTED_KEY]"))
        assertTrue(clean.contains("[REDACTED_OTP]"))
        assertTrue(clean.contains("[REDACTED_CARD]"))
        assertTrue(clean.contains("[REDACTED_ACCOUNT]"))
        assertTrue(SensitiveDataSanitizer.containsOnlyRedactedContent("[REDACTED_OTP] [REDACTED_CARD]"))
    }

    @Test
    fun `groq 404 model_not_found fetches live models, picks best priority replacement, saves it, retries once, and returns notice`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "", groqKey = "gsk_testGroqKey1234567890")
        keyStore.updateChatModel("broken-dead-model")
        val chatAttempts = AtomicInteger(0)

        val router = buildRouter(
            groqHandler = { req ->
                val path = req.url.encodedPath
                when {
                    path.endsWith("/openai/v1/models") -> {
                        buildJsonResponse(
                            req,
                            200,
                            """
                            {
                              "data": [
                                {"id": "whisper-large-v3-turbo", "active": true},
                                {"id": "llama-3.1-8b-instant", "active": true},
                                {"id": "qwen/qwen3.6-27b", "active": true}
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                    else -> {
                        val n = chatAttempts.incrementAndGet()
                        if (n == 1) {
                            buildJsonResponse(
                                req,
                                404,
                                """{"error":{"message":"The model `broken-dead-model` does not exist or you do not have access to it.","code":"model_not_found"}}"""
                            )
                        } else {
                            buildJsonResponse(
                                req,
                                200,
                                """{"choices":[{"message":{"role":"assistant","content":"Switched model se jawab ready!"}}]}"""
                            )
                        }
                    }
                }
            },
            geminiHandler = { req -> buildJsonResponse(req, 500, "{}") }
        )

        val res = router.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Hello Baby")),
            screenContext = null,
            screenshotBase64 = null,
            stepIndex = 0,
            isLocked = false,
            userLanguageHint = "hinglish"
        )

        assertTrue(res.isSuccess)
        val plan = res.getOrThrow()
        assertEquals(BrainType.GROQ, plan.brainUsed)
        assertEquals("Switched model se jawab ready!", plan.replyText)
        assertEquals("Model badal diya: qwen/qwen3.6-27b", plan.modelSwitchedNotice)
        assertEquals("qwen/qwen3.6-27b", keyStore.settings.value.modelConfig.chatModel)
        assertEquals(2, chatAttempts.get())
        // 404 is non-retryable (only 1 auto-recovery retry, zero exponential backoff sleeps)
        assertTrue(recordedSleepDelays.isEmpty())
    }

    @Test
    fun `groq 404 with no working live replacement fails clearly in Hinglish with model name and settings hint`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "", groqKey = "gsk_testGroqKey1234567890")
        keyStore.updateChatModel("dead-model-x")

        val router = buildRouter(
            groqHandler = { req ->
                if (req.url.encodedPath.endsWith("/openai/v1/models")) {
                    buildJsonResponse(req, 200, """{"data":[{"id":"qwen/qwen3.6-27b","active":true}]}""")
                } else {
                    buildJsonResponse(
                        req,
                        404,
                        """{"error":{"message":"model missing","code":"model_not_found"}}"""
                    )
                }
            },
            geminiHandler = { req -> buildJsonResponse(req, 500, "{}") }
        )

        val res = router.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Hi")),
            screenContext = null,
            screenshotBase64 = null,
            stepIndex = 0,
            isLocked = false,
            userLanguageHint = null
        )

        assertTrue(res.isFailure)
        val msg = res.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("dead-model-x"))
        assertTrue(msg.contains("qwen/qwen3.6-27b"))
        assertTrue(msg.contains("Settings > AI Models"))
        assertFalse(msg.contains("\"error\""))
    }

    @Test
    fun `groq 429 and 503 retry 3 times with 1s 2s 4s backoff then automatically fall back to gemini`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "AIzaTestGeminiKey1234567890abc", groqKey = "gsk_testGroqKey1234567890")
        val groqHits = AtomicInteger(0)
        val router = buildRouter(
            groqHandler = { req ->
                val hit = groqHits.incrementAndGet()
                val code = if (hit <= 2) 429 else 503
                buildJsonResponse(req, code, """{"error":{"message":"server busy / rate limit"}}""")
            },
            geminiHandler = { req ->
                buildJsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"Gemini ne handle kar liya!"}]}}]}"""
                )
            }
        )

        val res = router.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Hello Baby")),
            screenContext = null,
            screenshotBase64 = null,
            stepIndex = 0,
            isLocked = false,
            userLanguageHint = "hinglish"
        )

        assertTrue(res.isSuccess)
        val plan = res.getOrThrow()
        assertEquals(BrainType.GEMINI, plan.brainUsed)
        assertEquals("Gemini ne handle kar liya!", plan.replyText)
        assertNotNull(plan.fallbackNote)
        assertTrue(plan.fallbackNote!!.contains("Auto-switched from Groq"))
        assertEquals(4, groqHits.get())
        assertEquals(listOf(1_000L, 2_000L, 4_000L), recordedSleepDelays)
    }

    @Test
    fun `gemini 503 retries with backoff and recovers on 2nd retry`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "AIzaTestGeminiKey1234567890abc", groqKey = "")
        val gemHits = AtomicInteger(0)
        val router = buildRouter(
            groqHandler = { req -> buildJsonResponse(req, 500, "{}") },
            geminiHandler = { req ->
                if (gemHits.incrementAndGet() < 3) {
                    buildJsonResponse(req, 503, """{"error":{"message":"The model is overloaded","status":"UNAVAILABLE"}}""")
                } else {
                    buildJsonResponse(
                        req,
                        200,
                        """{"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"internal thoughts"},{"text":"503 ke baad recover ho gaya!"}]}}]}"""
                    )
                }
            }
        )

        val res = router.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Hello")),
            screenContext = null,
            screenshotBase64 = null,
            stepIndex = 0,
            isLocked = false,
            userLanguageHint = "hinglish"
        )

        assertTrue(res.isSuccess)
        assertEquals("503 ke baad recover ho gaya!", res.getOrThrow().replyText)
        assertEquals(3, gemHits.get())
        assertEquals(listOf(1_000L, 2_000L), recordedSleepDelays)
    }

    @Test
    fun `non-retryable 400 401 403 do not retry and never dump raw JSON or secrets, timeout retries and offline reports cleanly`() = runBlocking {
        val secretGroq = "gsk_verySecretGroqKey9876543210"
        val secretGem = "AIzaVerySecretGeminiKey9876543210"
        keyStore.updateApiKeys(geminiKey = secretGem, groqKey = secretGroq)

        val groqHits = AtomicInteger(0)
        val gemHits = AtomicInteger(0)
        val authFailRouter = buildRouter(
            groqHandler = { req ->
                groqHits.incrementAndGet()
                buildJsonResponse(req, 401, """{"error":{"message":"Invalid key $secretGroq"}}""")
            },
            geminiHandler = { req ->
                gemHits.incrementAndGet()
                buildJsonResponse(req, 403, """{"error":{"message":"Denied $secretGem"}}""")
            }
        )
        val authRes = authFailRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Hi")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = null
        )
        assertTrue(authRes.isFailure)
        assertEquals(1, groqHits.get())
        assertEquals(1, gemHits.get())
        assertTrue(recordedSleepDelays.isEmpty())
        val authEx = authRes.exceptionOrNull() as AiCallException
        val authMsg = authEx.message.orEmpty()
        assertFalse(authMsg.contains(secretGroq))
        assertFalse(authMsg.contains(secretGem))
        assertFalse(authMsg.contains("{\"error\""))
        assertTrue(authMsg.contains("401"))
        assertTrue(authMsg.contains("403"))

        recordedSleepDelays.clear()
        val timeoutHits = AtomicInteger(0)
        val timeoutRouter = buildRouter(
            groqHandler = {
                timeoutHits.incrementAndGet()
                throw SocketTimeoutException("read timed out $secretGroq")
            },
            geminiHandler = { throw UnknownHostException("Unable to resolve host $secretGem") }
        )
        val netRes = timeoutRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Hi")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = null
        )
        assertTrue(netRes.isFailure)
        assertEquals(4, timeoutHits.get())
        assertEquals(listOf(1_000L, 2_000L, 4_000L), recordedSleepDelays)
        val netMsg = netRes.exceptionOrNull()?.message.orEmpty()
        assertTrue(netMsg.contains("timeout", ignoreCase = true))
        assertTrue(netMsg.contains("internet connection nahi", ignoreCase = true))
        assertFalse(netMsg.contains(secretGroq))
        assertFalse(netMsg.contains(secretGem))
    }

    @Test
    fun `reasoning tags from gpt-oss and qwen are stripped and malformed tool call json or inline tool_call never crashes`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "", groqKey = "gsk_testGroqKey1234567890")

        val harmony = "<|channel|>analysis<|message|>Let me think carefully<|end|>" +
            "<|start|>assistant<|channel|>final<|message|>Flashlight on kar di!"
        assertEquals("Flashlight on kar di!", AiResponseParser.stripReasoning(harmony))

        val qwenThink = "<think>step 1 check\nstep 2 done</think>\nHaan ji, kar diya."
        assertEquals("Haan ji, kar diya.", AiResponseParser.stripReasoning(qwenThink))

        val unclosedThink = "<think>still thinking forever..."
        assertEquals(null, AiResponseParser.stripReasoning(unclosedThink))

        val router = buildRouter(
            groqHandler = { req ->
                buildJsonResponse(
                    req,
                    200,
                    """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "<think>Need to toggle flashlight now</think>",
                          "tool_calls": [{
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "toggle_flashlight",
                              "arguments": "```json\n{bad_json: [unclosed"
                            }
                          }]
                        }
                      }]
                    }
                    """.trimIndent()
                )
            },
            geminiHandler = { req -> buildJsonResponse(req, 500, "{}") }
        )

        assertEquals(emptyMap<String, Any?>(), router.parseToolArgsSafely("not json at all"))
        assertEquals(mapOf("enable" to true), router.parseToolArgsSafely("```json\n{\"enable\":true}\n```"))

        val res = router.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Torch")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = null
        )
        assertTrue(res.isSuccess)
        val plan = res.getOrThrow()
        assertEquals(null, plan.replyText)
        assertEquals(1, plan.toolCalls.size)
        assertEquals("toggle_flashlight", plan.toolCalls.first().name)
        assertTrue(plan.toolCalls.first().args.isEmpty())

        // Also test inline <tool_call> fallback when tool_calls array is absent
        val inlineRouter = buildRouter(
            groqHandler = { req ->
                buildJsonResponse(
                    req,
                    200,
                    """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "<think>calling volume</think><tool_call>{\"name\":\"set_volume\",\"arguments\":{\"stream\":\"media\",\"level_percent\":50}}</tool_call>Volume badha rahi hoon."
                        }
                      }]
                    }
                    """.trimIndent()
                )
            },
            geminiHandler = { req -> buildJsonResponse(req, 500, "{}") }
        )
        val inlineRes = inlineRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Sound half")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = null
        ).getOrThrow()
        assertEquals(1, inlineRes.toolCalls.size)
        assertEquals("set_volume", inlineRes.toolCalls.first().name)
        assertEquals(50.0, (inlineRes.toolCalls.first().args["level_percent"] as Number).toDouble(), 0.01)
        assertEquals("Volume badha rahi hoon.", inlineRes.replyText)
    }

    @Test
    fun `when both groq and gemini fail, AgentOrchestrator falls back to offline command handling for alarm or flash and explains`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "AIzaTestGeminiKey1234567890abc", groqKey = "gsk_testGroqKey1234567890")
        keyStore.setBooleanPref(SecureKeyStore.KEY_OFFLINE_COMMANDS, true)

        val db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val permHelper = PermissionHelper(context)
        val ttsManager = BabyTtsManager(context)
        val stopBus = StopSignalBus()
        val actionRepo = ActionLogRepository(db.actionLogDao(), keyStore)
        val actionInterceptor = ActionLogInterceptor(actionRepo)
        val historyRepo = HistoryRepository(db.chatDao(), db.taskRunDao(), db.queuedTaskDao())
        val memoryMgr = MemoryManager(db.memoryDao(), keyStore)
        val executor = PhoneControlExecutor(context, permHelper, ttsManager, keyStore, db.allowedAppDao(), db.memoryDao())
        val offlineEngine = OfflineCommandEngine(context, keyStore, executor)

        val failingGroqClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> buildJsonResponse(chain.request(), 401, """{"error":{"message":"bad key"}}""") })
            .build()
        val failingGeminiClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> buildJsonResponse(chain.request(), 403, """{"error":{"message":"forbidden"}}""") })
            .build()
        val groqApi = Retrofit.Builder().baseUrl("https://api.groq.com/").client(failingGroqClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(GroqApiService::class.java)
        val geminiApi = Retrofit.Builder().baseUrl("https://generativelanguage.googleapis.com/").client(failingGeminiClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(GeminiApiService::class.java)

        val router = AiRouter(geminiApi, groqApi, keyStore, moshi).apply {
            sleeper = { recordedSleepDelays += it }
            jitterRatioProvider = { 0.0 }
        }
        val notifMgr = NotificationSummaryManager(context, keyStore, groqApi, ttsManager)
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
            actionLogInterceptor = actionInterceptor,
            offlineCommandEngine = offlineEngine,
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

        try {
            // "wake me up at 7:30 am" bypasses quick-local and only matches offlineEngine in fallbackMode=true
            orchestrator.submitUserCommand("please wake me up at 7:30 am")
            withTimeout(5000L) {
                orchestrator.state.first { !it.isRunning && it.pendingConfirmation == null }
            }
            val latestRun = db.taskRunDao().observeAllTaskRuns().first().first()
            assertEquals("COMPLETED", latestRun.status)
            assertEquals("Offline Fallback", latestRun.brainSummary)
            assertTrue(latestRun.finalReply.contains("offline mode", ignoreCase = true))
        } finally {
            safetyMgr.cancelEmergencyCountdown()
            db.close()
        }
    }

    @Test
    fun `outbound prompt redacts otp and data saver strips screenshots`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "AIzaTestGeminiKey1234567890abc", groqKey = "")
        keyStore.setBooleanPref(SecureKeyStore.KEY_DATA_SAVER, true)
        val router = buildRouter(
            groqHandler = { req -> buildJsonResponse(req, 500, "{}") },
            geminiHandler = { req ->
                buildJsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"Done"}]}}]}"""
                )
            }
        )

        val res = router.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "My OTP is 739104 and password=TopSecret")),
            screenContext = "ActiveApp: com.example\n\"OTP is 739104\"",
            screenshotBase64 = "FAKE_BASE64_SCREENSHOT_BYTES",
            stepIndex = 0,
            isLocked = false,
            userLanguageHint = "hinglish"
        )
        assertTrue(res.isSuccess)
        val sentBody = capturedGeminiBodies.single()
        assertFalse(sentBody.contains("739104"))
        assertFalse(sentBody.contains("TopSecret"))
        assertFalse(sentBody.contains("FAKE_BASE64_SCREENSHOT_BYTES"))
        assertFalse(sentBody.contains("inlineData"))
    }

    @Test
    fun `whisper transcription parses success, auto-recovers 404, and handles 429 cleanly`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "", groqKey = "gsk_testGroqKey1234567890")
        val sttAttempts = AtomicInteger(0)
        val recoverRouter = buildRouter(
            groqHandler = { req ->
                if (req.url.encodedPath.endsWith("/openai/v1/models")) {
                    buildJsonResponse(req, 200, """{"data":[{"id":"whisper-large-v3","active":true}]}""")
                } else if (sttAttempts.incrementAndGet() == 1) {
                    buildJsonResponse(req, 404, """{"error":{"message":"model missing","code":"model_not_found"}}""")
                } else {
                    buildJsonResponse(req, 200, """{"text":"Hey Baby flashlight on karo","language":"hi","duration":1.4}""")
                }
            },
            geminiHandler = { req -> buildJsonResponse(req, 500, "{}") }
        )
        val ok = recoverRouter.transcribeAudio(byteArrayOf(1, 2, 3, 4))
        assertTrue(ok.isSuccess)
        assertEquals("Hey Baby flashlight on karo", ok.getOrThrow().text)
        assertEquals("hi", ok.getOrThrow().detectedLanguage)
        assertEquals("Model badal diya: whisper-large-v3", ok.getOrThrow().modelSwitchedNotice)

        val failRouter = buildRouter(
            groqHandler = { req -> buildJsonResponse(req, 429, """{"error":{"message":"slow down"}}""") },
            geminiHandler = { req -> buildJsonResponse(req, 500, "{}") }
        )
        val fail = failRouter.transcribeAudio(byteArrayOf(1, 2, 3))
        assertTrue(fail.isFailure)
        assertTrue(fail.exceptionOrNull()?.message.orEmpty().contains("429"))
    }

    @Test
    fun `api key health tester fetches live groq models and runs tiny pass-fail tests across all configured models`() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                when {
                    path.endsWith("/openai/v1/models") -> {
                        buildJsonResponse(
                            req,
                            200,
                            """{"data":[{"id":"openai/gpt-oss-120b","active":true},{"id":"qwen/qwen3.6-27b","active":true},{"id":"whisper-large-v3-turbo","active":true}]}"""
                        )
                    }
                    path.endsWith("/openai/v1/chat/completions") -> {
                        val body = readRequestBody(req)
                        if (body.contains("gpt-oss-20b")) {
                            buildJsonResponse(req, 404, """{"error":{"message":"model missing","code":"model_not_found"}}""")
                        } else {
                            buildJsonResponse(req, 200, """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""")
                        }
                    }
                    path.endsWith("/openai/v1/audio/transcriptions") -> {
                        buildJsonResponse(req, 200, """{"text":""}""")
                    }
                    path.contains(":generateContent") -> {
                        buildJsonResponse(req, 200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"OK"}]}}]}""")
                    }
                    else -> buildJsonResponse(req, 404, "{}")
                }
            })
            .build()

        val tester = ApiKeyHealthTester(mockClient)
        val live = tester.fetchGroqModels("gsk_demo123").getOrThrow()
        assertTrue(live.contains("openai/gpt-oss-120b"))
        assertTrue(live.contains("qwen/qwen3.6-27b"))

        val results = tester.testAllModels(
            groqKey = "gsk_demo123",
            geminiKey = "AIzaDemo123",
            config = AiModelConfig()
        )
        assertEquals(4, results.items.size)
        assertEquals(true, results.items[0].passed) // Chat / Tools
        assertEquals(false, results.items[1].passed) // Fast / Simple returned 404
        assertTrue(results.items[1].detail.contains("404"))
        assertEquals(true, results.items[2].passed) // STT
        assertEquals(true, results.items[3].passed) // Gemini
    }
}
