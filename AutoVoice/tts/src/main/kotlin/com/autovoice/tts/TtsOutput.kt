package com.autovoice.tts

import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.StreamingAudioReply
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

data class PlaybackIdentity(val turnId: String, val playbackId: String = UUID.randomUUID().toString()) {
    fun payload(): Map<String, Any?> = mapOf("turnId" to turnId, "playbackId" to playbackId)
}

enum class PlaybackStage(val wire: String) {
    STARTED("start"), COMPLETED("completed"), FAILED("failed"), INTERRUPTED("interrupted")
}

/** Android/audio-platform adapter. Generation, cache and playback coordination stay in :tts. */
fun interface TtsPlaybackDriver {
    fun play(reply: AudioReply, identity: PlaybackIdentity)

    suspend fun playStream(reply: StreamingAudioReply, identity: PlaybackIdentity) {
        val pcm = ByteArrayOutputStream()
        for (chunk in reply.chunks) pcm.write(chunk)
        val end = reply.completion.await()
        play(AudioReply("audio/pcm", pcm.toByteArray(), end.speakText, end.intent, end.asrText), identity)
    }

    fun stop() = Unit
}

/** Unified output API. Consumers do not coordinate synthesis, cache, stream or playback state. */
interface TtsOutput {
    fun speak(turnId: String, text: String)
    fun play(turnId: String, reply: AudioReply)
    fun playStream(turnId: String, reply: StreamingAudioReply, onComplete: (AudioStreamEnd) -> Unit)
    fun stop()
    fun acceptPlaybackEvent(stage: String, level: String, payload: Map<String, Any?>)
}

fun createTtsOutput(
    synthesizer: TtsSynthesizer,
    cacheDir: File?,
    driver: TtsPlaybackDriver,
    scope: CoroutineScope,
    isCurrentTurn: (String) -> Boolean,
    events: TtsEventSink = TtsEventSink { _, _, _ -> },
    onPlaybackStage: (PlaybackIdentity, PlaybackStage) -> Unit = { _, _ -> },
    onEmptyOutput: (String) -> Unit = {},
): TtsOutput {
    val service = createTtsService(synthesizer, cacheDir, events)
    return DefaultTtsOutput(
        service, PlaybackCoordinator(driver, events, onPlaybackStage), scope,
        isCurrentTurn, events, onEmptyOutput,
    )
}

private class DefaultTtsOutput(
    private val service: TtsService,
    private val playback: PlaybackCoordinator,
    private val scope: CoroutineScope,
    private val isCurrentTurn: (String) -> Boolean,
    private val events: TtsEventSink,
    private val onEmptyOutput: (String) -> Unit,
) : TtsOutput {
    override fun speak(turnId: String, text: String) {
        if (!isCurrentTurn(turnId)) return
        if (text.isBlank()) return onEmptyOutput(turnId)
        events.emit("tts_play_request", "info", mapOf("turnId" to turnId, "text" to text))
        val identity = playback.prepare(turnId)
        scope.launch {
            if (!isCurrentTurn(turnId)) return@launch
            service.audioFor(text, turnId)?.let { if (isCurrentTurn(turnId)) playback.play(identity, it) }
                ?: playback.failed(identity, IllegalStateException("TTS synthesis failed"))
        }
    }

    override fun play(turnId: String, reply: AudioReply) {
        if (isCurrentTurn(turnId)) playback.play(playback.prepare(turnId), reply)
    }

    override fun playStream(
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
            } catch (_: Throwable) {
                return@launch
            }
            if (isCurrentTurn(turnId)) onComplete(end)
            playing.join()
        }
    }

    override fun stop() = playback.stop()

    override fun acceptPlaybackEvent(stage: String, level: String, payload: Map<String, Any?>) =
        playback.accept(stage, level, payload)
}

private class PlaybackCoordinator(
    private val driver: TtsPlaybackDriver,
    private val events: TtsEventSink,
    private val stageListener: (PlaybackIdentity, PlaybackStage) -> Unit,
) {
    private var current: PlaybackIdentity? = null
    private var started = false
    private var streamJob: Job? = null
    private val driverLock = Any()

    fun prepare(turnId: String): PlaybackIdentity {
        val next = PlaybackIdentity(turnId)
        synchronized(driverLock) {
            val previous = synchronized(this) {
                val value = Triple(current, started, streamJob)
                current = next
                started = false
                streamJob = null
                value
            }
            previous.third?.cancel()
            if (previous.first != null) driver.stop()
            if (previous.first != null && previous.second) emit(previous.first!!, PlaybackStage.INTERRUPTED, "warn")
        }
        return next
    }

    fun play(identity: PlaybackIdentity, reply: AudioReply) {
        try {
            synchronized(driverLock) {
                if (!synchronized(this) { current == identity }) return
                driver.play(reply, identity)
            }
        } catch (error: Exception) {
            failed(identity, error)
        }
    }

    suspend fun playStream(identity: PlaybackIdentity, reply: StreamingAudioReply) {
        val job = currentCoroutineContext()[Job]
        synchronized(this) {
            if (current != identity) return
            streamJob = job
        }
        try {
            driver.playStream(reply, identity)
        } catch (cancelled: CancellationException) {
            accept("interrupted", "warn", identity.payload())
            throw cancelled
        } catch (error: Exception) {
            failed(identity, error)
        } finally {
            synchronized(this) { if (streamJob === job) streamJob = null }
        }
    }

    fun failed(identity: PlaybackIdentity, error: Throwable) =
        accept("failed", "error", identity.payload() + ("error" to error.toString()))

    fun accept(stage: String, level: String, payload: Map<String, Any?>) {
        val event = synchronized(this) {
            val identity = current ?: return
            if (payload["playbackId"] != identity.playbackId || payload["turnId"] != identity.turnId) return
            val kind = PlaybackStage.entries.firstOrNull { it.wire == stage } ?: return
            if (kind == PlaybackStage.STARTED) {
                if (started) return
                started = true
            } else {
                current = null
                started = false
            }
            identity to kind
        }
        emit(event.first, event.second, level, payload)
    }

    fun stop() {
        synchronized(driverLock) {
            val previous = synchronized(this) {
                val value = Triple(current, started, streamJob)
                current = null
                started = false
                streamJob = null
                value
            }
            previous.third?.cancel()
            driver.stop()
            if (previous.first != null && previous.second) emit(previous.first!!, PlaybackStage.INTERRUPTED, "warn")
        }
    }

    private fun emit(
        identity: PlaybackIdentity,
        stage: PlaybackStage,
        level: String,
        payload: Map<String, Any?> = identity.payload(),
    ) {
        val event = when (stage) {
            PlaybackStage.STARTED -> "tts_play_start"
            PlaybackStage.INTERRUPTED -> "tts_play_interrupted"
            PlaybackStage.COMPLETED, PlaybackStage.FAILED -> "tts_play_end"
        }
        events.emit(event, level, payload + mapOf("source" to "network", "event" to stage.wire))
        stageListener(identity, stage)
    }
}
