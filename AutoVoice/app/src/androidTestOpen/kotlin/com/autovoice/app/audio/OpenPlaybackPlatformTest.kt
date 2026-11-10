package com.autovoice.app.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autovoice.app.AudioPlayer
import com.autovoice.app.channel.PlaybackDriverPorts
import com.autovoice.app.channel.SelectedSpeechChannel
import com.autovoice.audiofrontend.*
import com.autovoice.tts.PlaybackIdentity
import com.autovoice.voicecore.*
import com.autovoice.voicecore.health.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Real production selection/MediaCodec/AudioTrack probe. No acoustic, microphone or car-AEC claim. */
@RunWith(AndroidJUnit4::class)
class OpenPlaybackPlatformTest {
    private class Reference : RenderReferenceSink {
        val frames = CopyOnWriteArrayList<RenderReferenceFrame>()
        @Volatile var cutoff = Long.MIN_VALUE
        @Synchronized override fun acceptRender(frame: RenderReferenceFrame): Boolean {
            if (frame.playbackId <= cutoff) return false
            frames.add(frame);return true
        }
        @Synchronized override fun invalidateRender(playbackId: Long) { cutoff = maxOf(cutoff,playbackId) }
    }
    @Test fun selectedDriverDecodesWritesRendersAndPhysicallyDrainsFixtures(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val reference = Reference();var previousEpoch = 0L
        for ((name,mime) in listOf("command-24k-mono.wav" to "audio/wav","command-24k-mono.m4a" to "audio/mp4","command-48k-stereo.wav" to "audio/wav")) {
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val terminal = CompletableDeferred<String>();val stages = CopyOnWriteArrayList<String>()
            val health = CopyOnWriteArrayList<ComponentStatusEvent>()
            val reporter = ComponentReporter(ComponentDescriptor("playback","audio.track","open"),1,ComponentStatusSink { health.add(it) })
            val driver = SelectedSpeechChannel.create().createPlaybackDriver(AudioPlayer { error("unused legacy playback") },
                PlaybackDriverPorts(context,scope,reference,{ stage,_,_ -> stages.add(stage);if (stage != "start") terminal.complete(stage) },reporter,ComponentReporter.none()))
            try {
                val data = instrumentation.context.assets.open("playback/$name").use { it.readBytes() }
                val identity = PlaybackIdentity("platform:$name");val started = System.nanoTime()
                instrumentation.runOnMainSync {
                    val before = System.nanoTime();driver.play(AudioReply(mime,data),identity)
                    assertTrue("file play blocked main thread",(System.nanoTime()-before)/1_000_000 < 200)
                }
                assertEquals("completed",withTimeout(15_000) { terminal.await() })
                assertEquals(listOf("start","completed"),stages.toList())
                val output = reference.frames.filter { it.playbackId > previousEpoch }
                assertTrue("real routed PCM reference was unavailable",output.isNotEmpty())
                val epoch = output.first().playbackId
                assertTrue(epoch > previousEpoch);assertTrue(output.all { it.playbackId == epoch && it.format == PcmFormat() && it.validSamples in 1..1600 })
                assertEquals(0L,output.first().startSample)
                assertTrue(output.zipWithNext().all { (a,b) -> a.startSample+a.validSamples == b.startSample })
                val frames = output.sumOf { it.validSamples }
                if (mime == "audio/wav") assertEquals(16888,frames) else assertTrue(frames in 16000..25000)
                val elapsed = (System.nanoTime()-started)/1_000_000
                assertTrue("completion preceded physical playhead drain",elapsed >= frames*1000L/16000-200)
                assertTrue(health.any { it.update.lifecycle == Lifecycle.READY })
                Log.i("OpenPlaybackProbe","fixture=$name frames=$frames references=${output.size} epoch=$epoch elapsedMs=$elapsed stages=$stages")
                previousEpoch = epoch
            } finally { driver.close();scope.cancel() }
        }
    }
    @Test fun streamingSemanticCompletionAndStopKeepActualIdentity(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation();val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val terminal = CompletableDeferred<Pair<String,Map<String,Any?>>>()
        val reference = Reference();val identity = PlaybackIdentity("platform:stream")
        val driver = SelectedSpeechChannel.create().createPlaybackDriver(AudioPlayer { error("unused legacy playback") },
            PlaybackDriverPorts(instrumentation.targetContext,scope,reference,{ stage,_,payload -> if (stage != "start") terminal.complete(stage to payload) },ComponentReporter.none(),ComponentReporter.none()))
        val chunks = Channel<ByteArray>(2);val end = CompletableDeferred<AudioStreamEnd>()
        try {
            val playing = scope.launch { driver.playStream(StreamingAudioReply("audio/pcm",24000,1,"pcm_s16le",chunks,end),identity) }
            val producing = scope.launch {
                val pcm = ByteArray(4800) { if (it%2 == 0) 0x10 else 0x27 }
                var at = 0
                while (at < pcm.size) { val n = minOf(131,pcm.size-at);chunks.send(pcm.copyOfRange(at,at+n));at += n }
                chunks.close()
            }
            producing.join();delay(300)
            assertFalse("physical playback finalized uncompleted stream semantics",terminal.isCompleted)
            end.complete(AudioStreamEnd())
            val completed = withTimeout(10_000) { terminal.await() }
            assertEquals("completed",completed.first);assertEquals(identity.playbackId,completed.second["playbackId"])
            playing.join()
            assertEquals(1600,reference.frames.sumOf { it.validSamples })
            driver.stop();Log.i("OpenPlaybackProbe","stream frames=1600 completed identity=${identity.playbackId}")
        } finally { chunks.cancel();driver.close();scope.cancel() }
    }
}
