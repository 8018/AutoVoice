package com.autovoice.app.audio

import com.autovoice.audiofrontend.RenderReferenceFrame
import com.autovoice.audiofrontend.RenderReferenceSink
import com.autovoice.tts.PlaybackIdentity
import com.autovoice.tts.TtsPlaybackDriver
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.health.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

internal data class PlaybackPcmFormat(val rate: Int,val channels: Int,val encoding: String = "pcm_s16le") {
    init { require(rate in 8000..48000 && channels in 1..2 && encoding == "pcm_s16le") { "unsupported PCM format" } }
}
internal data class PlaybackRenderTiming(val timestampNanos: Long,val gain: Float,val route: Int)
internal interface PlaybackPcmTrack : AutoCloseable {
    fun play()
    /** Nonblocking bytes accepted; zero means backpressure, negative means failure. */
    fun write(pcm: ByteArray,offset: Int,size: Int): Int
    fun playedFrames(): Long
    fun renderTiming(frame: Long): PlaybackRenderTiming?
    fun interrupt()
}
internal interface PlaybackPcmPlatform {
    fun openTrack(onRouteChanged: () -> Unit): PlaybackPcmTrack
    suspend fun decode(reply: AudioReply,emit: suspend (PlaybackPcmFormat,ByteArray) -> Unit)
}

/** One shared-IO owner, one current output and one bounded pending output. No UI decode/write/drain. */
internal class OpenPcmPlaybackDriver(
    private val platform: PlaybackPcmPlatform,
    parentScope: CoroutineScope,
    private val render: RenderReferenceSink?,
    private val events: (String,String,Map<String,Any?>) -> Unit,
    private val playbackReporter: ComponentReporter = ComponentReporter.none(),
    private val referenceReporter: ComponentReporter = ComponentReporter.none(),
    dispatcher: CoroutineContext = Dispatchers.IO,
    private val nowMs: () -> Long = { System.nanoTime()/1_000_000 },
    private val stallMs: Long = 2000,
) : TtsPlaybackDriver {
    private class Output(val identity: PlaybackIdentity,val file: AudioReply?,val stream: StreamingAudioReply?) {
        val active = AtomicBoolean(true);val terminal = AtomicBoolean(false)
        val epoch = renderGenerations.incrementAndGet()
        val done = CompletableDeferred<Unit>()
        @Volatile var job: Job? = null
        @Volatile var track: PlaybackPcmTrack? = null
        @Volatile var referenceRevoked = false
        var route: Int? = null;var gain: Float? = null
        var frames = 0L;var started = false
    }
    private val lock = Any()
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + dispatcher + ownerJob)
    private val pending = Channel<Output>(Channel.CONFLATED)
    private var current: Output? = null
    private var closed = false
    private val playbackToken = playbackReporter.start("audio_track_create")
    private val referenceToken = referenceReporter.start("awaiting_playback")
    init {
        require(stallMs in 1..10_000)
        referenceReporter.report(referenceToken,ComponentUpdate(Lifecycle.READY,Health.UNAVAILABLE,
            error = ComponentError(ErrorCode.RENDER_REFERENCE_STALE)))
        scope.launch {
            try {
                platform.openTrack {}.close()
                playbackReporter.report(playbackToken,ComponentUpdate.ready())
            } catch (e: Throwable) {
                e.rethrowCancellationOrFatal()
                playbackReporter.report(playbackToken,ComponentUpdate.failed(ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED,retryable = true)))
            }
            for (output in pending) {
                if (!output.active.get()) continue
                val playing = launch { runOutput(output) }
                output.job = playing
                if (!output.active.get()) playing.cancel()
                playing.join()
            }
        }
    }
    override fun play(reply: AudioReply,identity: PlaybackIdentity) {
        require(reply.data.isNotEmpty() && reply.data.size <= 16*1024*1024) { "unsupported playback size" }
        enqueue(Output(identity,reply,null))
    }
    override suspend fun playStream(reply: StreamingAudioReply,identity: PlaybackIdentity) {
        val output = Output(identity,null,reply)
        enqueue(output)
        try { output.done.await() } finally { if (!output.terminal.get()) interrupt(output) }
    }
    private fun enqueue(output: Output) {
        val previous = synchronized(lock) {
            check(!closed) { "playback driver is closed" }
            current.also {
                it?.active?.set(false)
                current = output
                check(pending.trySend(output).isSuccess) { "playback queue is closed" }
            }
        }
        previous?.let(::interrupt)
    }
    override fun stop() { synchronized(lock) { current.also { current = null } }?.let(::interrupt) }
    override fun close() {
        synchronized(lock) { if (closed) return;closed = true }
        stop();pending.cancel();ownerJob.cancel()
        playbackReporter.close(playbackToken);referenceReporter.close(referenceToken)
    }
    private fun interrupt(output: Output) {
        output.active.set(false);output.job?.cancel()
        runCatching { output.track?.interrupt() }
        revokeReference(output)
        finish(output,"interrupted")
    }
    private fun revokeReference(output: Output) {
        output.referenceRevoked = true
        runCatching { render?.invalidateRender(output.epoch) }
        val visible = synchronized(lock) { current == null || current === output }
        if (visible) referenceReporter.report(referenceToken,ComponentUpdate.health(Health.UNAVAILABLE,ComponentError(ErrorCode.RENDER_REFERENCE_STALE)))
    }
    private fun finish(output: Output,stage: String) {
        if (!output.terminal.compareAndSet(false,true)) return
        output.active.set(false)
        events(stage,if (stage == "failed") "error" else if (stage == "interrupted") "warn" else "info",
            output.identity.payload()+mapOf("frames" to output.frames,"sampleRate" to 16000,"channels" to 1,
                "errorCode" to if (stage == "failed") "PLAYBACK_FAILED" else null))
        output.done.complete(Unit)
        synchronized(lock) { if (current === output) current = null }
    }
    private suspend fun runOutput(output: Output) {
        var track: PlaybackPcmTrack? = null
        try {
            currentCoroutineContext().ensureActive()
            check(output.active.get())
            track = platform.openTrack { revokeReference(output) }
            output.track = track
            if (!output.active.get()) return
            track.play()
            var converter: PlaybackPcmConverter? = null
            var format: PlaybackPcmFormat? = null
            suspend fun accept(spec: PlaybackPcmFormat,bytes: ByteArray) {
                currentCoroutineContext().ensureActive();check(output.active.get())
                if (format == null) { format = spec;converter = PlaybackPcmConverter(spec) }
                check(format == spec) { "PCM format changed within output" }
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val n = minOf(8192,bytes.size-offset)
                    for (pcm in requireNotNull(converter).push(bytes,offset,n)) write(output,track,pcm)
                    offset += n
                }
            }
            output.file?.let { platform.decode(it,::accept) }
            output.stream?.let { stream ->
                val spec = PlaybackPcmFormat(stream.sampleRate,stream.channels,stream.encoding)
                for (chunk in stream.chunks) {
                    require(chunk.size <= 65_536) { "oversized PCM stream chunk" }
                    accept(spec,chunk)
                }
            }
            requireNotNull(converter) { "empty playback" }.finish().forEach { write(output,track,it) }
            require(output.frames > 0) { "empty PCM playback" }
            var progress = track.playedFrames();var lastProgress = nowMs()
            while (track.playedFrames() < output.frames) {
                currentCoroutineContext().ensureActive();check(output.active.get())
                val next = track.playedFrames()
                if (next > progress) { progress = next;lastProgress = nowMs() }
                check(nowMs()-lastProgress < stallMs) { "playback drain stalled" }
                delay(5)
            }
            output.stream?.completion?.await() // Physical drain alone never finalizes stream semantics.
            if (output.active.get()) finish(output,"completed")
        } catch (e: CancellationException) { finish(output,"interrupted");throw e }
        catch (e: Throwable) {
            e.rethrowCancellationOrFatal()
            if (output.active.get()) {
                playbackReporter.report(playbackToken,ComponentUpdate.health(Health.UNAVAILABLE,ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED,retryable = true)))
                finish(output,"failed")
            }
        } finally {
            output.track = null
            runCatching { track?.close() }
            revokeReference(output)
            if (!output.terminal.get()) finish(output,"interrupted")
        }
    }
    private suspend fun write(output: Output,track: PlaybackPcmTrack,pcm: ByteArray) {
        var offset = 0;var lastProgress = nowMs()
        while (offset < pcm.size) {
            currentCoroutineContext().ensureActive();check(output.active.get())
            val n = track.write(pcm,offset,pcm.size-offset)
            check(n in 0..pcm.size-offset && n%2 == 0) { "PCM write failed" }
            if (n == 0) { check(nowMs()-lastProgress < stallMs) { "PCM write stalled" };delay(5);continue }
            lastProgress = nowMs()
            if (!output.active.get()) return
            if (!output.started) {
                output.started = true
                playbackReporter.report(playbackToken,ComponentUpdate.health(Health.HEALTHY))
                events("start","info",output.identity.payload())
            }
            val start = output.frames;output.frames += n/2
            if (!output.referenceRevoked) {
                var timing = runCatching { track.renderTiming(start) }.getOrNull()
                val waitStart = nowMs()
                while (timing == null && nowMs()-waitStart < 100 && output.active.get()) {
                    delay(5);timing = runCatching { track.renderTiming(start) }.getOrNull()
                }
                if (timing == null || !timing.gain.isFinite() || timing.gain !in 0f..1f ||
                    output.route?.let { it != timing.route } == true || output.gain?.let { it != timing.gain } == true) revokeReference(output)
                else if (output.active.get()) {
                    output.route = timing.route;output.gain = timing.gain
                    val reference = pcm.copyOfRange(offset,offset+n)
                    for (i in reference.indices step 2) {
                        val sample = ((reference[i].toInt() and 255) or (reference[i+1].toInt() shl 8)).toShort()
                        val scaled = (sample*timing.gain).toInt().coerceIn(-32768,32767)
                        reference[i] = scaled.toByte();reference[i+1] = (scaled shr 8).toByte()
                    }
                    if (runCatching { render?.acceptRender(RenderReferenceFrame(output.epoch,start,timing.timestampNanos,reference)) }.getOrNull() != true) revokeReference(output)
                    else if (!output.referenceRevoked && output.active.get()) referenceReporter.report(referenceToken,ComponentUpdate.health(Health.DEGRADED)) // Own-app reference, not complete physical mix.
                }
            }
            offset += n
        }
    }
    private companion object { val renderGenerations = AtomicLong(0) }
}

/** Bounded PCM16 framing + stereo mean + rational area resampling, preserving cross-chunk bytes. */
internal class PlaybackPcmConverter(private val format: PlaybackPcmFormat) {
    private val frame = ByteArray(format.channels*2);private var frameBytes = 0
    private var weight = 0;private var sum = 0L
    private var output = ByteArray(3200);private var count = 0
    fun push(bytes: ByteArray,offset: Int = 0,size: Int = bytes.size-offset): List<ByteArray> {
        require(size in 0..8192)
        val ready = mutableListOf<ByteArray>()
        for (i in offset until offset+size) {
            frame[frameBytes++] = bytes[i]
            if (frameBytes != frame.size) continue
            frameBytes = 0
            var mono = 0
            for (ch in 0 until format.channels) mono += ((frame[ch*2].toInt() and 255) or (frame[ch*2+1].toInt() shl 8)).toShort().toInt()
            mono /= format.channels
            var remaining = 16000
            while (remaining > 0) {
                val take = minOf(remaining,format.rate-weight)
                sum += mono.toLong()*take;weight += take;remaining -= take
                if (weight == format.rate) {
                    val value = (sum/format.rate).toInt();sum = 0;weight = 0
                    output[count++] = value.toByte();output[count++] = (value shr 8).toByte()
                    if (count == output.size) { ready.add(output);output = ByteArray(3200);count = 0 }
                }
            }
        }
        return ready
    }
    fun finish(): List<ByteArray> {
        require(frameBytes == 0) { "incomplete PCM channel frame" }
        return if (count == 0) emptyList() else listOf(output.copyOf(count)).also { count = 0 }
    }
}
