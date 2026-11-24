package com.example.features.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.MainActivity
import com.example.R
import com.example.core.security.SecureKeyStore
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqMessage
import com.example.service.BabyNotificationListenerService
import com.example.service.CachedNotification
import com.example.voice.BabyTtsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class NotificationBucket(val rank: Int, val label: String) {
    PEOPLE(0, "People"),
    UPDATES(1, "Updates"),
    PROMOS(2, "Promos")
}

@Singleton
class NotificationSummaryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val groqApi: GroqApiService,
    private val ttsManager: BabyTtsManager
) {
    suspend fun buildSummary(
        scope: String = "all",
        speakAloud: Boolean = false
    ): String {
        val settings = keyStore.settings.value
        if (!settings.notificationSummaryEnabled) {
            return "Notification Summary Settings mein OFF hai."
        }

        val listener = BabyNotificationListenerService.instance
            ?: return "Notification Access ON nahi hai. Settings mein jaake allow karo."

        val rawList = listener.getNotifications(null)
        if (rawList.isEmpty()) {
            val empty = "Abhi koi nayi notification nahi hai."
            if (speakAloud && settings.ttsRepliesEnabled) ttsManager.speak(empty, "hinglish")
            return empty
        }

        val normalizedScope = scope.trim().lowercase().ifBlank { "all" }
        val allowSensitive = settings.readSensitiveNotificationsEnabled

        val classified = rawList.map { notif ->
            val bucket = classifyBucket(notif)
            val isSensitive = isSensitiveFinancialOrOtp(notif.title, notif.text)
            Triple(notif, bucket, isSensitive)
        }.filter { (notif, bucket, _) ->
            when (normalizedScope) {
                "all", "unread" -> true
                "people", "messages", "chat" -> bucket == NotificationBucket.PEOPLE
                "promos", "offers", "ads" -> bucket == NotificationBucket.PROMOS
                "updates", "work" -> bucket == NotificationBucket.UPDATES
                else -> notif.appLabel.lowercase().contains(normalizedScope) ||
                    notif.packageName.lowercase().contains(normalizedScope) ||
                    notif.title.lowercase().contains(normalizedScope)
            }
        }.sortedWith(
            compareBy<Triple<CachedNotification, NotificationBucket, Boolean>> { it.second.rank }
                .thenByDescending { it.first.postTime }
        )

        if (classified.isEmpty()) {
            val noneForScope = "'$scope' ke liye abhi koi notification nahi mili."
            if (speakAloud && settings.ttsRepliesEnabled) ttsManager.speak(noneForScope, "hinglish")
            return noneForScope
        }

        var maskedCount = 0
        val orderedByBucketAndApp = classified
            .groupBy { it.second }
            .toSortedMap(compareBy { it.rank })

        val structuredInput = buildString {
            orderedByBucketAndApp.forEach { (bucket, entries) ->
                appendLine("=== ${bucket.label.uppercase()} ===")
                entries.groupBy { it.first.appLabel }.forEach { (app, appEntries) ->
                    val snippets = appEntries.take(4).joinToString("; ") { (n, _, isSensitive) ->
                        if (isSensitive && !allowSensitive) {
                            maskedCount++
                            "${n.title.take(25)}: [OTP/Bank alert masked]"
                        } else {
                            val safeTitle = n.title.take(32)
                            val safeText = if (allowSensitive) n.text.take(80) else maskSensitiveText(n.text.take(80))
                            "$safeTitle: $safeText"
                        }
                    }
                    appendLine("- $app (${appEntries.size}): $snippets")
                }
            }
        }.trim()

        val summary = if (!settings.dataSaverEnabled && settings.groqApiKey.isNotBlank()) {
            runCatching {
                val resp = groqApi.chatCompletions(
                    bearerAuth = "Bearer ${settings.groqApiKey.trim()}",
                    request = GroqChatRequest(
                        model = settings.groqModel,
                        messages = listOf(
                            GroqMessage(
                                role = "system",
                                content = "You are Baby. Summarize these notifications in 2-4 crisp Hinglish sentences grouped by app and importance: ALWAYS mention PEOPLE messages first, updates next, and PROMOS last. Never invent or reveal masked OTP/bank numbers."
                            ),
                            GroqMessage(role = "user", content = structuredInput)
                        ),
                        tools = null,
                        toolChoice = null
                    )
                )
                resp.body()?.choices?.firstOrNull()?.message?.content?.trim()
            }.getOrNull() ?: formatLocalSummary(orderedByBucketAndApp, allowSensitive)
        } else {
            formatLocalSummary(orderedByBucketAndApp, allowSensitive)
        }

        val finalSummary = if (maskedCount > 0 && !allowSensitive) {
            "$summary\n($maskedCount OTP/banking notification masked — Settings mein override kar sakte ho.)"
        } else {
            summary
        }

        if (speakAloud && settings.ttsRepliesEnabled) {
            // Never read raw OTP/bank text aloud unless user explicitly opted in
            val spokenSafe = if (allowSensitive) finalSummary else maskSensitiveText(finalSummary)
            ttsManager.speak(spokenSafe, "hinglish")
        }
        return finalSummary
    }

    fun showSummaryNotification(summaryText: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "baby_summary_channel"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Baby Daily Summaries", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openIntent = PendingIntent.getActivity(
            context,
            401,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_baby_tile)
            .setContentTitle("Baby • Notification Summary")
            .setContentText(summaryText.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(4001, notif)
    }

    private fun formatLocalSummary(
        grouped: Map<NotificationBucket, List<Triple<CachedNotification, NotificationBucket, Boolean>>>,
        allowSensitive: Boolean
    ): String {
        return buildString {
            appendLine("Aapki notifications (People pehle, Promos aakhir mein):")
            grouped.forEach { (bucket, entries) ->
                appendLine("${bucket.label}:")
                entries.groupBy { it.first.appLabel }.forEach { (app, list) ->
                    val previews = list.take(2).joinToString(" | ") { (n, _, isSens) ->
                        if (isSens && !allowSensitive) "${n.title}: [OTP/Bank masked]"
                        else "${n.title}: ${n.text.take(45)}"
                    }
                    appendLine("• $app (${list.size}): $previews")
                }
            }
        }.trim()
    }

    fun syncScheduledSummaries() {
        val settings = keyStore.settings.value
        val wm = WorkManager.getInstance(context)
        if (!settings.notificationSummaryEnabled) {
            wm.cancelUniqueWork("baby_morning_summary")
            wm.cancelUniqueWork("baby_evening_summary")
            return
        }
        if (settings.scheduledSummaryMorningEnabled) {
            scheduleDaily("baby_morning_summary", 8, 30, wm)
        } else {
            wm.cancelUniqueWork("baby_morning_summary")
        }

        if (settings.scheduledSummaryEveningEnabled) {
            scheduleDaily("baby_evening_summary", 20, 0, wm)
        } else {
            wm.cancelUniqueWork("baby_evening_summary")
        }
    }

    private fun scheduleDaily(workName: String, targetHour: Int, targetMinute: Int, wm: WorkManager) {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelayMin = ((next.timeInMillis - now.timeInMillis) / 60_000L).coerceAtLeast(1L)
        val req = PeriodicWorkRequestBuilder<ScheduledSummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMin, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    companion object {
        private val PEOPLE_PACKAGES = listOf(
            "whatsapp", "messaging", "mms", "dialer", "phone", "telegram",
            "signal", "discord", "slack", "teams", "instagram", "orca", "messenger"
        )
        private val PROMO_KEYWORDS = listOf(
            "% off", "flat ", "sale", "offer", "discount", "cashback", "coupon",
            "promo", "limited time", "deal", "win ", "reward", "shop now",
            "subscribe", "upgrade", "zomato", "swiggy", "myntra", "amazon", "flipkart"
        )
        private val SENSITIVE_BANK_OTP_KEYWORDS = listOf(
            "otp", "one time password", "one-time password", "verification code",
            "security code", "cvv", "debited", "credited", "a/c", "acct",
            "account no", "upi", "neft", "imps", "rtgs", "netbanking",
            "card ending", "txn", "transaction", "hdfc", "icici", "sbi",
            "axis bank", "kotak", "pnb", "bob", "available balance", "avl bal"
        )
        private val DIGIT_CODE_REGEX = Regex("""\b\d{4,8}\b""")
        private val ACCOUNT_MASK_REGEX = Regex("""(?i)\b(rs\.?|inr|₹)\s*[\d,]+(\.\d+)?\b""")

        fun classifyBucket(notif: CachedNotification): NotificationBucket {
            val pkg = notif.packageName.lowercase()
            val label = notif.appLabel.lowercase()
            val combined = "${notif.title} ${notif.text}".lowercase()

            if (PROMO_KEYWORDS.any { combined.contains(it) || label.contains(it) } &&
                notif.remoteInput == null
            ) {
                return NotificationBucket.PROMOS
            }
            if (notif.remoteInput != null || PEOPLE_PACKAGES.any { pkg.contains(it) || label.contains(it) }) {
                return NotificationBucket.PEOPLE
            }
            return NotificationBucket.UPDATES
        }

        fun isSensitiveFinancialOrOtp(title: String, text: String): Boolean {
            val combined = "$title $text".lowercase()
            return SENSITIVE_BANK_OTP_KEYWORDS.any { combined.contains(it) }
        }

        fun maskSensitiveText(text: String): String {
            var masked = DIGIT_CODE_REGEX.replace(text, "[****]")
            masked = ACCOUNT_MASK_REGEX.replace(masked, "[AMOUNT]")
            return masked
        }
    }
}
