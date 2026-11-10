package com.autovoice.app.audio

import android.content.ContextWrapper
import com.autovoice.app.*
import com.autovoice.voicebusiness.dialog.DialogueSnapshot
import com.autovoice.audiofrontend.vad.VadEvent
import kotlinx.coroutines.test.runCurrent
import com.autovoice.audiofrontend.*
import com.autovoice.voicecore.health.ErrorCode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Controlled frontend boundary: verifies capture ownership/queue/abort, not native DSP or AudioRecord. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OpenCaptureBridgeTest {
    private class Front : AudioFrontendEngine, RenderReferenceSink {
        override val continuousProcessing = true
        override val ownsAuxiliaryVad = true
        override val vadAvailable = true
        private var observedEnd: Long? = null
        override val diagnostics get() = AudioFrontendDiagnostics(observedInputEndSample = observedEnd)
        var closes = 0
        @Volatile private var released = false
        val closedSignal = java.util.concurrent.CountDownLatch(1)
        var renderEntered: java.util.concurrent.CountDownLatch? = null
        var renderRelease: java.util.concurrent.CountDownLatch? = null
        val rendered = mutableListOf<Long>();val invalidated = mutableListOf<Long>()
        override fun acceptRender(frame: RenderReferenceFrame): Boolean {
            check(!released);renderEntered?.countDown();renderRelease?.await(2,java.util.concurrent.TimeUnit.SECONDS)
            check(!released);rendered.add(frame.playbackId);return true
        }
        override fun invalidateRender(playbackId: Long) { check(!released);invalidated.add(playbackId) }
        var calls = 0;var position = 0L;var aborts = 0;var fail = false;var finishes = 0
        val resets = mutableListOf<VadPurpose>()
        var auxiliary = emptySet<VadPurpose>()
        var mainEndAt: Long? = null
        var seed = emptyList<PositionedPcmBlock>()
        override fun resetVad(purpose: VadPurpose) { resets.add(purpose) }
        override fun startTurn(history: AudioInputHistory) {
            startTurn();observedEnd = history.observedEndSample
            seed = history.batch.inSampleRange(history.batch.processedBlocks.first().startSample,observedEnd ?: Long.MAX_VALUE).processedBlocks
            seed.forEach { input.write(it.pcm) };tracking = observedEnd == null
        }
        private var tracking = false
        private val input = java.io.ByteArrayOutputStream()
        override fun startTurn() { tracking = true;observedEnd = null;input.reset() }
        override fun process(block: ByteArray): FrontendBatch {
            calls++;check(!fail) { "controlled native failure" }
            val processed = ByteArray(block.size) { (block[it]+1).toByte() }
            val first = tracking && input.size() == 0
            if (tracking) input.write(processed)
            val observations = (if (first) listOf(VadObservation(position,512,true,events = listOf(VadEvent.SpeechStart))) else emptyList()) +
                auxiliary.map { VadObservation(position,512,true,events = listOf(VadEvent.SpeechStart),purpose = it) } +
                (if (position+512 == mainEndAt) listOf(VadObservation(position,512,false,events = listOf(VadEvent.SpeechEnd))) else emptyList())
            return FrontendBatch(listOf(PositionedPcmBlock(position,processed)),observations).also { position += block.size/2 }
        }
        override fun abortInput() { aborts++;tracking = false;input.reset() }
        override fun finishProcessedAudio() = ByteArray(0)
        override fun finishSegments(): List<ByteArray> { finishes++;tracking = false;return if (input.size() == 0) emptyList() else listOf(input.toByteArray()) }
        override fun close() { closes++;released = true;closedSignal.countDown() }
    }
    private fun sourceBlock(recorder: AudioRecorder,value: Int) {
        val method = AudioRecorder::class.java.getDeclaredMethod("handleCapturedBlock",ByteArray::class.java)
        method.isAccessible = true;method.invoke(recorder,ByteArray(1024) { value.toByte() })
    }
    private fun effects() = object : CaptureEffects {
        override val echoCancellationActive = false;override val noiseSuppressionActive = false
        override fun attach(audioSessionId: Int) = Unit
        override fun release() = Unit
    }
    @Test fun `idle and turn use one frontend once with ordered original input identity and exact samples`() = runTest {
        val f = Front();var creations = 0;var auxCreates = 0
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> creations++;f },vadFactory = { auxCreates++;null },captureEffectsFactory = { effects() })
        sourceBlock(r,1);sourceBlock(r,2)
        val idle = r.drainFrontendBatches()
        assertEquals(listOf(0L,512L),idle.map { it.batch.processedBlocks.single().startSample })
        assertTrue(idle.all { it.inputEpoch == null })
        assertTrue(r.start(false));val epoch = r.inputEpoch
        sourceBlock(r,3);sourceBlock(r,4)
        r.stop()
        val active = r.drainFrontendBatches()
        assertTrue(active.all { it.inputEpoch == epoch });assertNotNull(epoch)
        assertEquals(listOf(1024L,1536L),active.map { it.batch.processedBlocks.single().startSample })
        assertArrayEquals(ByteArray(1024) { 4 },active.first().batch.processedPcm)
        assertEquals(4,f.calls);assertEquals(1,creations);assertEquals(0,auxCreates)
        r.close()
    }
    @Test fun `queue overflow is explicit unfinished input failure and no more native work begins`() = runTest {
        val f = Front()
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() },frontendQueueCapacity = 1)
        assertTrue(r.start(false));val epoch = r.inputEpoch
        sourceBlock(r,1);sourceBlock(r,2);sourceBlock(r,3)
        assertEquals(ErrorCode.RESOURCE_EXHAUSTED,r.frontendFailure?.error?.code)
        assertEquals(epoch,r.frontendFailure?.inputEpoch)
        val features = com.autovoice.app.health.CapabilityEvaluator.evaluate(com.autovoice.voicecore.health.ComponentHealthSnapshot(),true,
            cloudRouteEnabled = true,capturePathUnavailable = r.frontendFailure != null)
        assertFalse(features.canTriggerManual)
        assertEquals(com.autovoice.voicecore.health.CapabilityAvailability.UNAVAILABLE,features.features["local"])
        assertEquals(com.autovoice.voicecore.health.CapabilityAvailability.UNAVAILABLE,features.features["cloud"])
        assertEquals(2,f.calls);assertEquals(1,f.aborts);assertEquals(0,f.finishes)
        assertFalse(r.start(false));assertTrue(r.finishSegments().isEmpty())
        r.close()
    }
    @Test fun `processing exception after success aborts instead of silently continuing truncated audio`() = runTest {
        val f = Front()
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        assertTrue(r.start(false));sourceBlock(r,1);f.fail = true;sourceBlock(r,2);sourceBlock(r,3)
        assertEquals(ErrorCode.COMPONENT_RUNTIME_FAILED,r.frontendFailure?.error?.code)
        assertEquals(2,f.calls);assertEquals(1,f.aborts);assertTrue(r.finishSegments().isEmpty());assertFalse(r.start(false))
        r.close()
    }
    private class Pipeline : RecordingPipeline {
        override val dialogueSnapshot = DialogueSnapshot()
        val local = mutableListOf<ByteArray>();val cloud = mutableListOf<ByteArray>()
        val streamed = java.io.ByteArrayOutputStream();var finish = 0;var cancels = 0
        override fun onWake() = Unit
        override fun onListeningStart(interruptPlayback: Boolean) = Unit
        override fun onListeningStop() = Unit
        override fun onVadStart() = Unit
        override fun onVadEnd() = Unit
        override fun appendStreamingCloudAudio(block: ByteArray) { streamed.write(block) }
        override fun finishStreamingCloudAudio() { finish++ }
        override fun cancelStreamingCloudAudio() { cancels++ }
        override fun onCloudSegment(segment: ByteArray) { cloud.add(segment) }
        override fun onTurnSegment(segment: ByteArray) { local.add(segment) }
        override fun appendRealtimeChatAudio(block: ByteArray) = Unit
        override fun startRealtimeChat() = Unit
        override fun finishRealtimeChat() = Unit
        override fun onFollowUpExpired(interactionId: String, taskRevision: Long?) = Unit
        override fun onInteractionExpired(interactionId: String) = Unit
        override fun resetDialogue() = Unit
    }
    private fun wake() = object : WakeWordPort {
        override val keyword = "test"
        override fun initialize() = Unit
        override fun arm() = Unit
        override fun accept(pcm: ByteArray) = Unit
        override fun pause() = Unit
        override fun disarm() = Unit
        override fun close() = Unit
    }
    @Test fun `stop synchronously drains unconsumed Open batches and processed local and cloud samples match exactly once`() = runTest {
        val f = Front();val p = Pipeline()
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        val c = RecordingCoordinator(r,wake(),p,backgroundScope,{false},{},elapsedRealtimeMs = { 0L })
        c.startManualTurn()
        repeat(10) { sourceBlock(r,it) }
        c.stopTurn() // Collector has not been scheduled yet: stop must drain the owned FIFO.
        val expected = (0 until 10).flatMap { value -> List(1024) { (value+1).toByte() } }.toByteArray()
        assertEquals(1,p.local.size);assertArrayEquals(expected,p.local.single())
        assertEquals(1,p.cloud.size);assertArrayEquals(expected,p.cloud.single())
        assertArrayEquals(expected,p.streamed.toByteArray());assertEquals(1,p.finish)
        runCurrent();assertEquals(1,p.local.size);assertEquals(1,p.finish)
        c.close()
    }
    @Test fun `native failure after sufficient audio cancels unfinalized input and never submits a truncated candidate`() = runTest {
        val f = Front();val p = Pipeline()
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        val c = RecordingCoordinator(r,wake(),p,backgroundScope,{false},{},elapsedRealtimeMs = { 0L })
        c.startManualTurn();repeat(10) { sourceBlock(r,it) };f.fail = true;sourceBlock(r,11)
        runCurrent()
        assertFalse(c.isRecording)
        c.stopTurn();assertTrue(p.local.isEmpty());assertTrue(p.cloud.isEmpty());assertEquals(0,p.finish)
        assertTrue(p.cancels > 0);assertEquals(11,f.calls)
        c.close()
    }

    @Test fun `auxiliary confirmation then delayed start seeds all already processed history once across live boundary`() = runTest {
        val f = Front().apply { auxiliary = setOf(VadPurpose.FOLLOW_UP) }
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { error("Open cannot construct legacy auxiliary VAD") },captureEffectsFactory = { effects() })
        r.setFollowUpListening(true);sourceBlock(r,1);sourceBlock(r,2)
        val confirmed = r.drainFrontendBatches().first()
        assertTrue(r.detectAuxiliarySpeech(confirmed,VadPurpose.FOLLOW_UP))
        sourceBlock(r,3);sourceBlock(r,4);sourceBlock(r,5) // DSP continues before the queued start is handled.
        val before = f.calls;assertTrue(r.start(true));assertEquals(before,f.calls)
        sourceBlock(r,6);r.stop()
        val input = r.drainFrontendBatches().filter { it.inputEpoch == r.inputEpoch }
        val delivered = input.flatMap { it.batch.processedPcm.asIterable() }.toByteArray()
        val expected = (1..6).flatMap { value -> List(1024) { (value+1).toByte() } }.toByteArray()
        assertArrayEquals(expected,delivered);assertArrayEquals(expected,r.finishSegments().single())
        assertTrue(input.first().speechConfirmedForInput)
        assertEquals(0L,f.seed.first().startSample)
        assertEquals(2560L,f.seed.last().let { it.startSample+it.validSamples })
        assertEquals(6,f.calls);assertEquals(listOf(VadPurpose.FOLLOW_UP),f.resets)
        r.close()
    }
    @Test fun `stale auxiliary observation cannot reopen a new listening epoch or bypass unverified open mic policy`() = runTest {
        val f = Front().apply { auxiliary = setOf(VadPurpose.FOLLOW_UP,VadPurpose.BARGE_IN) }
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        r.setFollowUpListening(true);sourceBlock(r,1);val old = r.drainFrontendBatches().single()
        r.setFollowUpListening(false);r.setFollowUpListening(true)
        assertFalse(r.detectAuxiliarySpeech(old,VadPurpose.FOLLOW_UP))
        r.setOpenMicBargeInListening(true);sourceBlock(r,2);val fresh = r.drainFrontendBatches().single()
        assertFalse(r.openMicBargeInAvailable);assertFalse(r.detectAuxiliarySpeech(fresh,VadPurpose.BARGE_IN))
        assertTrue(r.detectAuxiliarySpeech(fresh,VadPurpose.FOLLOW_UP))
        assertEquals(listOf(VadPurpose.FOLLOW_UP,VadPurpose.FOLLOW_UP,VadPurpose.BARGE_IN),f.resets)
        r.close()
    }

    @Test fun `historical real end is replayed to owner and closes original input excluding later idle without another DSP call`() = runTest {
        val f = Front().apply { auxiliary = setOf(VadPurpose.FOLLOW_UP);mainEndAt = 5120 }
        val p = Pipeline();val states = mutableListOf<RecordingLifecycleSnapshot>()
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        val c = RecordingCoordinator(r,wake(),p,backgroundScope,{false},states::add,elapsedRealtimeMs = { 0L })
        r.setFollowUpListening(true);sourceBlock(r,1);sourceBlock(r,2)
        val confirmation = r.drainFrontendBatches().first()
        assertTrue(r.detectAuxiliarySpeech(confirmation,VadPurpose.FOLLOW_UP))
        for (value in 3..15) sourceBlock(r,value) // Main end at5120, then2560 further idle samples.
        val calls = f.calls
        // Controlled start callback after real auxiliary detection; foreground UI ownership is tested separately.
        val start = RecordingCoordinator::class.java.getDeclaredMethod("startTurn",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
        start.isAccessible = true;start.invoke(c,true,true,false)
        assertFalse(c.isRecording)
        val expected = (1..10).flatMap { value -> List(1024) { (value+1).toByte() } }.toByteArray()
        assertArrayEquals(expected,p.local.single());assertArrayEquals(expected,p.cloud.single())
        assertArrayEquals(expected,p.streamed.toByteArray());assertEquals(1,p.finish)
        assertEquals(calls,f.calls);assertFalse(states.last().recording);assertFalse(states.last().permissionRequired)
        runCurrent();assertEquals(calls,f.calls);assertEquals(1,p.local.size)
        c.close()
    }

    @Test fun `explicit fresh frontend reconfiguration clears sticky path failure and cannot reuse old queued input`() = runTest {
        val first = Front();val second = Front();var created = 0
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> listOf(first,second)[created++] },vadFactory = { null },captureEffectsFactory = { effects() })
        assertTrue(r.start(false));val oldEpoch = r.inputEpoch;sourceBlock(r,1);first.fail = true;sourceBlock(r,2)
        assertNotNull(r.frontendFailure);assertFalse(r.start(false))
        r.stopMonitoring() // Same explicit pause boundary used by Coordinator mode reconfiguration.
        r.configureAudioProcessing(com.autovoice.voicecore.VadConfig(),com.autovoice.voicecore.DemoConfig.ECNR_RNNOISE)
        assertEquals(2,created);assertEquals(1,first.closes);assertNull(r.frontendFailure)
        assertTrue(r.start(false));sourceBlock(r,3)
        val input = r.drainFrontendBatches().single()
        assertNotEquals(oldEpoch,input.inputEpoch);assertEquals(0L,input.batch.processedBlocks.single().startSample)
        assertArrayEquals(ByteArray(1024) { 4 },input.batch.processedPcm)
        r.close();assertEquals(1,second.closes)
    }

    @Test fun `render invalidation survives frontend replacement while current output identity remains usable`() = runTest {
        val first = Front();val second = Front();var created = 0
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = null,scope = backgroundScope,
            frontendFactory = { _,_,_ -> listOf(first,second)[created++] },vadFactory = { null },captureEffectsFactory = { effects() })
        fun frame(id: Long,start: Long = 0) = RenderReferenceFrame(id,start,System.nanoTime(),ByteArray(320))
        assertTrue(r.acceptRender(frame(7)))
        r.invalidateRender(8);assertFalse(r.acceptRender(frame(7,160)))
        r.configureAudioProcessing(com.autovoice.voicecore.VadConfig(minSpeechMs = 96),com.autovoice.voicecore.DemoConfig.ECNR_RNNOISE)
        assertFalse(r.acceptRender(frame(7,320)));assertFalse(r.acceptRender(frame(8)))
        assertTrue(r.acceptRender(frame(9,480)))
        r.invalidateRender(7);assertTrue(r.acceptRender(frame(9,640)))
        assertEquals(listOf(7L),first.rendered);assertEquals(listOf(9L,9L),second.rendered)
        r.close();assertFalse(r.acceptRender(frame(10)));r.invalidateRender(10)
        assertEquals(1,first.closes);assertEquals(1,second.closes)
    }
    @Test fun `render forwarding and close serialize so no released frontend is invoked`() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1);val release = java.util.concurrent.CountDownLatch(1)
        val f = Front().apply { renderEntered = entered;renderRelease = release }
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = null,scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val forward = pool.submit<Boolean> { r.acceptRender(RenderReferenceFrame(1,0,System.nanoTime(),ByteArray(320))) }
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
            val closing = pool.submit { r.close() }
            assertFalse(f.closedSignal.await(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            release.countDown();assertTrue(forward.get(1,java.util.concurrent.TimeUnit.SECONDS));closing.get(1,java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(r.acceptRender(RenderReferenceFrame(2,0,System.nanoTime(),ByteArray(320))))
            assertEquals(1,f.closes)
        } finally { release.countDown();r.close();pool.shutdownNow() }
    }
    @Test fun `old capture stream frame cannot enter fresh frontend or acquire the new input epoch`() = runTest {
        val first = Front();val second = Front();var created = 0
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> listOf(first,second)[created++] },vadFactory = { null },captureEffectsFactory = { effects() })
        val field = AudioRecorder::class.java.getDeclaredField("captureStreamEpoch").apply { isAccessible = true }
        assertTrue(r.start(false));val old = (field.get(r) as java.util.concurrent.atomic.AtomicLong).get()
        r.abortInput();r.configureAudioProcessing(com.autovoice.voicecore.VadConfig(minSpeechMs = 96),com.autovoice.voicecore.DemoConfig.ECNR_RNNOISE)
        assertTrue(r.start(false));val current = (field.get(r) as java.util.concurrent.atomic.AtomicLong).get();assertNotEquals(old,current)
        val delayed = AudioRecorder::class.java.getDeclaredMethod("handleCapturedBlock",ByteArray::class.java,Long::class.javaPrimitiveType).apply { isAccessible = true }
        delayed.invoke(r,ByteArray(1024) { 99 },old)
        assertEquals(0,second.calls);assertTrue(r.drainFrontendBatches().isEmpty())
        sourceBlock(r,3);assertEquals(1,second.calls)
        assertArrayEquals(ByteArray(1024) { 4 },r.drainFrontendBatches().single().batch.processedPcm)
        r.close()
    }

    @Test fun `inline raw observer runs after processing lock and rejected blocks publish nothing`() = runTest {
        val f = Front()
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = null,scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        val lock = AudioRecorder::class.java.getDeclaredField("turnProcessingLock").apply { isAccessible = true }.get(r)
        val held = mutableListOf<Boolean>()
        val processedBeforeRaw = mutableListOf<Int>()
        val observer = backgroundScope.launch(Dispatchers.Unconfined,start = CoroutineStart.UNDISPATCHED) {
            r.rawPcmBlocks.collect {
                held.add(Thread.holdsLock(lock));processedBeforeRaw.add(f.calls)
                if (it[0] == 2.toByte()) r.close()
            }
        }
        val delayed = AudioRecorder::class.java.getDeclaredMethod("handleCapturedBlock",ByteArray::class.java,Long::class.javaPrimitiveType).apply { isAccessible = true }
        delayed.invoke(r,ByteArray(1024),-1L);assertTrue(held.isEmpty())
        sourceBlock(r,1)
        assertEquals(listOf(false),held);assertEquals(listOf(0),processedBeforeRaw);assertEquals(1,f.calls)
        sourceBlock(r,2) // Inline observer closes capture: the original admitted owner cannot enter DSP.
        assertEquals(listOf(false,false),held);assertEquals(listOf(0,1),processedBeforeRaw);assertEquals(1,f.calls)
        sourceBlock(r,3);assertEquals(2,held.size)
        observer.cancel()
    }

    @Test fun `block arriving after initial drain joins atomic normal completion exactly once`() = runTest {
        val f = Front();val p = Pipeline()
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        var inject = false
        val capture = object : RecordingCapture by r {
            override fun drainFrontendBatches(): List<CaptureFrontendBatch> {
                val drained = r.drainFrontendBatches()
                if (inject) { inject = false;sourceBlock(r,10) }
                return drained
            }
        }
        val c = RecordingCoordinator(capture,wake(),p,backgroundScope,{false},{},elapsedRealtimeMs = { 0L })
        c.startManualTurn();repeat(10) { sourceBlock(r,it) };inject = true
        c.stopTurn()
        val expected = (0..10).flatMap { value -> List(1024) { (value+1).toByte() } }.toByteArray()
        assertArrayEquals(expected,p.local.single());assertArrayEquals(expected,p.cloud.single())
        assertArrayEquals(expected,p.streamed.toByteArray());assertEquals(1,p.finish)
        c.close()
    }

    @Test fun `idle failure after snapshot cannot erase normally closed input before consumption`() = runTest {
        val f = Front();val p = Pipeline()
        val r = AudioRecorder(ContextWrapper(null),testAudioSource = TestAudioSource(ByteArray(1024)),scope = backgroundScope,
            frontendFactory = { _,_,_ -> f },vadFactory = { null },captureEffectsFactory = { effects() })
        val capture = object : RecordingCapture by r {
            override fun stopAndSnapshot(): CaptureCompletion? {
                val snapshot = r.stopAndSnapshot()
                f.fail = true;sourceBlock(r,99)
                return snapshot
            }
            override fun finishProcessedAudio(): ByteArray {
                f.fail = true;sourceBlock(r,99)
                return r.finishProcessedAudio()
            }
        }
        val c = RecordingCoordinator(capture,wake(),p,backgroundScope,{false},{},elapsedRealtimeMs = { 0L })
        c.startManualTurn();val epoch = r.inputEpoch;repeat(10) { sourceBlock(r,it) }
        c.stopTurn()
        val expected = (0..9).flatMap { value -> List(1024) { (value+1).toByte() } }.toByteArray()
        assertArrayEquals(expected,p.local.single());assertArrayEquals(expected,p.cloud.single())
        assertArrayEquals(expected,p.streamed.toByteArray());assertEquals(1,p.finish)
        assertNotNull(r.frontendFailure);assertNull(r.frontendFailure?.inputEpoch);assertNotNull(epoch)
        assertFalse(r.start(false));c.close()
    }

}
