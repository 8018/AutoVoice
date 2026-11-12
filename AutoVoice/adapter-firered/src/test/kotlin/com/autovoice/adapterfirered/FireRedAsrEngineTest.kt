package com.autovoice.adapterfirered

import com.autovoice.voicecore.*
import com.autovoice.voicecore.health.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class FireRedAsrEngineTest {
    private class Sink : AsrSink {
        val transcripts = mutableListOf<AsrResult>();var established = 0
        override fun onTranscript(result: AsrResult) { transcripts.add(result) }
        override fun onTurnEstablished() { established++ }
    }
    private class Decoder : FireRedDecoder {
        val calls = AtomicInteger();val closed = CountDownLatch(1)
        var output = "打开车窗";var entered: CountDownLatch? = null;var proceed: CountDownLatch? = null
        var samples = floatArrayOf()
        override fun decode(samples: FloatArray): String { calls.incrementAndGet();this.samples = samples;entered?.countDown();proceed?.await(5,TimeUnit.SECONDS);return output }
        override fun close() { closed.countDown() }
    }
    private val admitted = FireRedAdmissionProvider { FireRedAdmission(Long.MAX_VALUE,{ true },{ false }) }
    private fun runtime(f: ModelFixture,d: Decoder,loads: AtomicInteger = AtomicInteger()) = FireRedRuntime({ f.loader().load() },{ loads.incrementAndGet();d },{})
    private fun engine(r: FireRedRuntime,p: FireRedAdmissionProvider = admitted, timeout: Long = 5000) = FireRedAsrEngine(r,p,ComponentReporter.none(),timeout)
    private fun awaitPendingRequests(runtime: FireRedRuntime, expected: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (runtime.pendingRequests != expected && System.nanoTime() < until) Thread.sleep(1)
        assertEquals(expected, runtime.pendingRequests)
    }
    private fun async(block: () -> AsrResult?) = CompletableFuture.supplyAsync(block)

    @Test fun `one model reused PCM is signed little endian normalized and only nonempty actual finals establish once`() {
        ModelFixture().use { f ->
            val d = Decoder();val loads = AtomicInteger();val e = engine(runtime(f,d,loads))
            assertTrue(e.awaitReady(2000));val sink = Sink()
            val result = e.recognize("turn",byteArrayOf(0,0,0,-128,-1,127),sink)
            assertEquals("打开车窗",result?.text);assertTrue(result!!.isFinal)
            assertArrayEquals(floatArrayOf(0f,-1f,32767f/32768f),d.samples)
            assertEquals(1,sink.transcripts.size);assertEquals(1,sink.established)
            d.output = "  ";assertNull(e.recognize("next",byteArrayOf(0,0),sink))
            assertEquals(1,sink.established);assertEquals(1,loads.get());assertEquals(2,d.calls.get())
            assertThrows(IllegalArgumentException::class.java) { e.recognize("odd",byteArrayOf(1),sink) }
            assertThrows(IllegalArgumentException::class.java) { e.recognize("large",ByteArray(960002),sink) }
            e.close();assertTrue(d.closed.await(2,TimeUnit.SECONDS))
        }
    }
    @Test fun `missing admission never executes and queued unfinished permit is checked at actual native boundary`() {
        ModelFixture().use { f ->
            val d = Decoder().apply { entered = CountDownLatch(1);proceed = CountDownLatch(1) };val r = runtime(f,d)
            val valid = java.util.concurrent.atomic.AtomicBoolean(true)
            val p = FireRedAdmissionProvider { id -> if (id == "missing") null else FireRedAdmission(Long.MAX_VALUE,{ id == "finalized" },{ valid.get() }) }
            val e = engine(r,p);assertTrue(e.awaitReady(2000))
            assertNull(e.recognize("missing",byteArrayOf(0,0),Sink()))
            val active = async { e.recognize("active",byteArrayOf(0,0),Sink()) };assertTrue(d.entered!!.await(2,TimeUnit.SECONDS))
            val pending = async { e.recognize("pending",byteArrayOf(0,0),Sink()) }
            val finalized = async { e.recognize("finalized",byteArrayOf(0,0),Sink()) }
            awaitPendingRequests(r, 3)
            valid.set(false);d.proceed!!.countDown()
            assertNull(active.get(2,TimeUnit.SECONDS));assertNull(pending.get(2,TimeUnit.SECONDS));assertNotNull(finalized.get(2,TimeUnit.SECONDS))
            assertEquals(2,d.calls.get());e.close()
        }
    }

    @Test fun `silence tokens do not produce a transcript or establish a turn`() {
        ModelFixture().use { fixture ->
            val decoder = Decoder().apply { output = "<sil><sil>" }
            engine(runtime(fixture, decoder)).use { engine ->
                assertTrue(engine.awaitReady(2000))
                val sink = Sink()
                assertNull(engine.recognize("silence", byteArrayOf(0, 0), sink))
                assertTrue(sink.transcripts.isEmpty())
                assertEquals(0, sink.established)

                decoder.output = "<sil>打开<sil>车窗<sil>"
                assertEquals("打开车窗", engine.recognize("speech", byteArrayOf(0, 0), sink)?.text)
                assertEquals(1, sink.established)
            }
        }
    }
    @Test fun `queue bounded close drops queued work and successor reuses active model without freeing JNI`() {
        ModelFixture().use { f ->
            val d = Decoder().apply { entered = CountDownLatch(1);proceed = CountDownLatch(1) };val loads = AtomicInteger();val r = runtime(f,d,loads)
            val old = engine(r);assertTrue(old.awaitReady(2000));val sink = Sink()
            val first = async { old.recognize("a",byteArrayOf(0,0),sink) };assertTrue(d.entered!!.await(2,TimeUnit.SECONDS))
            val q1 = async { old.recognize("b",byteArrayOf(0,0),sink) };val q2 = async { old.recognize("c",byteArrayOf(0,0),sink) }
            awaitPendingRequests(r, 3)
            assertThrows(RejectedExecutionException::class.java) { old.recognize("overflow",byteArrayOf(0,0),sink) }
            val start = System.nanoTime();old.close();assertTrue((System.nanoTime()-start)/1_000_000 < 200)
            assertEquals(1,d.closed.count);val next = engine(r)
            d.proceed!!.countDown();assertNull(first.get(2,TimeUnit.SECONDS));assertNull(q1.get(2,TimeUnit.SECONDS));assertNull(q2.get(2,TimeUnit.SECONDS))
            // Cancellation wakes the callers before the owner releases their queue slots.
            awaitPendingRequests(r, 0)
            assertTrue(next.awaitReady(2000));assertNotNull(next.recognize("new",byteArrayOf(0,0),Sink()))
            assertEquals(1,loads.get());assertEquals(2,d.calls.get());assertTrue(sink.transcripts.isEmpty())
            next.close();assertTrue(d.closed.await(2,TimeUnit.SECONDS))
        }
    }
    @Test fun `expired queued request and closed engine cannot begin native`() {
        ModelFixture().use { f ->
            val d = Decoder().apply { entered = CountDownLatch(1);proceed = CountDownLatch(1) };val r = runtime(f,d)
            val e = engine(r);assertTrue(e.awaitReady(2000));val active = async { e.recognize("a",byteArrayOf(0,0),Sink()) };assertTrue(d.entered!!.await(2,TimeUnit.SECONDS))
            val deadline = System.nanoTime()/1_000_000 + 50
            val expiring = engine(r,FireRedAdmissionProvider { FireRedAdmission(deadline,{true},{true}) })
            assertNull(expiring.recognize("expired",byteArrayOf(0,0),Sink()));expiring.close()
            d.proceed!!.countDown();active.get(2,TimeUnit.SECONDS);e.close()
            assertNull(e.recognize("closed",byteArrayOf(0,0),Sink()));assertEquals(1,d.calls.get())
        }
    }
    @Test fun `initialization timeout cannot be revived by late successful native construction or duplicate model load`() {
        ModelFixture().use { f ->
            val events = java.util.concurrent.CopyOnWriteArrayList<ComponentStatusEvent>();val entered = CountDownLatch(1);val proceed = CountDownLatch(1);val d = Decoder();val loads = AtomicInteger()
            val r = FireRedRuntime({ f.loader().load() },{ loads.incrementAndGet();entered.countDown();proceed.await(2,TimeUnit.SECONDS);d },{})
            val reporter = ComponentReporter(ComponentDescriptor("local.asr","firered","open"),1,ComponentStatusSink { events.add(it) })
            val old = FireRedAsrEngine(r,admitted,reporter,30);assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(old.awaitReady(200))
            val next = engine(r);proceed.countDown();assertTrue(next.awaitReady(2000))
            assertEquals(1,loads.get());assertEquals(Lifecycle.FAILED,events.last().update.lifecycle)
            assertEquals(ErrorCode.INIT_TIMEOUT,events.last().update.error?.code);assertNull(old.recognize("late",byteArrayOf(0,0),Sink()))
            old.close();next.close()
        }
    }
    @Test fun `closing from transcript callback cannot establish or return a stale final`() {
        ModelFixture().use { f ->
            val d = Decoder();val e = engine(runtime(f,d));assertTrue(e.awaitReady(2000))
            var transcripts = 0;var established = 0
            val result = e.recognize("close-in-callback",byteArrayOf(0,0),object : AsrSink {
                override fun onTranscript(result: AsrResult) { transcripts++;e.close() }
                override fun onTurnEstablished() { established++ }
            })
            assertEquals(1,transcripts);assertEquals(0,established);assertNull(result)
        }
    }

    @Test fun `initialization timeout cancels queued caller without waiting for blocked native constructor`() {
        ModelFixture().use { f ->
            val entered = CountDownLatch(1);val proceed = CountDownLatch(1);val d = Decoder()
            val r = FireRedRuntime({ f.loader().load() },{ entered.countDown();proceed.await(2,TimeUnit.SECONDS);d },{})
            val e = engine(r,timeout = 150);assertTrue(entered.await(1,TimeUnit.SECONDS))
            val pending = async { e.recognize("pending-init",byteArrayOf(0,0),Sink()) }
            try { assertNull(pending.get(500,TimeUnit.MILLISECONDS));assertEquals(0,d.calls.get()) }
            finally { proceed.countDown();e.close() }
        }
    }

}
