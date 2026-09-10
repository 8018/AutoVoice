package com.autovoice.app

import android.util.Log
import com.autovoice.app.audio.TtsCache
import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.StreamingAudioReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Owns synthesis, cache lookup and playback preparation; it does not route business intents. */
internal class SpeechOutputService(
    private val tts: TtsRequester,
    private val cache: TtsCache,
    private val playback: PlaybackCoordinator,
    private val telemetry: TelemetryClient,
    private val scope: CoroutineScope,
    private val isCurrentTurn: (String) -> Boolean,
    private val onEmptyOutput: (String) -> Unit,
) {
    fun play(turnId: String, reply: AudioReply) {
        if (!isCurrentTurn(turnId)) return
        playback.play(playback.prepare(turnId), reply)
    }

    fun playStream(
        turnId: String,
        reply: StreamingAudioReply,
        onComplete: (AudioStreamEnd) -> Unit,
    ) {
        scope.launch {
            if (!isCurrentTurn(turnId)) return@launch
            val identity = playback.prepare(turnId)
            val playing = launch { playback.playStream(identity, reply) }
            val end = try {
                reply.completion.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "stream completion failed; output closed safely", error)
                return@launch
            }
            if (isCurrentTurn(turnId)) onComplete(end)
            playing.join()
        }
    }

    fun speak(turnId: String, text: String) {
        if (!isCurrentTurn(turnId)) return
        if (text.isBlank()) {
            onEmptyOutput(turnId)
            return
        }
        telemetry.recordFor(turnId, TelemetryStages.TTS_PLAY_REQUEST, "info", mapOf("text" to text))
        val identity = playback.prepare(turnId)
        scope.launch {
            if (!isCurrentTurn(turnId)) return@launch
            val cached = cache.get(text) { stage, level, payload ->
                telemetry.recordFor(turnId, stage, level, payload)
            }
            if (cached != null) {
                if (isCurrentTurn(turnId)) playback.play(identity, cached)
                return@launch
            }
            tts.request(text, turnId)?.let { reply ->
                cache.put(text, reply)
                if (isCurrentTurn(turnId)) playback.play(identity, reply)
            } ?: playback.failed(identity, IllegalStateException("TTS synthesis failed"))
        }
    }

    companion object {
        private const val TAG = "SpeechOutputService"
    }
}
