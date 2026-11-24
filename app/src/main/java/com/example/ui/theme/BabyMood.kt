package com.example.ui.theme

import com.example.core.security.EmotionIntensity

enum class BabyMood(val label: String) {
    CALM("Calm"),
    HAPPY("Happy"),
    EXCITED("Excited"),
    THINKING("Thinking"),
    EMPATHETIC("Empathetic"),
    CONCERNED("Concerned"),
    ALERT("Alert")
}

data class MoodResolutionResult(
    val mood: BabyMood,
    val cleanedText: String,
    val distressHelplineTriggered: Boolean = false
)

object BabyMoodResolver {

    private val MOOD_TAG_REGEX = Regex("""\[\s*mood\s*:\s*([a-zA-Z_-]+)\s*]""", RegexOption.IGNORE_CASE)
    private val PARTIAL_TAG_PREFIX_REGEX = Regex("""\[\s*m(?:o(?:o(?:d(?:\s*:[^]]*)?)?)?)?$""", RegexOption.IGNORE_CASE)

    /**
     * Parses optional `[mood: ...]` tag emitted by an AI model and strips ALL `[mood:...]`
     * markers cleanly from the text so tags are never displayed or spoken aloud.
     */
    fun parseMoodTagAndStrip(rawText: String): Pair<BabyMood?, String> {
        if (rawText.isBlank()) return null to ""
        var parsedMood: BabyMood? = null
        val cleaned = MOOD_TAG_REGEX.replace(rawText) { match ->
            if (parsedMood == null) {
                parsedMood = moodFromToken(match.groupValues[1])
            }
            ""
        }
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return parsedMood to cleaned
    }

    /**
     * Strips complete or partially streamed `[mood: ...` tags during SSE streaming.
     */
    fun stripMoodTagForStreaming(streamText: String): String {
        val withoutComplete = MOOD_TAG_REGEX.replace(streamText, "")
        return PARTIAL_TAG_PREFIX_REGEX.replace(withoutComplete, "").trim()
    }

    fun moodFromToken(token: String?): BabyMood? {
        return when (token?.trim()?.lowercase()) {
            "calm", "neutral", "normal" -> BabyMood.CALM
            "happy", "cheerful", "warm", "glad" -> BabyMood.HAPPY
            "excited", "enthusiastic", "celebratory" -> BabyMood.EXCITED
            "thinking", "curious", "focused" -> BabyMood.THINKING
            "empathetic", "caring", "kind", "sad", "comforting" -> BabyMood.EMPATHETIC
            "concerned", "worried", "error", "careful" -> BabyMood.CONCERNED
            "alert", "urgent", "warning", "sos" -> BabyMood.ALERT
            else -> null
        }
    }

    /**
     * Detects critical self-harm, crisis, or severe emotional distress in text-only user input
     * without false-triggering on routine commands like "battery khatam" or "alarm band karo".
     */
    fun detectDistressAndHelpline(userCommand: String): String? {
        val lower = userCommand.lowercase().trim()
        if (lower.isEmpty()) return null

        val crisisPhrases = listOf(
            "kill myself",
            "want to die",
            "wanna die",
            "end my life",
            "suicide",
            "suicidal",
            "self harm",
            "self-harm",
            "hurt myself",
            "mar jana chahta",
            "mar jana chahti",
            "marne ka man",
            "marna chahta",
            "marna chahti",
            "jeena nahi chahta",
            "jeena nahi chahti",
            "zindagi khatam",
            "khud ko hurt",
            "khud ko mar",
            "no reason to live",
            "feel hopeless and want to end"
        )
        val matched = crisisPhrases.any { lower.contains(it) }
        if (!matched) return null

        return "Main samajh rahi hoon ki abhi waqt bahut mushkil lag raha hai, aur aap akele nahi hain. " +
            "Please kisi bharosemand dost, family member, ya professional se abhi baat karein — aapki life bahut keemti hai.\n\n" +
            "India 24x7 Free Helpline:\n" +
            "• Tele-MANAS: 14416 ya 1800-891-4416\n" +
            "• Kiran Mental Health: 1800-599-0019"
    }

    /**
     * Resolves final BabyMood combining safety rules, AI tag, user mood setting, and intensity.
     */
    fun resolveMood(
        rawReplyText: String,
        userCommand: String = "",
        intensity: EmotionIntensity = EmotionIntensity.SUBTLE,
        understandUserMood: Boolean = false,
        isError: Boolean = false,
        isSosOrAlert: Boolean = false
    ): MoodResolutionResult {
        val (tagged, stripped) = parseMoodTagAndStrip(rawReplyText)
        val helpline = detectDistressAndHelpline(userCommand)

        if (intensity == EmotionIntensity.OFF && helpline == null && !isSosOrAlert) {
            return MoodResolutionResult(
                mood = BabyMood.CALM,
                cleanedText = stripped,
                distressHelplineTriggered = false
            )
        }

        if (isSosOrAlert) {
            return MoodResolutionResult(
                mood = BabyMood.ALERT,
                cleanedText = stripped
            )
        }

        if (helpline != null) {
            return MoodResolutionResult(
                mood = BabyMood.EMPATHETIC,
                cleanedText = helpline,
                distressHelplineTriggered = true
            )
        }

        if (intensity == EmotionIntensity.OFF) {
            return MoodResolutionResult(
                mood = BabyMood.CALM,
                cleanedText = stripped
            )
        }

        if (isError) {
            return MoodResolutionResult(
                mood = BabyMood.CONCERNED,
                cleanedText = stripped
            )
        }

        val lowerUser = userCommand.lowercase()
        val lowerReply = stripped.lowercase()

        val userSoundsDown = understandUserMood && listOf(
            "sad", "udaas", "udas", "akela", "lonely", "stressed", "tension",
            "thak gaya", "thak gayi", "pareshan", "ro raha", "ro rahi", "anxious", "dukhi"
        ).any { lowerUser.contains(it) }

        val userSoundsHappy = understandUserMood && listOf(
            "khush", "happy", "great news", "maza aa gaya", "pass ho gaya",
            "job mil gayi", "congrats", "celebrate", "awesome"
        ).any { lowerUser.contains(it) }

        val replySoundsPositive = listOf(
            "ho gaya", "kar diya", "on kar di", "off kar di", "saved",
            "shukriya", "welcome", "badhiya", "done", "ready hai", "bhej diya"
        ).any { lowerReply.contains(it) }

        val resolved = when {
            userSoundsDown -> BabyMood.EMPATHETIC
            tagged != null -> if (intensity == EmotionIntensity.SUBTLE && tagged == BabyMood.EXCITED) {
                BabyMood.HAPPY
            } else {
                tagged
            }
            userSoundsHappy -> if (intensity == EmotionIntensity.EXPRESSIVE) BabyMood.EXCITED else BabyMood.HAPPY
            replySoundsPositive -> if (intensity == EmotionIntensity.EXPRESSIVE && stripped.contains("!")) {
                BabyMood.EXCITED
            } else {
                BabyMood.HAPPY
            }
            else -> BabyMood.CALM
        }

        return MoodResolutionResult(
            mood = resolved,
            cleanedText = stripped
        )
    }

    /**
     * Converts [BabyMood] into a non-spoken Gemini TTS `speechMetadata.style` hint.
     */
    fun ttsStyleDirective(mood: BabyMood, intensity: EmotionIntensity): String? {
        if (intensity == EmotionIntensity.OFF) return null
        val expressive = intensity == EmotionIntensity.EXPRESSIVE
        return when (mood) {
            BabyMood.CALM -> if (expressive) "warm calm friendly conversational tone" else "gentle natural tone"
            BabyMood.HAPPY -> if (expressive) "bright cheerful warm smile tone" else "warm friendly tone"
            BabyMood.EXCITED -> if (expressive) "upbeat energetic enthusiastic tone" else "cheerful friendly tone"
            BabyMood.THINKING -> "thoughtful clear pacing"
            BabyMood.EMPATHETIC -> "soft caring compassionate comforting tone"
            BabyMood.CONCERNED -> "calm reassuring careful tone"
            BabyMood.ALERT -> "clear urgent steady focused tone"
        }
    }
}
