package com.autovoice.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * 本地 TTS 兜底（Android [TextToSpeech]，离线可用）。
 *
 * 云端 TTS / AudioReply 不可用时的本地链路播报。init 是异步的：
 * 未就绪/init 失败/语言不支持时 [speak] 回调 onResult(false)，不抛到 UI。
 */
class SystemTtsFallback(
    context: Context,
    private val onInitError: ((Throwable) -> Unit)? = null,
) {

    private val tts: TextToSpeech

    @Volatile
    private var ready = false

    /** 播报中标志（回声抑制：本地链播报期间按录音 → 丢弃本轮，防回声被 ASR 当指令）。 */
    @Volatile
    private var speaking = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val languageResult = tts.setLanguage(Locale.CHINESE)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "TTS zh not supported (code=$languageResult), degraded silently")
                    onInitError?.invoke(IllegalStateException("TTS language zh not supported code=$languageResult"))
                } else {
                    ready = true
                }
            } else {
                Log.w(TAG, "TTS init failed (status=$status), degraded silently")
                onInitError?.invoke(IllegalStateException("TTS init failed status=$status"))
            }
        }
    }

    /** 是否已就绪（init 成功且中文可用）。 */
    val isReady: Boolean get() = ready

    /** 播报中（回声抑制：本地链播报期间按录音 → 丢弃本轮）。 */
    val isSpeaking: Boolean get() = speaking

    /**
     * 播报文本：播报完成回调 onResult(true)；init 未就绪/失败/文本为空/引擎错误
     * 回调 onResult(false)。按 utteranceId 匹配回调，旧播报的迟到回调被忽略。
     * [speaking] 标志：onStart 置位（引擎确认开始出声）、onDone/onError 复位。
     */
    fun speak(text: String, onResult: (Boolean) -> Unit) {
        if (text.isBlank() || !ready) {
            onResult(false)
            return
        }
        val id = "autovoice_" + UUID.randomUUID()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == id) speaking = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) {
                    speaking = false
                    onResult(true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) {
                    speaking = false
                    onResult(false)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) {
                    speaking = false
                    onResult(false)
                }
            }
        })
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            speaking = false
            onResult(false)
        }
    }

    /** 释放 TTS 资源（幂等）。 */
    fun shutdown() {
        ready = false
        speaking = false
        try {
            tts.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "tts shutdown failed, degraded silently", t)
        }
    }

    private companion object {
        const val TAG = "SystemTtsFallback"
    }
}
