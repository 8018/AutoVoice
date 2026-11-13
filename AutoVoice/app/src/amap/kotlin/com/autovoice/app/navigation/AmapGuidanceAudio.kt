package com.autovoice.app.navigation

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

/** Per-utterance gain affects only navigation speech. No system stream volume is changed. */
internal class AmapGuidanceAudio(context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var closed = false
    private var engine: TextToSpeech? = null
    var muted = false
        private set
    private var gain = 1f

    init {
        engine = TextToSpeech(context) { status ->
            main.post {
                if (closed || status != TextToSpeech.SUCCESS) return@post
                val current = engine ?: return@post
                if (current.setLanguage(Locale.SIMPLIFIED_CHINESE) < TextToSpeech.LANG_AVAILABLE) return@post
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                ready = current.setAudioAttributes(attributes) == TextToSpeech.SUCCESS
            }
        }
    }

    fun mute(value: Boolean): Boolean {
        if (!ready || closed) return false
        if (value && engine?.stop() != TextToSpeech.SUCCESS) return false
        muted = value
        return true
    }

    fun volume(value: Float): Boolean {
        if (!ready || closed || !value.isFinite() || value !in 0f..1f) return false
        gain = value
        return true
    }

    fun speak(text: String) {
        if (!ready || closed || muted || gain == 0f || text.isBlank()) return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain) }
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
    }

    fun stop() { engine?.stop() }

    override fun close() {
        closed = true
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
