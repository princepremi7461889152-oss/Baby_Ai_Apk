package com.example.features.vision

import android.content.ClipboardManager
import android.content.Context
import com.example.core.security.GroqModelRole
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiPart
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqMessage
import com.example.domain.ai.AiResilienceHelper
import com.example.domain.ai.AiResponseParser
import com.example.domain.tools.ToolExecutionResult
import com.example.voice.BabyTtsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2 Module 4: Clipboard AI
 * Reads the system clipboard ONLY when explicitly invoked by the user (voice/text/bubble),
 * NEVER via background clipboard listeners.
 */
@Singleton
class ClipboardAiManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val groqApi: GroqApiService,
    private val geminiApi: GeminiApiService,
    private val ttsManager: BabyTtsManager
) {
    suspend fun readClipboardText(): String? = withContext(Dispatchers.Main) {
        if (!keyStore.settings.value.clipboardAiEnabled) return@withContext null
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@withContext null
        val clip = cm.primaryClip ?: return@withContext null
        if (clip.itemCount == 0) return@withContext null
        clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun processClipboard(
        mode: String = "summarize",
        targetLanguage: String? = null,
        explicitText: String? = null,
        speakResult: Boolean = false
    ): ToolExecutionResult {
        val settings = keyStore.settings.value
        if (!settings.clipboardAiEnabled) {
            return ToolExecutionResult(false, "Clipboard AI Settings mein OFF hai.")
        }

        val normMode = normalizeMode(mode)
        val clipText = explicitText?.trim()?.takeIf { it.isNotEmpty() }
            ?: readClipboardText()
            ?: run {
                if (explicitText == null) {
                    ClipboardActionActivity.launch(context, mode = normMode)
                    return ToolExecutionResult(true, "Clipboard AI sheet khol di hai — wahan aapka copied text $normMode ho jayega.")
                }
                return ToolExecutionResult(false, "Clipboard abhi khaali hai.")
            }

        val cappedText = SensitiveDataSanitizer.sanitizeForRemoteAi(
            clipText.take(if (settings.dataSaverEnabled) 1000 else 2500)
        )
        if (SensitiveDataSanitizer.containsOnlyRedactedContent(cappedText)) {
            return ToolExecutionResult(
                false,
                "Privacy guard: Clipboard mein sirf sensitive data (OTP/key/card/password) tha, isliye AI ko nahi bheja."
            )
        }

        val lang = targetLanguage?.takeIf { it.isNotBlank() } ?: "Hinglish"

        val instruction = when (normMode) {
            "translate" -> "Translate the copied text naturally into $lang in simple clear wording."
            "draft_reply" -> "Draft a short, polite, ready-to-send reply to this copied message in Hinglish (or match its language). Give only the suggested reply plus 1 short note if needed."
            "explain" -> "Explain what this copied text means in 2 short Hinglish sentences."
            else -> "Summarize this copied text into 2-3 crisp Hinglish bullet points or sentences."
        }

        val prompt = "$instruction\n\nCopied Text:\n$cappedText"
        val aiResult = callGroq(prompt) ?: callGeminiText(prompt)

        val output = aiResult ?: "Clipboard text read hua (${cappedText.take(120)}...), lekin AI summary ke liye Groq/Gemini key check karo."
        if (speakResult && settings.ttsRepliesEnabled) {
            ttsManager.speak(output, "hinglish")
        }
        return ToolExecutionResult(aiResult != null, output)
    }

    private suspend fun callGroq(prompt: String): String? {
        val settings = keyStore.settings.value
        val key = settings.groqApiKey.trim()
        if (key.isBlank()) return null
        return AiResilienceHelper.executeGroqChatWithRecovery(
            groqApi = groqApi,
            keyStore = keyStore,
            key = key,
            initialModel = settings.modelConfig.fastModel,
            role = GroqModelRole.FAST,
            requestBuilder = { modelId ->
                GroqChatRequest(
                    model = modelId,
                    messages = listOf(
                        GroqMessage(
                            role = "system",
                            content = "You are Baby, a friendly Hinglish phone assistant helping with copied clipboard text."
                        ),
                        GroqMessage(role = "user", content = prompt)
                    ),
                    tools = null,
                    toolChoice = null
                )
            }
        ).getOrNull()?.value?.choices?.firstOrNull()?.message?.content
            ?.let { AiResponseParser.stripReasoning(it) }
    }

    private suspend fun callGeminiText(prompt: String): String? {
        val settings = keyStore.settings.value
        val key = settings.geminiApiKey.trim()
        if (key.isBlank()) return null
        return AiResilienceHelper.executeGeminiWithRetry(
            geminiApi = geminiApi,
            key = key,
            model = settings.modelConfig.geminiModel,
            request = GeminiGenerateRequest(
                systemInstruction = GeminiContent(
                    parts = listOf(GeminiPart(text = "You are Baby, a friendly Hinglish phone assistant."))
                ),
                contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = prompt))))
            )
        ).getOrNull()?.candidates?.firstOrNull()?.content?.parts
            ?.let { AiResilienceHelper.extractGeminiVisibleText(it) }
    }

    companion object {
        fun normalizeMode(raw: String?): String {
            val l = raw?.lowercase().orEmpty()
            return when {
                l.contains("reply") || l.contains("draft") || l.contains("jawab") -> "draft_reply"
                l.contains("translat") || l.contains("anuvad") -> "translate"
                l.contains("explain") || l.contains("samjha") -> "explain"
                else -> "summarize"
            }
        }
    }
}
