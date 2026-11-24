package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.PreferredBrain
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GroqApiService
import com.example.domain.ai.AgentStepTurn
import com.example.domain.ai.AiRouter
import com.example.domain.ai.BrainType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiRouterAndSanitizerTest {

    private lateinit var context: Context
    private lateinit var keyStore: SecureKeyStore
    private lateinit var moshi: Moshi

    private val capturedGroqBodies = mutableListOf<String>()
    private val capturedGeminiBodies = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyStore = SecureKeyStore(context)
        keyStore.clearApiKeys()
        keyStore.setAutoFallback(true)
        keyStore.updatePreferredBrain(PreferredBrain.GROQ_FIRST)
        keyStore.setBooleanPref(SecureKeyStore.KEY_DATA_SAVER, false)
        moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        capturedGroqBodies.clear()
        capturedGeminiBodies.clear()
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
        return AiRouter(geminiApi, groqApi, keyStore, moshi)
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
    fun `groq 429 rate limit automatically switches to gemini and preserves fallback note`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "AIzaTestGeminiKey1234567890abc", groqKey = "gsk_testGroqKey1234567890")
        val router = buildRouter(
            groqHandler = { req -> buildJsonResponse(req, 429, """{"error":{"message":"rate limit hit"}}""") },
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
    }

    @Test
    fun `error handling covers 401 invalid key, timeout, and no internet without leaking secrets`() = runBlocking {
        val secretGroq = "gsk_verySecretGroqKey9876543210"
        val secretGem = "AIzaVerySecretGeminiKey9876543210"
        keyStore.updateApiKeys(geminiKey = secretGem, groqKey = secretGroq)

        val authFailRouter = buildRouter(
            groqHandler = { req -> buildJsonResponse(req, 401, """{"error":{"message":"Invalid key $secretGroq"}}""") },
            geminiHandler = { req -> buildJsonResponse(req, 403, """{"error":{"message":"Denied $secretGem"}}""") }
        )
        val authRes = authFailRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Hi")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = null
        )
        assertTrue(authRes.isFailure)
        val authMsg = authRes.exceptionOrNull()?.message.orEmpty()
        assertFalse(authMsg.contains(secretGroq))
        assertFalse(authMsg.contains(secretGem))
        assertTrue(authMsg.contains("HTTP 401") || authMsg.contains("invalid", ignoreCase = true))

        val timeoutRouter = buildRouter(
            groqHandler = { throw SocketTimeoutException("read timed out $secretGroq") },
            geminiHandler = { throw UnknownHostException("Unable to resolve host $secretGem") }
        )
        val netRes = timeoutRouter.planNextStep(
            turns = listOf(AgentStepTurn(role = "user", text = "Hi")),
            screenContext = null, screenshotBase64 = null, stepIndex = 0, isLocked = false, userLanguageHint = null
        )
        assertTrue(netRes.isFailure)
        val netMsg = netRes.exceptionOrNull()?.message.orEmpty()
        assertTrue(netMsg.contains("timed out", ignoreCase = true))
        assertTrue(netMsg.contains("No internet", ignoreCase = true))
        assertFalse(netMsg.contains(secretGroq))
        assertFalse(netMsg.contains(secretGem))
    }

    @Test
    fun `malformed tool call json never crashes and returns empty args map`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "", groqKey = "gsk_testGroqKey1234567890")
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
                          "content": null,
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
        val calls = res.getOrThrow().toolCalls
        assertEquals(1, calls.size)
        assertEquals("toggle_flashlight", calls.first().name)
        assertTrue(calls.first().args.isEmpty())
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
    fun `whisper transcription parses success and handles errors cleanly`() = runBlocking {
        keyStore.updateApiKeys(geminiKey = "", groqKey = "gsk_testGroqKey1234567890")
        val okRouter = buildRouter(
            groqHandler = { req ->
                buildJsonResponse(req, 200, """{"text":"Hey Baby flashlight on karo","language":"hi","duration":1.4}""")
            },
            geminiHandler = { req -> buildJsonResponse(req, 500, "{}") }
        )
        val ok = okRouter.transcribeAudio(byteArrayOf(1, 2, 3, 4))
        assertTrue(ok.isSuccess)
        assertEquals("Hey Baby flashlight on karo", ok.getOrThrow().text)
        assertEquals("hi", ok.getOrThrow().detectedLanguage)

        val failRouter = buildRouter(
            groqHandler = { req -> buildJsonResponse(req, 429, """{"error":{"message":"slow down"}}""") },
            geminiHandler = { req -> buildJsonResponse(req, 500, "{}") }
        )
        val fail = failRouter.transcribeAudio(byteArrayOf(1, 2, 3))
        assertTrue(fail.isFailure)
        assertTrue(fail.exceptionOrNull()?.message.orEmpty().contains("429"))
    }
}
