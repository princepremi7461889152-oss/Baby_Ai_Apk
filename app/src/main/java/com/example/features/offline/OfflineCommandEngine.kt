package com.example.features.offline

import android.content.Context
import android.media.AudioManager
import com.example.core.security.SecureKeyStore
import com.example.domain.tools.PhoneControlExecutor
import com.example.domain.tools.ToolExecutionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineMatchResult(
    val toolName: String,
    val args: Map<String, Any?>,
    val executionResult: ToolExecutionResult,
    val friendlyReply: String
)

@Singleton
class OfflineCommandEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val executor: PhoneControlExecutor
) {
    suspend fun tryExecuteOffline(command: String): OfflineMatchResult? {
        if (!keyStore.settings.value.offlineCommandsEnabled) return null
        val lower = command.lowercase().trim()

        // 1. Flashlight
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val enable = !lower.contains("off") && !lower.contains("band") && !lower.contains("disable")
            val res = executor.execute("toggle_flashlight", mapOf("enable" to enable))
            return OfflineMatchResult(
                "toggle_flashlight",
                mapOf("enable" to enable),
                res,
                if (enable) "Flashlight on kar di hai!" else "Flashlight band kar di hai."
            )
        }

        // 2. DND
        if (lower.contains("dnd") || lower.contains("do not disturb")) {
            val enable = !lower.contains("off") && !lower.contains("band") && !lower.contains("disable")
            val res = executor.execute("toggle_dnd", mapOf("enable" to enable))
            return OfflineMatchResult(
                "toggle_dnd",
                mapOf("enable" to enable),
                res,
                if (res.ok) "DND ${if (enable) "ON" else "OFF"} kar diya hai." else res.output
            )
        }

        // 3. Volume
        if (lower.contains("volume") || lower.contains("awaz") || lower.contains("mute")) {
            val percent = Regex("""(\d{1,3})\s*%?""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                ?: when {
                    lower.contains("mute") || lower.contains("silent") -> 0
                    lower.contains("full") || lower.contains("max") -> 100
                    lower.contains("kam") || lower.contains("low") || lower.contains("down") -> currentVolumePercent() - 20
                    lower.contains("zyada") || lower.contains("high") || lower.contains("up") || lower.contains("badha") -> currentVolumePercent() + 20
                    else -> null
                }
            if (percent != null) {
                val clamped = percent.coerceIn(0, 100)
                val res = executor.execute("set_volume", mapOf("stream" to "media", "level_percent" to clamped))
                return OfflineMatchResult(
                    "set_volume",
                    mapOf("stream" to "media", "level_percent" to clamped),
                    res,
                    "Volume $clamped% kar diya hai."
                )
            }
        }

        // 4. Brightness
        if (lower.contains("brightness") || lower.contains("roshni")) {
            val percent = Regex("""(\d{1,3})\s*%?""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                ?: when {
                    lower.contains("low") || lower.contains("kam") || lower.contains("dim") -> 20
                    lower.contains("full") || lower.contains("high") || lower.contains("max") -> 95
                    else -> 50
                }
            val res = executor.execute("set_brightness", mapOf("level_percent" to percent.coerceIn(5, 100)))
            return OfflineMatchResult(
                "set_brightness",
                mapOf("level_percent" to percent),
                res,
                if (res.ok) "Brightness $percent% set kar di hai." else res.output
            )
        }

        // 5. Simple direct alarm "7:30 alarm" / "7 baje ka alarm"
        if (lower.contains("alarm") && !lower.contains("cancel") && !lower.contains("band")) {
            val colon = Regex("""(\d{1,2}):(\d{2})""").find(lower)
            val baje = Regex("""(\d{1,2})\s*(baje|am|pm)""").find(lower)
            if (colon != null || baje != null) {
                var h = (colon?.groupValues?.get(1) ?: baje?.groupValues?.get(1))?.toIntOrNull() ?: 7
                val m = colon?.groupValues?.get(2)?.toIntOrNull() ?: 0
                if ((lower.contains("pm") || lower.contains("shaam") || lower.contains("raat")) && h in 1..11) h += 12
                val args = mapOf("hour" to h.coerceIn(0, 23), "minute" to m.coerceIn(0, 59), "label" to "Baby Alarm")
                val res = executor.execute("set_alarm", args)
                return OfflineMatchResult("set_alarm", args, res, res.output)
            }
        }

        // 6. Simple "open <app>" / "<app> kholo"
        val openMatch = Regex("""^open\s+([a-z0-9 .]+)$""").find(lower)
            ?: Regex("""^([a-z0-9 .]+)\s+kholo$""").find(lower)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            if (appName.isNotBlank() && !appName.contains("wifi") && !appName.contains("settings")) {
                val res = executor.execute("open_app", mapOf("app_name" to appName))
                if (res.ok) {
                    return OfflineMatchResult("open_app", mapOf("app_name" to appName), res, "$appName khol diya hai!")
                }
            }
        }

        // 7. Offline Call Contact: "call Mummy", "Mummy ko call karo", "Rahul ko phone lagao", "dial 9876543210"
        val callTarget = extractOfflineCallTarget(lower)
        if (!callTarget.isNullOrBlank()) {
            val res = executor.execute("call_contact", mapOf("target" to callTarget))
            return OfflineMatchResult("call_contact", mapOf("target" to callTarget), res, res.output)
        }

        return null
    }

    private fun extractOfflineCallTarget(lower: String): String? {
        val patterns = listOf(
            Regex("""^(?:call|dial|phone)\s+([a-z0-9+ .-]{2,40})$"""),
            Regex("""^([a-z0-9+ .-]{2,40})\s+ko\s+(?:call|phone|dial)\s*(?:karo|kar|lagao|laga\s*do)?$"""),
            Regex("""^(?:call|phone)\s+([a-z0-9+ .-]{2,40})\s+(?:karo|lagao)$""")
        )
        for (p in patterns) {
            val m = p.find(lower)
            if (m != null) {
                val candidate = m.groupValues[1].trim()
                if (candidate.isNotBlank() && !candidate.contains("emergency") && !candidate.contains("help")) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun currentVolumePercent(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return ((cur.toFloat() / max) * 100f).toInt()
    }
}
