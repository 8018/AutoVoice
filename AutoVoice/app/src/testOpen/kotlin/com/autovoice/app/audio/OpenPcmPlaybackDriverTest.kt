package com.autovoice.app.audio

import com.autovoice.audiofrontend.*
import com.autovoice.tts.PlaybackIdentity
import com.autovoice.voicecore.*
import com.autovoice.voicecore.health.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class OpenPcmPlaybackDriverTest {
    @Test fun `platform decoder preserves old 16k raw contract and repairs oversized standard WAV fields`() = runTest {
        val platform = AndroidPlaybackPcmPlatform(android.content.ContextWrapper(null))
        val raw = ByteArray(320) { it.toByte() };val formats = mutableListOf<PlaybackPcmFormat>();val decoded = ByteArrayOutputStream()
        suspend fun capture(format: PlaybackPcmFormat,bytes: ByteArray) { formats.add(format);decoded.write(bytes) }
        platform.decode(AudioReply("audio/L16",raw),::capture)
        assertEquals(PlaybackPcmFormat(16000,1),formats.single());assertArrayEquals(raw,decoded.toByteArray())
        decoded.reset();formats.clear()
        val wave = WavHeader.write(raw.size,24000,2)+raw
        for (i in 4..7) wave[i] = 0x7f
        for (i in 40..43) wave[i] = 0x7f
        platform.decode(AudioReply("audio/wav",wave),::capture)
        assertEquals(PlaybackPcmFormat(24000,2),formats.single());assertArrayEquals(raw,decoded.toByteArray())
    }
    private class Track : PlaybackPcmTrack {
        val pcm = ByteArrayOutputStream();var head = 0L;var autoDrain = true;var result: Int? = null
        val partials = ArrayDeque<Int>();var closed = 0;var interrupted = 0;var gain = 1f
        var onWrite: () -> Unit = {};var changed: () -> Unit = {}
        override fun play(queuedFrames: Int) = Unit
        override fun write(pcm: ByteArray,offset: Int,size: Int): Int {
            val n = (partials.removeFirstOrNull() ?: result ?: size).coerceAtMost(size)
            if (n > 0) this.pcm.write(pcm,offset,n)
            onWrite();return n
        }
        override fun playedFrames() = if (autoDrain) pcm.size()/2L else head
        override fun renderTiming(frame: Long) = PlaybackRenderTiming(1_000_000_000+frame*62_500,gain,1)
        override fun interrupt() { interrupted++ }
        override fun close() { closed++ }
    }
    private class Platform(val track: Track = Track()) : PlaybackPcmPlatform {
        var opens = 0;var closes = 0
        var failProbe = false
        override fun openTrack(onRouteChanged: () -> Unit): PlaybackPcmTrack {
            if (++opens == 1) { check(!failProbe) { "controlled initial probe failure" };return Track().also { closes++ } }
            track.changed = onRouteChanged;return track
        }
        override suspend fun decode(reply: AudioReply,emit: suspend (PlaybackPcmFormat,ByteArray) -> Unit) {
            emit(PlaybackPcmFormat(16000,1),reply.data)
        }
    }
    private class Reference : RenderReferenceSink {
        val frames = mutableListOf<RenderReferenceFrame>();var cutoff = Long.MIN_VALUE
        var fail = false
        override fun acceptRender(frame: RenderReferenceFrame): Boolean {
            check(!fail) { "controlled reference failure" }
            if (frame.playbackId <= cutoff) return false
            frames.add(frame);return true
        }
        override fun invalidateRender(playbackId: Long) { cutoff = maxOf(cutoff,playbackId) }
    }
    private fun TestScope.driver(p: Platform,r: Reference,events: MutableList<Pair<String,Map<String,Any?>>>,reporter: ComponentReporter = ComponentReporter.none()) =
        OpenPcmPlaybackDriver(p,backgroundScope,r,{ stage,_,payload -> events.add(stage to payload) },
            referenceReporter = reporter,dispatcher = StandardTestDispatcher(testScheduler),nowMs = { testScheduler.currentTime },stallMs = 50)

    @Test fun `24k stereo arbitrary chunk boundaries conserve samples and reject dangling bytes`() {
        val pcm = ByteArray(24000*4)
        for (i in pcm.indices step 4) { pcm[i] = 0x10;pcm[i+1] = 0x27;pcm[i+2] = 0x30;pcm[i+3] = 0x75 } // 10000+30000 ->20000
        val converter = PlaybackPcmConverter(PlaybackPcmFormat(24000,2));val out = ByteArrayOutputStream()
        var at = 0
        while (at < pcm.size) { val n = minOf(701,pcm.size-at);converter.push(pcm,at,n).forEach(out::write);at += n }
        converter.finish().forEach(out::write)
        assertEquals(32000,out.size())
        assertEquals(20000,((out.toByteArray()[0].toInt() and 255) or (out.toByteArray()[1].toInt() shl 8)).toShort().toInt())
        val identity = PlaybackPcmConverter(PlaybackPcmFormat(16000,1));val source = ByteArray(4096) { it.toByte() };val unchanged = ByteArrayOutputStream()
        var start = 0
        while (start < source.size) { val n = minOf(701,source.size-start);identity.push(source,start,n).forEach(unchanged::write);start += n }
        identity.finish().forEach(unchanged::write);assertArrayEquals(source,unchanged.toByteArray())
        val bad = PlaybackPcmConverter(PlaybackPcmFormat(16000,1));bad.push(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { bad.finish() }
    }
    @Test fun `file schedules promptly partial zero writes account exactly once and completion waits for physical drain`() = runTest {
        val track = Track().apply { autoDrain = false;partials.addAll(listOf(2,0,2)) }
        val p = Platform(track);val r = Reference();val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        val d = driver(p,r,events);val id = PlaybackIdentity("turn")
        val input = ByteArray(640) { it.toByte() }
        d.play(AudioReply("audio/pcm",input),id)
        assertTrue(events.isEmpty());runCurrent();advanceTimeBy(10);runCurrent()
        assertArrayEquals(input,track.pcm.toByteArray());assertFalse(events.any { it.first == "completed" })
        assertEquals(320,r.frames.sumOf { it.validSamples });assertEquals(listOf(0L,1L,2L),r.frames.map { it.startSample })
        track.head = 320;advanceTimeBy(5);runCurrent()
        assertEquals(listOf("start","completed"),events.map { it.first });assertEquals(id.playbackId,events.last().second["playbackId"])
        assertEquals(1,track.closed);d.close()
    }
    @Test fun `stream uses explicit format across odd chunks and waits for semantic completion too`() = runTest {
        val p = Platform();val r = Reference();val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        val d = driver(p,r,events);val chunks = Channel<ByteArray>(2);val end = CompletableDeferred<AudioStreamEnd>()
        val job = backgroundScope.launch { d.playStream(StreamingAudioReply("audio/pcm",24000,1,"pcm_s16le",chunks,end),PlaybackIdentity("chat")) }
        chunks.send(byteArrayOf(0x10));chunks.send(byteArrayOf(0x27,0x10,0x27,0x10,0x27));chunks.close()
        runCurrent();assertEquals(4,p.track.pcm.size());assertFalse(events.any { it.first == "completed" })
        end.complete(AudioStreamEnd());runCurrent();assertTrue(job.isCompleted)
        assertEquals(listOf("start","completed"),events.map { it.first });d.close()
    }
    @Test fun `zero error and stalled drain fail boundedly stop remains prompt and closes resources`() = runTest {
        for (kind in 0..2) {
            val track = Track().apply { if (kind == 0) result = 0 else if (kind == 1) result = -1 else autoDrain = false }
            val events = mutableListOf<Pair<String,Map<String,Any?>>>()
            val d = driver(Platform(track),Reference(),events)
            d.play(AudioReply("audio/pcm",ByteArray(320)),PlaybackIdentity("$kind"));runCurrent()
            advanceTimeBy(60);runCurrent()
            assertEquals(1,events.count { it.first == "failed" });assertEquals(1,track.closed);d.close()
        }
        val track = Track().apply { result = 0 };val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        val d = driver(Platform(track),Reference(),events)
        d.play(AudioReply("audio/pcm",ByteArray(320)),PlaybackIdentity("stop"));runCurrent();d.stop();runCurrent()
        assertEquals(listOf("interrupted"),events.map { it.first });assertEquals(1,track.closed);d.close()
    }
    @Test fun `gain route and recreated driver generations invalidate old reference without retiring new output`() = runTest {
        val r = Reference();val first = Platform();val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        first.track.autoDrain = false
        val d1 = driver(first,r,events);d1.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("old"));runCurrent()
        val old = r.frames.last().playbackId;first.track.changed();assertTrue(r.cutoff >= old)
        d1.close();runCurrent()
        val next = Platform();next.track.partials.addAll(listOf(2,638));var calls = 0
        next.track.onWrite = { if (++calls == 2) next.track.gain = .5f }
        val d2 = driver(next,r,events);d2.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("new"));runCurrent()
        assertTrue(r.frames.last().playbackId > old);assertEquals(1,r.frames.count { it.playbackId > old })
        val cutoff = r.cutoff;first.track.changed();assertEquals(cutoff,r.cutoff)
        d2.close();runCurrent();assertEquals(1,next.track.closed)
        val latest = Platform().apply { track.autoDrain = false };val d3 = driver(latest,r,events)
        d3.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("latest"));runCurrent()
        val activeEpoch = r.frames.last().playbackId;assertTrue(r.cutoff < activeEpoch)
        first.track.changed();assertTrue(r.cutoff < activeEpoch)
        d3.close();runCurrent();assertEquals(activeEpoch,r.cutoff)
    }
    @Test fun `reference sink failure revokes reference without failing safe playback`() = runTest {
        val p = Platform();val r = Reference().apply { fail = true };val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        val d = driver(p,r,events);d.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("safe"));runCurrent()
        assertEquals(listOf("start","completed"),events.map { it.first });assertEquals(640,p.track.pcm.size())
        assertTrue(r.cutoff > 0);d.close()
    }
    @Test fun `replaced pending output ends old identity without conflating current PCM chunks`() = runTest {
        val p = Platform();val r = Reference();val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        val d = driver(p,r,events);val old = PlaybackIdentity("old");val next = PlaybackIdentity("next")
        d.play(AudioReply("audio/pcm",ByteArray(640) { 1 }),old)
        val expected = ByteArray(640) { it.toByte() };d.play(AudioReply("audio/pcm",expected),next);runCurrent()
        assertArrayEquals(expected,p.track.pcm.toByteArray())
        assertEquals(listOf("interrupted","start","completed"),events.map { it.first })
        assertEquals(old.playbackId,events.first().second["playbackId"])
        assertTrue(events.drop(1).all { it.second["playbackId"] == next.playbackId });d.close()
    }
    @Test fun `late old route callback cannot revoke current reference health`() = runTest {
        val updates = mutableListOf<ComponentStatusEvent>()
        val reporter = ComponentReporter(ComponentDescriptor("render_reference","pcm","open"),1,ComponentStatusSink { updates.add(it) })
        val p = Platform().apply { track.autoDrain = false };val r = Reference();val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        val d = driver(p,r,events,reporter)
        assertFalse(updates.any { it.update.health == Health.HEALTHY })
        d.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("old"));runCurrent();val oldCallback = p.track.changed
        d.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("current"));runCurrent()
        assertEquals(Health.DEGRADED,updates.last().update.health)
        val currentEpoch = r.frames.last().playbackId;oldCallback()
        assertEquals(Health.DEGRADED,updates.last().update.health);assertTrue(r.cutoff < currentEpoch)
        d.close();runCurrent()
    }
    @Test fun `terminal observer exception cannot strand stream completion or duplicate termination`() = runTest {
        val p = Platform();val reference = Reference();var terminalAttempts = 0
        val d = OpenPcmPlaybackDriver(p,backgroundScope,reference,{ stage,_,_ ->
            if (stage != "start") { terminalAttempts++;error("controlled observer exception") }
        },dispatcher = StandardTestDispatcher(testScheduler),nowMs = { testScheduler.currentTime },stallMs = 50)
        val chunks = Channel<ByteArray>(1);chunks.send(ByteArray(320));chunks.close()
        val end = CompletableDeferred(AudioStreamEnd())
        val playing = backgroundScope.launch { d.playStream(StreamingAudioReply("audio/pcm",16000,1,"pcm_s16le",chunks,end),PlaybackIdentity("observer")) }
        try {
            runCurrent();assertTrue(playing.isCompleted);assertEquals(1,p.track.closed)
            d.stop();d.close();runCurrent();assertEquals(1,terminalAttempts)
        } finally { playing.cancel();runCatching { d.close() } }
    }
    @Test fun `actual successful output retries failed probe with fresh initialization then uses runtime health`() = runTest {
        val updates = mutableListOf<ComponentStatusEvent>()
        val reporter = ComponentReporter(ComponentDescriptor("playback","audio.track","open"),1,ComponentStatusSink { updates.add(it) })
        val p = Platform().apply { failProbe = true };val reference = Reference();val stages = mutableListOf<String>()
        val d = OpenPcmPlaybackDriver(p,backgroundScope,reference,{ stage,_,_ -> stages.add(stage) },playbackReporter = reporter,
            dispatcher = StandardTestDispatcher(testScheduler),nowMs = { testScheduler.currentTime },stallMs = 50)
        try {
            runCurrent();val failed = updates.last();assertEquals(Lifecycle.FAILED,failed.update.lifecycle)
            d.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("recovered"));runCurrent()
            val ready = updates.lastOrNull { it.update.lifecycle == Lifecycle.READY }
            assertNotNull(ready);assertNotEquals(failed.token.attemptId,ready!!.token.attemptId)
            assertEquals(listOf("start","completed"),stages)
            p.track.result = -1;d.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("runtime-failed"));runCurrent()
            assertEquals(Health.UNAVAILABLE,updates.last().update.health);assertNull(updates.last().update.lifecycle)
            assertEquals(ready.token,updates.last().token)
            p.track.result = null;d.play(AudioReply("audio/pcm",ByteArray(640)),PlaybackIdentity("runtime-recovery"));runCurrent()
            assertEquals(Health.HEALTHY,updates.last().update.health);assertEquals(ready.token,updates.last().token)
            assertEquals(2,updates.count { it.update.lifecycle == Lifecycle.INITIALIZING })
        } finally { d.close();runCurrent() }
    }
}
