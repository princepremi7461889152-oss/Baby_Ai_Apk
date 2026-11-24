package com.example.features.vision

import com.example.core.security.GroqModelRole
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiInlineData
import com.example.data.remote.GeminiPart
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqMessage
import com.example.domain.ai.AiResilienceHelper
import com.example.domain.ai.AiResponseParser
import com.example.domain.tools.ToolExecutionResult
import com.example.features.safety.SafetyManager
import com.example.service.BabyAccessibilityService
import com.example.util.PermissionHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2 Module 3: Screen Help
 * Handles "Ye kya hai?", "isko translate karo", "isko summarize karo".
 * Always inspects Accessibility UI tree FIRST; captures screenshot for Gemini Vision
 * ONLY if the text tree does not contain enough information AND Data Saver is OFF.
 */
@Singleton
class ScreenHelpManager @Inject constructor(
    private val keyStore: SecureKeyStore,
    private val permissionHelper: PermissionHelper,
    private val groqApi: GroqApiService,
    private val geminiApi: GeminiApiService
) {
    suspend fun explainScreen(
        mode: String = "explain",
        targetLanguage: String? = null,
        extraQuestion: String? = null
    ): ToolExecutionResult {
        val settings = keyStore.settings.value
        if (!settings.screenHelpEnabled) {
            return ToolExecutionResult(false, "Screen Help Settings mein OFF hai.")
        }
        if (permissionHelper.isDeviceLocked()) {
            return ToolExecutionResult(false, "Phone locked hai — pehle unlock karo phir screen padh sakti hoon.")
        }

        val accessibility = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "Accessibility Service ON nahi hai, isliye screen nahi dekh pa rahi hoon.")

        val activePkg = accessibility.getActivePackageName()
        SafetyManager.isBlockedScreen(activePkg, null)?.let { blocked ->
            return ToolExecutionResult(false, "Safety guard: $blocked screen ko Baby read ya screenshot nahi karegi.")
        }

        val uiTree = accessibility.readUiTree(maxNodes = 120)
        SafetyManager.isBlockedScreen(activePkg, uiTree)?.let { blocked ->
            return ToolExecutionResult(false, "Safety guard: $blocked screen ko Baby read ya screenshot nahi karegi.")
        }
        val normMode = normalizeMode(mode)
        val langLabel = targetLanguage?.takeIf { it.isNotBlank() } ?: settings.preferredLanguage.displayName
        val extractedLines = extractReadableText(uiTree)
        val treeEnough = hasSufficientTextTree(uiTree, extractedLines)

        // Strict tree-first + Data Saver rule
        val useVisionScreenshot = !treeEnough && !settings.dataSaverEnabled && settings.geminiApiKey.isNotBlank()

        val taskPrompt = buildTaskInstruction(normMode, langLabel, extraQuestion)
        val rawPayload = extractedLines.take(35).joinToString("\n").ifBlank { uiTree.take(1500) }
        val minimalTextPayload = SensitiveDataSanitizer.sanitizeForRemoteAi(rawPayload)

        if (!useVisionScreenshot) {
            val textAnswer = runTextBrain(taskPrompt, minimalTextPayload)
            if (textAnswer != null) {
                val dataNote = if (!treeEnough && settings.dataSaverEnabled) {
                    " (Data Saver ON: text tree only)"
                } else ""
                return ToolExecutionResult(true, "$textAnswer$dataNote")
            }
            if (settings.dataSaverEnabled) {
                return ToolExecutionResult(
                    true,
                    "Screen par dikhne wala text (Data Saver mode):\n${minimalTextPayload.take(350)}"
                )
            }
        }

        // Only capture screenshot if text tree wasn't enough and Data Saver is OFF
        val screenshotB64 = accessibility.takeScreenshotBase64()
            ?: return ToolExecutionResult(
                true,
                if (minimalTextPayload.isNotBlank()) "Screen text: ${minimalTextPayload.take(350)}"
                else "Screen screenshot capture nahi ho paya (Android 11+ chahiye ya text tree limited hai)."
            )

        val visionAnswer = callGeminiVision(
            prompt = "$taskPrompt\nOptional accessibility labels:\n${minimalTextPayload.take(500)}",
            jpegBase64 = screenshotB64
        )
        return if (visionAnswer != null) {
            ToolExecutionResult(true, visionAnswer)
        } else {
            val fallback = runTextBrain(taskPrompt, minimalTextPayload)
                ?: "Screen read nahi ho payi — Gemini/Groq API key check karo."
            ToolExecutionResult(false, fallback)
        }
    }

    private suspend fun runTextBrain(taskPrompt: String, screenText: String): String? {
        val settings = keyStore.settings.value
        val userContent = SensitiveDataSanitizer.sanitizeForRemoteAi("$taskPrompt\n\nVisible Screen Content:\n$screenText")

        if (settings.groqApiKey.isNotBlank()) {
            val groqAns = AiResilienceHelper.executeGroqChatWithRecovery(
                groqApi = groqApi,
                keyStore = keyStore,
                key = settings.groqApiKey.trim(),
                initialModel = settings.modelConfig.fastModel,
                role = GroqModelRole.FAST,
                requestBuilder = { modelId ->
                    GroqChatRequest(
                        model = modelId,
                        messages = listOf(
                            GroqMessage(
                                role = "system",
                                content = "You are Baby, a friendly Hinglish phone assistant. Answer concisely in 2-4 natural Hinglish sentences based ONLY on the provided screen text."
                            ),
                            GroqMessage(role = "user", content = userContent)
                        ),
                        tools = null,
                        toolChoice = null
                    )
                }
            ).getOrNull()?.value?.choices?.firstOrNull()?.message?.content
                ?.let { AiResponseParser.stripReasoning(it) }
            if (!groqAns.isNullOrBlank()) return groqAns
        }

        if (settings.geminiApiKey.isNotBlank()) {
            return AiResilienceHelper.executeGeminiWithRetry(
                geminiApi = geminiApi,
                key = settings.geminiApiKey.trim(),
                model = settings.modelConfig.geminiModel,
                request = GeminiGenerateRequest(
                    systemInstruction = GeminiContent(
                        parts = listOf(GeminiPart(text = "You are Baby. Answer concisely in friendly Hinglish."))
                    ),
                    contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = userContent))))
                )
            ).getOrNull()?.candidates?.firstOrNull()?.content?.parts
                ?.let { AiResilienceHelper.extractGeminiVisibleText(it) }
        }
        return null
    }

    private suspend fun callGeminiVision(prompt: String, jpegBase64: String): String? {
        val settings = keyStore.settings.value
        val key = settings.geminiApiKey.trim()
        if (key.isBlank()) return null

        return AiResilienceHelper.executeGeminiWithRetry(
            geminiApi = geminiApi,
            key = key,
            model = settings.modelConfig.geminiModel,
            request = GeminiGenerateRequest(
                systemInstruction = GeminiContent(
                    parts = listOf(
                        GeminiPart(text = "You are Baby, a friendly Hinglish assistant. Explain, translate, or summarize what is shown on the user's phone screen concisely.")
                    )
                ),
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(
                            GeminiPart(text = SensitiveDataSanitizer.sanitizeForRemoteAi(prompt)),
                            GeminiPart(inlineData = GeminiInlineData("image/jpeg", jpegBase64))
                        )
                    )
                )
            )
        ).getOrNull()?.candidates?.firstOrNull()?.content?.parts
            ?.let { AiResilienceHelper.extractGeminiVisibleText(it) }
    }

    private fun buildTaskInstruction(mode: String, targetLang: String, extraQuestion: String?): String {
        val base = when (mode) {
            "translate" -> "Translate all visible main text on the screen clearly into $targetLang (simple Hinglish/Hindi if unspecified)."
            "summarize" -> "Summarize the key points visible on this screen in 2-3 short Hinglish bullet points or sentences."
            else -> "Explain clearly in simple Hinglish what is currently shown on this screen and what the user can do next."
        }
        return if (!extraQuestion.isNullOrBlank()) "$base\nUser asked: $extraQuestion" else base
    }

    companion object {
        private val QUOTED_OR_PARENS_REGEX = Regex(""""([^"]{2,})"|\(([^)]{2,})\)""")

        fun normalizeMode(raw: String?): String {
            val l = raw?.lowercase().orEmpty()
            return when {
                l.contains("translat") || l.contains("anuvad") -> "translate"
                l.contains("summar") || l.contains("short") -> "summarize"
                else -> "explain"
            }
        }

        fun extractReadableText(uiTree: String): List<String> {
            val out = linkedSetOf<String>()
            uiTree.lineSequence().forEach { line ->
                if (line.startsWith("ActiveApp:")) {
                    out.add(line)
                } else {
                    QUOTED_OR_PARENS_REGEX.findAll(line).forEach { m ->
                        val chunk = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
                        if (chunk.length >= 2) out.add(chunk)
                    }
                }
            }
            return out.toList()
        }

        fun hasSufficientTextTree(uiTree: String, extractedLines: List<String> = extractReadableText(uiTree)): Boolean {
            if (uiTree.isBlank() || uiTree.contains("No active window visible")) return false
            val textOnly = extractedLines.filterNot { it.startsWith("ActiveApp:") }
            val totalChars = textOnly.sumOf { it.length }
            val activePkg = uiTree.lineSequence().firstOrNull { it.startsWith("ActiveApp:") }.orEmpty().lowercase()
            val imageHeavyApp = listOf("gallery", "photos", "camera", "youtube", "instagram", "netflix", "mxplayer")
                .any { activePkg.contains(it) }
            if (imageHeavyApp && totalChars < 180) return false
            return textOnly.size >= 3 && totalChars >= 65
        }
    }
}
