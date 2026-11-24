package com.example.features.offline

import com.example.domain.tools.AppResolver
import java.util.Locale

data class FastPathIntent(
    val toolName: String,
    val args: Map<String, Any?>,
    val defaultReply: String
)

/**
 * Deterministic + fuzzy zero-network intent matcher (<300 ms target) running BEFORE any AI call.
 * Covers Hindi, Hinglish, and English commands for:
 * flashlight, volume, brightness, wifi, bluetooth, DND, alarm, timer, open app, call, media, back/home.
 */
object FastPathIntentMatcher {

    private val GREETING_PREFIX = Regex("""^(?:hey\s+baby|baby|ok\s+baby|hi\s+baby|hello\s+baby)\s*[,:-]?\s*""", RegexOption.IGNORE_CASE)

    fun match(
        rawCommand: String,
        currentMediaVolumePercent: () -> Int = { 50 },
        fallbackMode: Boolean = false
    ): FastPathIntent? {
        val cleaned = rawCommand.trim().replace(GREETING_PREFIX, "").trim()
        if (cleaned.isBlank()) return null

        val normalized = normalizeDevanagariKeywords(cleaned)
        val lower = normalized.lowercase(Locale.ROOT).trim()

        // Guard against stealing multi-step search/play queries like "youtube par arijit song bajao"
        val isSearchAndPlayQuery = Regex("""\b(?:youtube|spotify|yt\s*music|instagram|amazon|flipkart|maps|play\s*store|chrome)\b.*\b(?:par|pe|mein|on|in|search|play|bajao|chalao|dhundo|dikhao)\b.*\b(?:gaana|song|video|reel|route|price|khol|bajao|chalao)\b""").containsMatchIn(lower)

        // 1. Flashlight / Torch
        if (containsAnyFuzzy(lower, listOf("flashlight", "flash light", "flashlite", "torch", "torche")) ||
            (fallbackMode && (lower.contains("flash on") || lower.contains("flash off")))
        ) {
            val enable = !hasTurnOffCue(lower)
            return FastPathIntent(
                toolName = "toggle_flashlight",
                args = mapOf("enable" to enable),
                defaultReply = if (enable) "Flashlight on kar di hai!" else "Flashlight band kar di hai."
            )
        }

        // 2. Wi-Fi
        if (containsAnyFuzzy(lower, listOf("wifi", "wi-fi", "wi fi"))) {
            val enable = !hasTurnOffCue(lower)
            return FastPathIntent(
                toolName = "toggle_wifi",
                args = mapOf("enable" to enable),
                defaultReply = if (enable) "Wi-Fi on panel khol diya hai." else "Wi-Fi off karne ke liye settings khol di hai."
            )
        }

        // 3. Bluetooth
        if (containsAnyFuzzy(lower, listOf("bluetooth", "blutooth", "bluetoth", "blue tooth")) ||
            Regex("""\bbt\s+(?:on|off|chalu|band)\b""").containsMatchIn(lower)
        ) {
            val enable = !hasTurnOffCue(lower)
            return FastPathIntent(
                toolName = "toggle_bluetooth",
                args = mapOf("enable" to enable),
                defaultReply = if (enable) "Bluetooth on kar diya hai." else "Bluetooth off kar diya hai."
            )
        }

        // 4. DND
        if (containsAnyFuzzy(lower, listOf("dnd", "do not disturb", "silent mode"))) {
            val enable = !hasTurnOffCue(lower)
            return FastPathIntent(
                toolName = "toggle_dnd",
                args = mapOf("enable" to enable),
                defaultReply = if (enable) "Do Not Disturb ON kar diya hai." else "Do Not Disturb OFF kar diya hai."
            )
        }

        // 5. Volume
        if (containsAnyFuzzy(lower, listOf("volume", "volum", "awaz", "awaaz", "sound", "mute", "unmute"))) {
            val stream = when {
                lower.contains("ring") -> "ring"
                lower.contains("alarm") -> "alarm"
                lower.contains("call") -> "call"
                lower.contains("notif") -> "notification"
                else -> "media"
            }
            val pct = Regex("""(\d{1,3})\s*%?""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                ?: when {
                    lower.contains("unmute") -> 60
                    lower.contains("mute") || lower.contains("silent") -> 0
                    lower.contains("full") || lower.contains("max") || lower.contains("100") -> 100
                    lower.contains("kam") || lower.contains("low") || lower.contains("down") ||
                        lower.contains("ghatao") || lower.contains("dheeme") || lower.contains("decrease") ->
                        currentMediaVolumePercent() - 20
                    lower.contains("zyada") || lower.contains("high") || lower.contains("up") ||
                        lower.contains("badha") || lower.contains("tez") || lower.contains("increase") ->
                        currentMediaVolumePercent() + 20
                    else -> null
                }
            if (pct != null) {
                val clamped = pct.coerceIn(0, 100)
                return FastPathIntent(
                    toolName = "set_volume",
                    args = mapOf("stream" to stream, "level_percent" to clamped),
                    defaultReply = "Volume $clamped% kar diya hai."
                )
            }
        }

        // 6. Brightness
        if (containsAnyFuzzy(lower, listOf("brightness", "brigtness", "brightnes", "roshni", "screen light"))) {
            val rawPct = Regex("""(\d{1,3})\s*%?""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                ?: when {
                    lower.contains("low") || lower.contains("kam") || lower.contains("dim") || lower.contains("ghatao") -> 20
                    lower.contains("full") || lower.contains("high") || lower.contains("max") || lower.contains("zyada") || lower.contains("badhao") -> 95
                    else -> 55
                }
            val clamped = rawPct.coerceIn(5, 100)
            return FastPathIntent(
                toolName = "set_brightness",
                args = mapOf("level_percent" to clamped),
                defaultReply = "Brightness $clamped% set kar di hai."
            )
        }

        // 7. Timer ("5 minute ka timer", "30 second timer", "set a timer for 2 min")
        if (containsAnyFuzzy(lower, listOf("timer", "countdown")) && !hasTurnOffCue(lower)) {
            val sec = extractTimerSeconds(lower)
            if (sec != null && sec > 0) {
                val label = when {
                    sec % 3600 == 0 -> "${sec / 3600} hr timer"
                    sec % 60 == 0 -> "${sec / 60} min timer"
                    else -> "$sec sec timer"
                }
                return FastPathIntent(
                    toolName = "set_timer",
                    args = mapOf("seconds" to sec, "label" to "Baby $label"),
                    defaultReply = "$label start kar diya hai!"
                )
            }
        }

        // 8. Alarm ("subah 7 baje alarm lagao", "7:30 pm alarm", "6 baje utha dena")
        if ((containsAnyFuzzy(lower, listOf("alarm", "alam")) || lower.contains("utha dena") || lower.contains("utha do") || (fallbackMode && lower.contains("wake me"))) &&
            !lower.contains("cancel") && !hasTurnOffCue(lower)
        ) {
            val colon = Regex("""(\d{1,2}):(\d{2})""").find(lower)
            val baje = Regex("""(\d{1,2})\s*(baje|am|pm)\b""").find(lower)
            if (colon != null || baje != null) {
                var h = (colon?.groupValues?.get(1) ?: baje?.groupValues?.get(1))?.toIntOrNull() ?: 7
                val m = colon?.groupValues?.get(2)?.toIntOrNull() ?: 0
                if ((lower.contains("pm") || lower.contains("shaam") || lower.contains("raat")) && h in 1..11) {
                    h += 12
                }
                if ((baje?.groupValues?.get(2) == "am" || lower.contains("subah")) && h == 12) {
                    h = 0
                }
                val hh = h.coerceIn(0, 23)
                val mm = m.coerceIn(0, 59)
                val formatted = String.format(Locale.US, "%02d:%02d", hh, mm)
                return FastPathIntent(
                    toolName = "set_alarm",
                    args = mapOf("hour" to hh, "minute" to mm, "label" to "Baby Alarm"),
                    defaultReply = "Alarm $formatted par set kar diya hai!"
                )
            }
        }

        // 9. Back / Home Navigation
        if (Regex("""^(?:go\s+back|back\s+jao|peeche\s+jao|wapas\s+jao|back\s+karo|back)$""").matches(lower)) {
            return FastPathIntent("go_back", emptyMap(), "Back kar diya.")
        }
        if (Regex("""^(?:go\s+home|home\s+jao|home\s+screen\s+jao|home\s+par\s+jao|ghar\s+jao|home)$""").matches(lower)) {
            return FastPathIntent("go_home", emptyMap(), "Home screen par aa gayi.")
        }

        // 10. Media Controls ("gaana pause karo", "next song", "play music", "agla gaana")
        matchMediaAction(lower)?.let { action ->
            val reply = when (action) {
                "play" -> "Music play kar diya."
                "pause" -> "Music pause kar diya."
                "next" -> "Agla gaana chala diya."
                "previous" -> "Pichla gaana chala diya."
                "stop" -> "Music band kar diya."
                else -> "Media toggle kar diya."
            }
            return FastPathIntent("media_control", mapOf("action" to action), reply)
        }

        // 11. Open App ("open youtube", "yutub kholo", "insta chalao") — only if not a search-and-play query
        if (!isSearchAndPlayQuery) {
            extractOpenAppName(lower)?.let { appName ->
                return FastPathIntent(
                    toolName = "open_app",
                    args = mapOf("app_name" to appName),
                    defaultReply = "$appName khol diya hai!"
                )
            }
        }

        // 12. Call Contact ("call mummy", "mummy ko phone lagao", "dial 9876543210")
        extractCallTarget(lower)?.let { target ->
            return FastPathIntent(
                toolName = "call_contact",
                args = mapOf("target" to target),
                defaultReply = "$target ko call laga rahi hoon."
            )
        }

        return null
    }

    private fun matchMediaAction(lower: String): String? {
        if (lower.contains("youtube") || lower.contains("spotify") || lower.contains("arijit")) return null
        val mediaWords = listOf("music", "gaana", "gana", "song", "media", "track", "audio", "playback")
        val hasMediaNoun = mediaWords.any { lower.contains(it) }
        return when {
            Regex("""^(?:pause|play|next|previous|prev|resume)$""").matches(lower) -> {
                if (lower == "prev") "previous" else if (lower == "resume") "play" else lower
            }
            (lower.contains("next") || lower.contains("agla")) && (hasMediaNoun || lower.length <= 20) -> "next"
            (lower.contains("previous") || lower.contains("prev") || lower.contains("pichla")) && (hasMediaNoun || lower.length <= 24) -> "previous"
            hasMediaNoun && (lower.contains("pause") || lower.contains("rok") || lower.contains("ruko")) -> "pause"
            hasMediaNoun && (lower.contains("stop") || lower.contains("band")) -> "stop"
            hasMediaNoun && (lower.contains("play") || lower.contains("resume") || lower.contains("chalu") || lower.contains("bajao")) -> "play"
            else -> null
        }
    }

    private fun extractTimerSeconds(lower: String): Int? {
        val m = Regex("""(\d{1,4})\s*(hours?|hrs?|ghant[ae]?|minutes?|mins?|min|seconds?|secs?|sec|second)\b""").find(lower)
            ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues[2]
        return when {
            unit.startsWith("h") || unit.startsWith("ghant") -> (n * 3600).coerceIn(1, 86400)
            unit.startsWith("m") -> (n * 60).coerceIn(1, 86400)
            else -> n.coerceIn(1, 86400)
        }
    }

    private fun extractOpenAppName(lower: String): String? {
        val patterns = listOf(
            Regex("""^(?:open|launch|start)\s+([a-z0-9 ._-]{2,40})$"""),
            Regex("""^([a-z0-9 ._-]{2,32})\s+(?:kholo|khol\s*do|open\s*karo|open\s*kardo|open\s*kar\s*do|chalao|chala\s*do)$""")
        )
        val raw = patterns.firstNotNullOfOrNull { it.find(lower)?.groupValues?.get(1) } ?: return null
        val cleaned = raw
            .replace(Regex("""\b(please|for me|app|karo|kar do|kardo|bhai|baby|jara|zara)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.isBlank() ||
            cleaned.contains("wifi") ||
            cleaned.contains("bluetooth") ||
            cleaned.contains("flashlight") ||
            cleaned.contains("torch") ||
            cleaned.contains("dnd") ||
            Regex("""\b(kaam|wala|wali|wale|screen|page|file|chat|message|msg|photo|link|kuch)\b""").containsMatchIn(cleaned)
        ) {
            return null
        }
        return cleaned
    }

    private fun extractCallTarget(lower: String): String? {
        val patterns = listOf(
            Regex("""^(?:call|dial|phone)\s+([a-z0-9+ .-]{2,40})$"""),
            Regex("""^([a-z0-9+ .-]{2,40})\s+ko\s+(?:call|phone|dial)\s*(?:karo|kar|lagao|laga\s*do|kar\s*do)?$"""),
            Regex("""^(?:call|phone)\s+([a-z0-9+ .-]{2,40})\s+(?:karo|lagao|kar\s*do)$""")
        )
        for (p in patterns) {
            val m = p.find(lower)
            if (m != null) {
                val candidate = m.groupValues[1].trim()
                if (candidate.isNotBlank() &&
                    !candidate.contains("emergency") &&
                    !candidate.contains("help") &&
                    !candidate.contains("screen")
                ) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun hasTurnOffCue(lower: String): Boolean =
        lower.contains("off") ||
            lower.contains("band") ||
            lower.contains("disable") ||
            lower.contains("bujhao") ||
            lower.contains("bujha") ||
            lower.contains("stop")

    private fun containsAnyFuzzy(lower: String, targets: List<String>): Boolean {
        if (targets.any { lower.contains(it) }) return true
        val words = lower.split(Regex("""[^a-z0-9]+""")).filter { it.length >= 4 }
        return words.any { w ->
            targets.any { t ->
                val tClean = t.replace(" ", "")
                tClean.length >= 5 && AppResolver.levenshtein(w, tClean) <= 1
            }
        }
    }

    private fun normalizeDevanagariKeywords(input: String): String {
        return input
            .replace("टॉर्च", "torch")
            .replace("फ्लैशलाइट", "flashlight")
            .replace("फ्लैश", "flash")
            .replace("वाईफाई", "wifi")
            .replace("वाइफाइ", "wifi")
            .replace("ब्लूटूथ", "bluetooth")
            .replace("ब्राइटनेस", "brightness")
            .replace("रोशनी", "roshni")
            .replace("वॉल्यूम", "volume")
            .replace("आवाज़", "awaz")
            .replace("आवाज", "awaz")
            .replace("अलार्म", "alarm")
            .replace("टाइमर", "timer")
            .replace("बंद", "band")
            .replace("चालू", "chalu")
            .replace("खोलो", "kholo")
            .replace("बजे", "baje")
            .replace("गाना", "gaana")
            .replace("कॉल", "call")
            .replace("कम", "kam")
            .replace("ज्यादा", "zyada")
            .replace("बढ़ाओ", "badhao")
    }
}
