package com.autovoice.adapterfirered

import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.health.ComponentReporter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class SenseVoiceDecoderTest {
    @Test fun `silent noise and isolated hot frames skip recognition entirely`() {
        val vad = VadHandle(listOf(0.0f, 0.9f, 0.5f, 0.51f))
        val asr = Recognizer()
        SenseVoiceDecoder(vad, asr).use { decoder ->
            assertEquals("", decoder.decode(FloatArray(2048)))
            assertTrue(asr.segments.isEmpty())
        }
    }

    @Test fun `continuous hot frames admit the full PCM and history resets for every request`() {
        val pcm = FloatArray(1040) { (it + 1) / 2048f }
        val vad = VadHandle(listOf(0.7f, 0.8f, 0.1f))
        val asr = Recognizer()
        SenseVoiceDecoder(vad, asr).use { decoder ->
            repeat(2) {
                // A legitimate recognition must pass through unchanged even if it is one character.
                assertEquals("我", decoder.decode(pcm))
                assertArrayEquals(pcm, asr.segments.last())
                val (first, second, third) = vad.requests[it]
                assertArrayEquals(FloatArray(64) + pcm.copyOfRange(0, 512), first)
                assertArrayEquals(pcm.copyOfRange(448, 1024), second)
                assertArrayEquals(pcm.copyOfRange(960, 1040) + FloatArray(496), third)
            }
            assertEquals(2, vad.requests.size)
        }
        assertEquals(1, vad.releases)
        assertEquals(1, asr.releases)
    }

    @Test fun `failure during recognition still releases both native handles`() {
        val vad = VadHandle(listOf(0.7f, 0.8f))
        val asr = Recognizer(decodeFailure = true)
        assertThrows(IllegalStateException::class.java) {
            SenseVoiceDecoder(vad, asr).use { it.decode(FloatArray(1024)) }
        }
        assertEquals(1, vad.releases)
        assertEquals(1, asr.releases)
    }

    @Test fun `invalid VAD probability fails instead of admitting a request`() {
        val asr = Recognizer()
        SenseVoiceDecoder(VadHandle(listOf(0.7f, 0.8f, Float.NaN)), asr).use { decoder ->
            assertThrows(IllegalStateException::class.java) { decoder.decode(FloatArray(1536)) }
            assertTrue(asr.segments.isEmpty())
        }
    }

    @Test fun `close during active VAD drops decoding and callbacks and releases the handles on the owner`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val released = CountDownLatch(1)
        val asr = Recognizer()
        var owner: Thread? = null
        var releaseThread: Thread? = null
        val vad = object : SileroHandle {
            override fun reset() { owner = Thread.currentThread() }
            override fun compute(samples: FloatArray): Float {
                entered.countDown()
                check(resume.await(5, SECONDS))
                return 0.9f
            }
            override fun close() {
                releaseThread = Thread.currentThread()
                released.countDown()
            }
        }
        ModelFixture().use { model ->
            val runtime = FireRedRuntime(
                load = { model.loader().load() },
                create = { SenseVoiceDecoder(vad, asr) },
                bootstrap = {},
                metadata = ValidatedFireRedModel::metadata,
            )
            val engine = FireRedAsrEngine(runtime, FireRedAdmissionProvider {
                FireRedAdmission(Long.MAX_VALUE, { true }, { false })
            }, ComponentReporter.none())
            engine.use {
                assertTrue(it.awaitReady(2000))
                val pending = CompletableFuture.supplyAsync {
                    it.recognize("active", ByteArray(2048), object : AsrSink {
                        override fun onTranscript(result: AsrResult) = fail<Unit>("unexpected transcript")
                        override fun onTurnEstablished() = fail<Unit>("unexpected turn")
                    })
                }
                try {
                    assertTrue(entered.await(2, SECONDS))
                    it.close()
                    assertNull(pending.get(2, SECONDS))
                    assertEquals(1L, released.count)
                } finally {
                    resume.countDown()
                }
            }
            assertTrue(released.await(2, SECONDS))
            assertTrue(asr.segments.isEmpty())
            assertEquals(owner, releaseThread)
            assertEquals(1, asr.releases)
        }
    }

    private class VadHandle(private val probabilities: List<Float>) : SileroHandle {
        val requests = mutableListOf<MutableList<FloatArray>>()
        var releases = 0
        override fun reset() { requests.add(mutableListOf()) }
        override fun compute(samples: FloatArray): Float {
            val frames = requests.last()
            return probabilities[frames.size].also { frames.add(samples.copyOf()) }
        }
        override fun close() { releases++ }
    }

    private class Recognizer(private val decodeFailure: Boolean = false) : FireRedDecoder {
        val segments = mutableListOf<FloatArray>()
        var releases = 0
        override fun decode(samples: FloatArray): String {
            segments.add(samples.copyOf())
            check(!decodeFailure)
            return "我"
        }
        override fun close() { releases++ }
    }
}
