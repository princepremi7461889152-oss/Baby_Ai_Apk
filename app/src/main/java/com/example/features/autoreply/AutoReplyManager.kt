package com.example.features.autoreply

import android.Manifest
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.example.core.security.AutoReplyMode
import com.example.core.security.GroqModelRole
import com.example.core.security.SecureKeyStore
import com.example.core.security.SensitiveDataSanitizer
import com.example.data.local.AutoReplyContactRuleEntity
import com.example.data.local.AutoReplyRuleDao
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiPart
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqMessage
import com.example.data.remote.MercuryApiService
import com.example.data.remote.MercuryChatRequest
import com.example.domain.ai.AiResilienceHelper
import com.example.domain.ai.AiResponseParser
import com.example.features.actionlog.ActionLogRepository
import com.example.util.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoReplyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val ruleDao: AutoReplyRuleDao,
    private val groqApi: GroqApiService,
    private val geminiApi: GeminiApiService,
    private val actionLog: ActionLogRepository,
    private val permissionHelper: PermissionHelper,
    private val mercuryApi: MercuryApiService? = null
) {
    constructor(
        context: Context,
        keyStore: SecureKeyStore,
        ruleDao: AutoReplyRuleDao,
        groqApi: GroqApiService,
        geminiApi: GeminiApiService,
        actionLog: ActionLogRepository,
        permissionHelper: PermissionHelper
    ) : this(context, keyStore, ruleDao, groqApi, geminiApi, actionLog, permissionHelper, null)

    val rules: Flow<List<AutoReplyContactRuleEntity>> = ruleDao.observeAll()

    suspend fun upsertContactRule(displayName: String, ruleType: String) {
        val clean = displayName.trim()
        if (clean.isBlank()) return
        ruleDao.upsert(
            AutoReplyContactRuleEntity(
                contactKey = normalizeKey(clean),
                displayName = clean,
                ruleType = ruleType.uppercase()
            )
        )
    }

    suspend fun deleteRule(contactKey: String) = ruleDao.deleteByKey(contactKey)

    suspend fun handleIncomingNotification(
        packageName: String,
        appLabel: String,
        senderTitle: String,
        messageText: String,
        replyAction: Notification.Action?,
        remoteInput: RemoteInput?,
        isGroup: Boolean = false
    ) {
        val settings = keyStore.settings.value
        if (!settings.autoReplyEnabled || settings.autoReplyMode == AutoReplyMode.OFF) return
        if (senderTitle.isBlank()) return
        if (settings.autoReplyIgnoreGroups && isGroup) return

        if (settings.autoReplyScheduleEnabled &&
            !isWithinSchedule(
                settings.autoReplyStartHour,
                settings.autoReplyStartMinute,
                settings.autoReplyEndHour,
                settings.autoReplyEndMinute
            )
        ) {
            return
        }

        val isSmsOrCall = packageName.contains("mms") ||
            packageName.contains("messaging") ||
            packageName.contains("dialer") ||
            packageName.contains("telecom")
        val isMessagingOrCall = packageName.contains("whatsapp") ||
            isSmsOrCall ||
            replyAction != null
        if (!isMessagingOrCall) return

        val key = normalizeKey(senderTitle)
        val existingRule = ruleDao.getByKey(key)
        if (existingRule != null && (existingRule.ruleType == "BLOCK" || existingRule.ruleType == "NEVER")) {
            return
        }

        val allRules = ruleDao.getAll()
        val hasAllowList = allRules.any { it.ruleType == "ALLOW" }
        if (hasAllowList && existingRule?.ruleType != "ALLOW") {
            return
        }

        // Strict 1 reply per contact per hour rate limit
        val now = System.currentTimeMillis()
        if (existingRule != null && (now - existingRule.lastRepliedAt) < ONE_HOUR_MS) {
            return
        }

        val replyBody = buildReplyText(settings.activeAutoReplyTemplate(), senderTitle, messageText)
        if (replyBody.isBlank()) return

        var sentOk = false
        var resultNote = ""
        if (replyAction != null && remoteInput != null) {
            sentOk = runCatching {
                val intent = Intent()
                val bundle = Bundle().apply {
                    putCharSequence(remoteInput.resultKey, replyBody)
                }
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                replyAction.actionIntent.send(context, 0, intent)
                true
            }.getOrElse {
                resultNote = "Inline reply failed: ${SensitiveDataSanitizer.sanitizeError(it.message)}"
                false
            }
            if (sentOk) resultNote = "Auto-replied via $appLabel (template/AI, ${replyBody.length} chars)"
        } else if (isSmsOrCall && permissionHelper.hasPermission(Manifest.permission.SEND_SMS)) {
            val number = resolveSenderNumber(senderTitle)
            if (!number.isNullOrBlank()) {
                sentOk = sendDirectSms(number, replyBody)
                resultNote = if (sentOk) {
                    "Auto-replied via SMS ($appLabel) to $senderTitle (${replyBody.length} chars)"
                } else {
                    "SMS auto-reply failed for $senderTitle"
                }
            } else {
                resultNote = "Missed call/SMS from $senderTitle (no number resolved for SMS reply)"
            }
        } else {
            resultNote = "Missed call/notification for $senderTitle (no inline reply action)"
        }

        if (sentOk) {
            ruleDao.upsert(
                AutoReplyContactRuleEntity(
                    contactKey = key,
                    displayName = existingRule?.displayName ?: senderTitle,
                    ruleType = existingRule?.ruleType ?: "AUTO",
                    lastRepliedAt = now
                )
            )
            actionLog.record(
                commandText = "Auto-reply (${settings.autoReplyMode.label})",
                toolName = "auto_reply",
                argsSummary = "to=$senderTitle, app=$appLabel",
                resultText = resultNote,
                targetApp = appLabel,
                status = "AUTO_REPLY",
                source = "auto-reply"
            )
        }
    }

    private fun sendDirectSms(phoneNumber: String, body: String): Boolean {
        return runCatching {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return false
            val parts = smsManager.divideMessage(body)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            true
        }.getOrDefault(false)
    }

    private fun resolveSenderNumber(senderTitle: String): String? {
        val cleaned = Regex("""[+]?[0-9][0-9\s\-()]{6,}""").find(senderTitle)?.value?.trim()
        if (!cleaned.isNullOrBlank()) return cleaned
        if (!permissionHelper.hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val q = senderTitle.trim()
        if (q.isBlank()) return null
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                null
            )?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return null
    }

    private suspend fun buildReplyText(
        template: String,
        sender: String,
        incomingText: String
    ): String {
        val settings = keyStore.settings.value
        if (!settings.autoReplyAiEnabled || settings.dataSaverEnabled || !settings.hasAnyApiKey) {
            return template
        }
        val safeIncoming = SensitiveDataSanitizer.sanitizeForRemoteAi(incomingText.take(280))
        val systemText = "You are Baby, an AI assistant. Generate only the short 1-sentence Hinglish auto-reply text matching the user's tone. Never reveal any model names."
        val prompt = "Mode: ${settings.autoReplyMode.label}. Base message: \"$template\". Sender: $sender wrote: \"$safeIncoming\". Write a short, polite 1-sentence Hinglish auto-reply."

        if (mercuryApi != null && settings.inceptionApiKey.isNotBlank()) {
            val mercText = AiResilienceHelper.executeMercuryChatWithRecovery(
                mercuryApi = mercuryApi,
                keyStore = keyStore,
                key = settings.inceptionApiKey.trim(),
                baseUrl = settings.modelConfig.inceptionBaseUrl,
                initialModel = settings.modelConfig.mindModel,
                requestBuilder = { modelId, stream ->
                    MercuryChatRequest(
                        model = modelId,
                        messages = listOf(
                            GroqMessage(role = "system", content = systemText),
                            GroqMessage(role = "user", content = prompt)
                        ),
                        tools = null,
                        toolChoice = null,
                        reasoningEffort = "low",
                        maxCompletionTokens = 160,
                        stream = stream
                    )
                },
                streamResponses = false
            ).getOrNull()?.value?.choices?.firstOrNull()?.message?.content
                ?.let { AiResponseParser.stripReasoning(it) }
            if (!mercText.isNullOrBlank()) return mercText
            if (!settings.autoFallbackEnabled) return template
        }

        if (settings.geminiApiKey.isNotBlank()) {
            val gemText = AiResilienceHelper.executeGeminiWithRecovery(
                geminiApi = geminiApi,
                keyStore = keyStore,
                key = settings.geminiApiKey.trim(),
                initialModel = settings.modelConfig.geminiModel,
                request = GeminiGenerateRequest(
                    systemInstruction = GeminiContent(
                        parts = listOf(GeminiPart(text = systemText))
                    ),
                    contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = prompt)))),
                    generationConfig = com.example.data.remote.GeminiGenerationConfig(
                        maxOutputTokens = 256,
                        thinkingConfig = com.example.data.remote.GeminiThinkingConfig("low")
                    )
                )
            ).getOrNull()?.value?.candidates?.firstOrNull()?.content?.parts
                ?.let { AiResilienceHelper.extractGeminiVisibleText(it) }
            if (!gemText.isNullOrBlank()) return gemText
            if (!settings.autoFallbackEnabled) return template
        }

        if (settings.groqApiKey.isNotBlank()) {
            val groqText = AiResilienceHelper.executeGroqChatWithRecovery(
                groqApi = groqApi,
                keyStore = keyStore,
                key = settings.groqApiKey.trim(),
                initialModel = settings.modelConfig.fastModel,
                role = GroqModelRole.FAST,
                requestBuilder = { modelId ->
                    GroqChatRequest(
                        model = modelId,
                        messages = listOf(
                            GroqMessage(role = "system", content = systemText),
                            GroqMessage(role = "user", content = prompt)
                        ),
                        tools = null,
                        toolChoice = null
                    )
                }
            ).getOrNull()?.value?.choices?.firstOrNull()?.message?.content
                ?.let { AiResponseParser.stripReasoning(it) }
            if (!groqText.isNullOrBlank()) return groqText
        }

        return template
    }

    companion object {
        private const val ONE_HOUR_MS = 60L * 60L * 1000L

        fun normalizeKey(input: String): String =
            input.lowercase().replace(Regex("[^a-z0-9+]"), "").ifBlank { input.lowercase().trim() }

        fun isWithinSchedule(
            startHour: Int,
            startMinute: Int,
            endHour: Int,
            endMinute: Int,
            nowMinutesOverride: Int? = null
        ): Boolean {
            val start = startHour.coerceIn(0, 23) * 60 + startMinute.coerceIn(0, 59)
            val end = endHour.coerceIn(0, 23) * 60 + endMinute.coerceIn(0, 59)
            if (start == end) return true
            val current = nowMinutesOverride ?: Calendar.getInstance().let {
                it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
            }
            return if (start < end) {
                current in start until end
            } else {
                // Overnight window e.g. 22:00 to 07:00
                current >= start || current < end
            }
        }
    }
}
