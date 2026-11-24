package com.example.features.vision

import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiInlineData
import com.example.data.remote.GeminiPart
import com.example.domain.ai.AiCallException
import com.example.domain.ai.AiResilienceHelper
import com.example.domain.tools.ToolExecutionResult
import com.example.util.PermissionHelper
import com.example.voice.BabyTtsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2 Module 5: Camera + Vision
 * Opens quick one-shot CameraCaptureActivity (saving ONLY to temp cacheDir, never Gallery),
 * sends that single photo + question to Gemini Vision, speaks the Hinglish answer,
 * and ensures all temp image data is discarded right after the API call.
 */
@Singleton
class CameraVisionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val permissionHelper: PermissionHelper,
    private val geminiApi: GeminiApiService,
    private val ttsManager: BabyTtsManager
) {
    suspend fun captureAndDescribe(
        question: String = "Ye kya hai? Hinglish mein short aur clear batao."
    ): ToolExecutionResult {
        val settings = keyStore.settings.value
        if (!settings.cameraVisionEnabled) {
            return ToolExecutionResult(false, "Camera + Vision Settings mein OFF hai.")
        }
        if (permissionHelper.isDeviceLocked()) {
            return ToolExecutionResult(false, "Phone locked hai — camera use karne ke liye pehle unlock karo.")
        }
        if (settings.dataSaverEnabled) {
            return ToolExecutionResult(false, "Data Saver mode ON hai, isliye camera photo Gemini Vision par nahi bhej rahi.")
        }
        val key = settings.geminiApiKey.trim()
        if (key.isBlank()) {
            return ToolExecutionResult(false, "Camera Vision ke liye Gemini API key Settings mein add karo.")
        }

        // Capture one photo to cacheDir; CameraCaptureActivity deletes the file right after encoding
        var base64Photo: String? = CameraCaptureActivity.captureOnePhotoBase64(context)
            ?: return ToolExecutionResult(false, "Camera photo capture cancel ho gaya ya camera permission missing hai.")

        return try {
            val promptText = SensitiveDataSanitizer.sanitizeForRemoteAi(
                question.ifBlank { "Ye kya hai? Hinglish mein short aur helpful jawab do." }
            )
            val photoData = base64Photo ?: return ToolExecutionResult(false, "Camera image missing.")
            val res = AiResilienceHelper.executeGeminiWithRetry(
                geminiApi = geminiApi,
                key = key,
                model = settings.modelConfig.geminiModel,
                request = GeminiGenerateRequest(
                    systemInstruction = GeminiContent(
                        parts = listOf(
                            GeminiPart(text = "You are Baby, a friendly Hinglish-speaking assistant. Identify what is in the user's photo and answer their question in 2-3 crisp Hinglish sentences. Never mention model names.")
                        )
                    ),
                    contents = listOf(
                        GeminiContent(
                            role = "user",
                            parts = listOf(
                                GeminiPart(text = promptText),
                                GeminiPart(inlineData = GeminiInlineData("image/jpeg", photoData))
                            )
                        )
                    )
                )
            )

            // Immediately release base64 payload reference after API call
            base64Photo = null

            val body = res.getOrElse { err ->
                val friendly = (err as? AiCallException)?.failure?.userMessage
                    ?: "Camera Vision fail ho gaya. Settings > AI Models ya network check karo."
                return ToolExecutionResult(false, friendly)
            }

            val parts = body.candidates?.firstOrNull()?.content?.parts.orEmpty()
            val answer = AiResilienceHelper.extractGeminiVisibleText(parts)
                ?: "Photo samajh nahi aayi, dobara try karo."

            if (settings.ttsRepliesEnabled) {
                ttsManager.speak(answer, "hinglish")
            }
            ToolExecutionResult(true, answer)
        } finally {
            base64Photo = null
            CameraCaptureActivity.purgeTempCacheFiles(context)
        }
    }
}
