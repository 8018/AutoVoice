package com.autovoice.tts

import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.StreamingAudioReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsServiceTest {
    @Test
    fun `interruption event carries caller reason`() = runTest {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        lateinit var output: TtsOutput
        output = createTtsOutput(
            synthesizer = TtsSynthesizer { _, _ -> null },
            cacheDir = null,
            driver = object : TtsPlaybackDriver {
                override fun play(reply: AudioReply, identity: PlaybackIdentity) {
                    output.acceptPlaybackEvent("start", "info", identity.payload())
                }
            },
            scope = this,
            isCurrentTurn = { true },
            events = TtsEventSink { event, _, payload -> events += event to payload },
        )
        output.play("turn", AudioReply("audio/pcm", byteArrayOf(1), "hello"))
        output.stop(PlaybackInterruptionReason.NEW_TURN)
        assertEquals(
            PlaybackInterruptionReason.NEW_TURN.wire,
            events.last { it.first == "tts_play_interrupted" }.second["reason"],
        )
    }

    @Test
    fun `output that never starts fails within preparation deadline`() = runTest {
        val stages = mutableListOf<PlaybackStage>()
        val output = createTtsOutput(
            synthesizer = TtsSynthesizer { _, _ -> null },
            cacheDir = null,
            driver = TtsPlaybackDriver { _, _ -> },
            scope = this,
            isCurrentTurn = { true },
            onPlaybackStage = { _, stage -> stages += stage },
            prepareTimeoutMs = 100,
        )
        output.play("turn", AudioReply("audio/pcm", byteArrayOf(1), "hi"))
        advanceTimeBy(99); runCurrent()
        assertTrue(stages.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(PlaybackStage.FAILED), stages)
    }

    @Test
    fun `synthesis exception settles output as failed`() = runTest {
        val stages = mutableListOf<PlaybackStage>()
        val output = createTtsOutput(
            synthesizer = TtsSynthesizer { _, _ -> throw IllegalStateException("network failed") },
            cacheDir = null,
            driver = TtsPlaybackDriver { _, _ -> },
            scope = this,
            isCurrentTurn = { true },
            onPlaybackStage = { _, stage -> stages += stage },
        )
        output.speak("turn", "hello")
        runCurrent()
        assertEquals(listOf(PlaybackStage.FAILED), stages)
    }

    @Test
    fun `stream playback completion waits for semantic completion before settling`() = runTest {
        val stages = mutableListOf<PlaybackStage>()
        val order = mutableListOf<String>()
        val end = CompletableDeferred<AudioStreamEnd>()
        lateinit var output: TtsOutput
        output = createTtsOutput(
            synthesizer = TtsSynthesizer { _, _ -> null },
            cacheDir = null,
            driver = object : TtsPlaybackDriver {
                override fun play(reply: AudioReply, identity: PlaybackIdentity) = Unit
                override suspend fun playStream(reply: StreamingAudioReply, identity: PlaybackIdentity) {
                    output.acceptPlaybackEvent("start", "info", identity.payload())
                    output.acceptPlaybackEvent("completed", "info", identity.payload())
                }
            },
            scope = this,
            isCurrentTurn = { true },
            onPlaybackStage = { _, stage -> stages += stage; order += stage.name },
        )
        val chunks = Channel<ByteArray>(1).apply { close() }
        output.playStream("turn", StreamingAudioReply("audio/pcm", 24_000, 1, "pcm_s16le", chunks, end)) {
            order += "semantic"
        }
        runCurrent()
        assertEquals(listOf(PlaybackStage.STARTED), stages)
        end.complete(AudioStreamEnd("done", null, ""))
        runCurrent()
        assertEquals(listOf("STARTED", "semantic", "COMPLETED"), order)
    }

    @Test
    fun `stream failure overrides early playback completion`() = runTest {
        val stages = mutableListOf<PlaybackStage>()
        val end = CompletableDeferred<AudioStreamEnd>()
        lateinit var output: TtsOutput
        output = createTtsOutput(
            synthesizer = TtsSynthesizer { _, _ -> null },
            cacheDir = null,
            driver = object : TtsPlaybackDriver {
                override fun play(reply: AudioReply, identity: PlaybackIdentity) = Unit
                override suspend fun playStream(reply: StreamingAudioReply, identity: PlaybackIdentity) {
                    output.acceptPlaybackEvent("start", "info", identity.payload())
                    output.acceptPlaybackEvent("completed", "info", identity.payload())
                }
            },
            scope = this,
            isCurrentTurn = { true },
            onPlaybackStage = { _, stage -> stages += stage },
        )
        val chunks = Channel<ByteArray>(1).apply { close() }
        output.playStream("turn", StreamingAudioReply("audio/pcm", 24_000, 1, "pcm_s16le", chunks, end)) {
            throw AssertionError("failed stream must not apply semantic end")
        }
        runCurrent()
        end.completeExceptionally(IllegalStateException("stream failed"))
        runCurrent()
        assertEquals(listOf(PlaybackStage.STARTED, PlaybackStage.FAILED), stages)
    }

    @Test
    fun `realtime stream plays only for its active chat generation and completes control`() = runTest {
        var generation = 1L
        val played = mutableListOf<PlaybackIdentity>()
        val completed = mutableListOf<AudioStreamEnd>()
        val output = createTtsOutput(
            synthesizer = TtsSynthesizer { _, _ -> null },
            cacheDir = null,
            driver = TtsPlaybackDriver { _, identity -> played += identity },
            scope = this,
            isCurrentTurn = { false },
            isCurrentRealtime = { it.generation == generation },
        )
        fun stream(): StreamingAudioReply {
            val chunks = Channel<ByteArray>(1).apply { close() }
            val end = CompletableDeferred<AudioStreamEnd>().apply {
                complete(AudioStreamEnd("再见", null, ""))
            }
            return StreamingAudioReply("audio/pcm", 24_000, 1, "pcm_s16le", chunks, end)
        }

        output.playRealtimeStream(RealtimePlaybackToken(1, "one"), stream()) { completed += it }
        runCurrent()
        assertEquals(listOf("chat:1:one"), played.map { it.turnId })
        assertEquals(listOf("再见"), completed.map { it.speakText })

        generation = 2
        output.playRealtimeStream(RealtimePlaybackToken(1, "late"), stream()) { completed += it }
        runCurrent()
        assertEquals(1, played.size)
        assertEquals(1, completed.size)
    }

    @Test
    fun `generation is cached behind the service boundary`() = runTest {
        var generated = 0
        val service = createTtsService(
            synthesizer = TtsSynthesizer { text, _ ->
                generated++
                AudioReply("audio/wav", byteArrayOf(1), text)
            },
            cacheDir = null,
        )

        service.audioFor("hello", "turn-1")
        service.audioFor("hello", "turn-2")

        assertEquals(1, generated)
    }

    @Test
    fun `output owns generation cache and playback identity`() = runTest {
        var generated = 0
        val played = mutableListOf<Pair<AudioReply, PlaybackIdentity>>()
        val events = mutableListOf<String>()
        val output = createTtsOutput(
            synthesizer = TtsSynthesizer { text, _ ->
                generated++
                AudioReply("audio/wav", byteArrayOf(1), text)
            },
            cacheDir = null,
            driver = TtsPlaybackDriver { reply, identity ->
                played += reply to identity
            },
            scope = this,
            isCurrentTurn = { true },
            events = TtsEventSink { event, _, _ -> events += event },
        )

        output.speak("turn-1", "hello")
        testScheduler.advanceUntilIdle()
        output.speak("turn-2", "hello")
        testScheduler.advanceUntilIdle()

        assertEquals(1, generated)
        assertEquals(listOf("turn-1", "turn-2"), played.map { it.second.turnId })
        assertTrue("tts_cache_hit" in events)
    }
}
