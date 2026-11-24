package com.example.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BabyTtsManager @Inject constructor(
    @ApplicationContext private val context: Context
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            tts?.setSpeechRate(1.04f)
            tts?.setPitch(1.03f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                }
            })
        }
    }

    fun speak(text: String, languageHint: String = "hinglish") {
        val clean = text.trim()
        if (!isReady || clean.isEmpty()) return

        val locale = when {
            languageHint.equals("english", ignoreCase = true) ||
                languageHint.startsWith("en", ignoreCase = true) ->
                Locale.Builder().setLanguage("en").setRegion("IN").build()
            languageHint.equals("hindi", ignoreCase = true) ||
                languageHint.startsWith("hi", ignoreCase = true) ->
                Locale.Builder().setLanguage("hi").setRegion("IN").build()
            containsDevanagari(clean) ->
                Locale.Builder().setLanguage("hi").setRegion("IN").build()
            else ->
                Locale.Builder().setLanguage("hi").setRegion("IN").build()
        }

        val engine = tts ?: return
        val availability = engine.isLanguageAvailable(locale)
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
        } else {
            engine.language = Locale.US
        }

        val res = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        _isSpeaking.value = (res == TextToSpeech.SUCCESS)
    }

    fun stop() {
        runCatching { tts?.stop() }
        _isSpeaking.value = false
    }

    private fun containsDevanagari(text: String): Boolean =
        text.any { it in '\u0900'..'\u097F' }
}
