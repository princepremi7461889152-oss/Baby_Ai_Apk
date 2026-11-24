package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.EmotionIntensity
import com.example.core.security.TtsTierMode
import com.example.ui.theme.BabyMood
import com.example.ui.theme.BabyMoodResolver
import com.example.voice.AcousticWakeSpotter
import com.example.voice.BabyTtsManager
import com.example.voice.MicPipelineManager
import com.example.voice.ResolvedTtsTier
import com.example.voice.TtsAudioPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceAndMoodTest {

    // ---- Suite 6: MicPipelineManager & AcousticWakeSpotter ----

    @Test
    fun `suite 6 mic pipeline backoff schedule caps cleanly at 30 seconds`() {
        assertEquals(1_000L, MicPipelineManager.nextRetryDelayMs(0))
        assertEquals(1_000L, MicPipelineManager.nextRetryDelayMs(1))
        assertEquals(2_000L, MicPipelineManager.nextRetryDelayMs(2))
        assertEquals(4_000L, MicPipelineManager.nextRetryDelayMs(3))
        assertEquals(8_000L, MicPipelineManager.nextRetryDelayMs(4))
        assertEquals(16_000L, MicPipelineManager.nextRetryDelayMs(5))
        assertEquals(30_000L, MicPipelineManager.nextRetryDelayMs(6))
        assertEquals(30_000L, MicPipelineManager.nextRetryDelayMs(12))
    }

    @Test
    fun `suite 6 TTS post-playback ignore window and WAV pre-roll framing work accurately`() {
        assertEquals(16_000, MicPipelineManager.PRE_ROLL_SAMPLES)
        assertEquals(300L, MicPipelineManager.POST_TTS_IGNORE_WINDOW_MS)

        assertTrue(MicPipelineManager.shouldIgnoreForTts(isSpeaking = true, postTtsIgnoreUntilMs = 0L, nowMs = 1000L))
        assertTrue(MicPipelineManager.shouldIgnoreForTts(isSpeaking = false, postTtsIgnoreUntilMs = 1300L, nowMs = 1250L))
        assertFalse(MicPipelineManager.shouldIgnoreForTts(isSpeaking = false, postTtsIgnoreUntilMs = 1300L, nowMs = 1300L))

        val pcm = ByteArray(3_200) { (it % 127).toByte() }
        val wav = MicPipelineManager.wrapPcmAsWav(pcm, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        val wavSampleRate = ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(16_000, wavSampleRate)
    }

    @Test
    fun `suite 6 acoustic wake spotter ignores silence and triggers on sustained speech-band frames`() {
        val spotter = AcousticWakeSpotter()
        val silent = ShortArray(320) { 0 }
        val silentResult = spotter.evaluateFrame(silent, silent.size, 16_000, sensitivity = 0.7f)
        assertFalse(silentResult.detected)
        assertEquals(0f, silentResult.rmsLevel, 0.01f)

        // Generate 440 Hz speech-band PCM16 signal across multiple 20ms frames
        val speechFrame = ShortArray(320) { i ->
            (sin(2.0 * PI * 440.0 * i / 16_000.0) * 6_000.0).toInt().toShort()
        }
        var detectedCount = 0
        repeat(4) {
            val res = spotter.evaluateFrame(speechFrame, speechFrame.size, 16_000, sensitivity = 0.7f)
            if (res.detected) detectedCount++
        }
        assertEquals(1, detectedCount)

        // Immediate repeat frame stays suppressed by cooldown
        val cooldownResult = spotter.evaluateFrame(speechFrame, speechFrame.size, 16_000, sensitivity = 0.7f)
        assertFalse(cooldownResult.detected)
    }

    // ---- Suite 7: BabyTtsManager Two-Tier Routing, Sentence Split & Timeout Fallback ----

    @Test
    fun `suite 7 two-tier TTS routing obeys short threshold, offline, and forced tier modes`() {
        assertEquals(
            ResolvedTtsTier.TIER_A_ANDROID,
            BabyTtsManager.resolveTierForSentence(wordCount = 5, mode = TtsTierMode.AUTO, hasGeminiKey = true, isOnline = true)
        )
        assertEquals(
            ResolvedTtsTier.TIER_B_GEMINI,
            BabyTtsManager.resolveTierForSentence(wordCount = 9, mode = TtsTierMode.AUTO, hasGeminiKey = true, isOnline = true)
        )
        assertEquals(
            ResolvedTtsTier.TIER_A_ANDROID,
            BabyTtsManager.resolveTierForSentence(wordCount = 15, mode = TtsTierMode.AUTO, hasGeminiKey = true, isOnline = false)
        )
        assertEquals(
            ResolvedTtsTier.TIER_A_ANDROID,
            BabyTtsManager.resolveTierForSentence(wordCount = 15, mode = TtsTierMode.ANDROID_ONLY, hasGeminiKey = true, isOnline = true)
        )
        assertEquals(
            ResolvedTtsTier.TIER_B_GEMINI,
            BabyTtsManager.resolveTierForSentence(wordCount = 2, mode = TtsTierMode.GEMINI_PREMIUM, hasGeminiKey = true, isOnline = true)
        )
    }

    @Test
    fun `suite 7 TTS splits sentences across Hinglish and Devanagari delimiters and strips markdown`() {
        val raw = "Namaste! **Flashlight** on kar di hai। Check https://example.com right now?"
        val clean = BabyTtsManager.cleanSpeakableText(raw)
        assertFalse(clean.contains("**"))
        assertFalse(clean.contains("https://"))

        val sentences = BabyTtsManager.splitSentences(clean)
        assertEquals(3, sentences.size)
        assertEquals("Namaste!", sentences[0])
        assertTrue(sentences[1].endsWith("।"))
        assertTrue(sentences[2].endsWith("?"))
    }

    @Test
    fun `suite 7 Gemini TTS timeout strictly falls back to Android Tier A cleanly`() = runBlocking {
        var androidPlayedText: String? = null
        val slowSentence = "Yeh ek lamba Hinglish jawab hai jo Gemini TTS timeout hone par Android voice par aayega."

        val chosenTier = BabyTtsManager.routeSentenceWithFallback(
            sentence = slowSentence,
            mode = TtsTierMode.AUTO,
            hasGeminiKey = true,
            isOnline = true,
            timeoutMs = 80L,
            geminiFetcher = {
                delay(350L)
                TtsAudioPayload(byteArrayOf(1, 2, 3), "audio/pcm")
            },
            onPlayGemini = { true },
            onPlayAndroid = { androidPlayedText = it }
        )

        assertEquals(ResolvedTtsTier.TIER_A_ANDROID, chosenTier)
        assertEquals(slowSentence, androidPlayedText)

        var geminiPlayed = false
        val fastTier = BabyTtsManager.routeSentenceWithFallback(
            sentence = slowSentence,
            mode = TtsTierMode.AUTO,
            hasGeminiKey = true,
            isOnline = true,
            timeoutMs = 500L,
            geminiFetcher = {
                TtsAudioPayload(byteArrayOf(1, 2, 3), "audio/pcm")
            },
            onPlayGemini = {
                geminiPlayed = true
                true
            },
            onPlayAndroid = { androidPlayedText = "should_not_run" }
        )
        assertEquals(ResolvedTtsTier.TIER_B_GEMINI, fastTier)
        assertTrue(geminiPlayed)
    }

    // ---- Suite 8: BabyMoodResolver & Distress Safety Guard ----

    @Test
    fun `suite 8 mood tag parsing and streaming partial tag stripping never leak markers`() {
        val (mood, text) = BabyMoodResolver.parseMoodTagAndStrip("[mood: happy] Flashlight on kar di hai!")
        assertEquals(BabyMood.HAPPY, mood)
        assertEquals("Flashlight on kar di hai!", text)

        assertEquals("Haan main sun rahi", BabyMoodResolver.stripMoodTagForStreaming("[mood: calm] Haan main sun rahi"))
        assertEquals("Haan bilkul", BabyMoodResolver.stripMoodTagForStreaming("Haan bilkul [mood: hap"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Baby", context.getString(R.string.app_name))
    }

    @Test
    fun `suite 8 mood resolution respects intensity OFF, SOS alert, errors, and subtle softening`() {
        val offRes = BabyMoodResolver.resolveMood(
            rawReplyText = "[mood: excited] Task done!",
            intensity = EmotionIntensity.OFF
        )
        assertEquals(BabyMood.CALM, offRes.mood)
        assertEquals("Task done!", offRes.cleanedText)
        assertNull(BabyMoodResolver.ttsStyleDirective(BabyMood.EXCITED, EmotionIntensity.OFF))

        val alertRes = BabyMoodResolver.resolveMood(
            rawReplyText = "[mood: happy] SOS triggered",
            intensity = EmotionIntensity.OFF,
            isSosOrAlert = true
        )
        assertEquals(BabyMood.ALERT, alertRes.mood)

        val errRes = BabyMoodResolver.resolveMood(
            rawReplyText = "Network timeout ho gaya",
            intensity = EmotionIntensity.SUBTLE,
            isError = true
        )
        assertEquals(BabyMood.CONCERNED, errRes.mood)

        val subtleSoftened = BabyMoodResolver.resolveMood(
            rawReplyText = "[mood: excited] Wah, badhai ho!",
            intensity = EmotionIntensity.SUBTLE
        )
        assertEquals(BabyMood.HAPPY, subtleSoftened.mood)

        val userMoodRes = BabyMoodResolver.resolveMood(
            rawReplyText = "Main yahan hoon aapke saath.",
            userCommand = "Aaj main bahut udas aur akela feel kar raha hoon",
            intensity = EmotionIntensity.SUBTLE,
            understandUserMood = true
        )
        assertEquals(BabyMood.EMPATHETIC, userMoodRes.mood)
        assertNotNull(BabyMoodResolver.ttsStyleDirective(BabyMood.EMPATHETIC, EmotionIntensity.EXPRESSIVE))
    }

    @Test
    fun `suite 8 crisis helpline triggers safely without false-triggering routine commands`() {
        val routineFalsePositives = listOf(
            "Phone ki battery khatam ho gayi hai",
            "Alarm band karo",
            "Macro khatam karo",
            "I want to finish this assignment fast"
        )
        for (cmd in routineFalsePositives) {
            assertNull("False distress trigger on '$cmd'", BabyMoodResolver.detectDistressAndHelpline(cmd))
        }

        val crisisRes = BabyMoodResolver.resolveMood(
            rawReplyText = "Normal AI reply",
            userCommand = "Main apni zindagi khatam karna chahta hoon, I want to die",
            intensity = EmotionIntensity.SUBTLE
        )
        assertTrue(crisisRes.distressHelplineTriggered)
        assertEquals(BabyMood.EMPATHETIC, crisisRes.mood)
        assertTrue(crisisRes.cleanedText.contains("14416"))
        assertTrue(crisisRes.cleanedText.contains("1800-599-0019"))
    }
}
