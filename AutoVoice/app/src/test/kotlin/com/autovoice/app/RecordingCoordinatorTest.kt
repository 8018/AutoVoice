package com.autovoice.app

import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.voicecore.dialog.DialogueSnapshot
import com.autovoice.voicecore.dialog.DialogueState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingCoordinatorTest {
    @Test fun `wake turn owns capture then routes vad and completed audio in order`() = runTest {
        val capture = FakeCapture().apply {
            segments = listOf(byteArrayOf(7, 8))
            processedTail = ByteArray(64) { 2 }
        }
        val wake = FakeWakeWord()
        val pipeline = FakePipeline()
        val states = mutableListOf<RecordingLifecycleSnapshot>()
        val coordinator = coordinator(capture, wake, pipeline) { states += it }
        runCurrent()

        coordinator.onForeground()
        runCurrent()
        assertTrue(wake.armed)

        coordinator.deliverWake("你好飞飞")
        assertEquals(listOf(false), capture.startPreRoll)
        assertEquals(listOf("wake", "listening:true"), pipeline.events.take(2))

        repeat(10) { capture.pcm.emit(ByteArray(960) { 1 }) }
        runCurrent()
        capture.vad.emit(VadEvent.SpeechStart)
        runCurrent()
        capture.vad.emit(VadEvent.SpeechEnd)
        runCurrent()

        assertEquals(10, pipeline.streamingBlocks)
        assertEquals(1, pipeline.finishStreaming)
        assertEquals(listOf(byteArrayOf(7, 8).toList()), pipeline.cloudSegments.map(ByteArray::toList))
        assertEquals(9_664, pipeline.turnSegments.single().size)
        assertFalse(states.last().recording)
    }

    @Test fun `realtime chat exclusively receives raw pcm and resumes wake after exit`() = runTest {
        val capture = FakeCapture()
        val wake = FakeWakeWord()
        val pipeline = FakePipeline()
        val coordinator = coordinator(capture, wake, pipeline) {}
        runCurrent()
        coordinator.onForeground()
        runCurrent()

        assertTrue(coordinator.setChatMode(true))
        capture.raw.emit(byteArrayOf(1, 2, 3))
        capture.pcm.emit(ByteArray(960))
        runCurrent()

        assertEquals(1, pipeline.startRealtime)
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), pipeline.realtimeBlocks.map(ByteArray::toList))
        assertTrue(pipeline.turnSegments.isEmpty())

        assertTrue(coordinator.setChatMode(false))
        runCurrent()
        assertEquals(1, pipeline.finishRealtime)
        assertEquals(1, pipeline.dialogueResets)
        assertTrue(wake.armed)
    }

    @Test fun `follow up timer expires only matching interaction`() = runTest {
        val capture = FakeCapture()
        val pipeline = FakePipeline()
        val coordinator = coordinator(capture, FakeWakeWord(), pipeline) {}
        runCurrent()
        coordinator.onForeground()
        runCurrent()

        val waiting = DialogueSnapshot(DialogueState.FOLLOW_UP_LISTENING, "interaction", "turn")
        pipeline.dialogueSnapshot = waiting
        coordinator.onDialogueState(waiting, taskDirective = null)
        assertTrue(capture.followUpEnabled)

        advanceTimeBy(999)
        runCurrent()
        assertTrue(pipeline.expiredInteractions.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("interaction"), pipeline.expiredInteractions)
        assertEquals(listOf<Long?>(null), pipeline.expiredTaskRevisions)
    }

    @Test fun `task listening directive controls window and preserves revision`() = runTest {
        val capture = FakeCapture()
        val pipeline = FakePipeline()
        val coordinator = coordinator(capture, FakeWakeWord(), pipeline) {}
        runCurrent()
        coordinator.onForeground()
        runCurrent()

        val waiting = DialogueSnapshot(DialogueState.FOLLOW_UP_LISTENING, "interaction", "turn")
        pipeline.dialogueSnapshot = waiting
        coordinator.onDialogueState(waiting, TaskListeningDirective(revision = 7, listenWindowMs = 2_000))

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(pipeline.expiredInteractions.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("interaction"), pipeline.expiredInteractions)
        assertEquals(listOf<Long?>(7), pipeline.expiredTaskRevisions)
    }

    @Test fun `capture start failure rolls back pipeline and reports permission`() = runTest {
        val capture = FakeCapture().apply { startResult = false }
        val pipeline = FakePipeline()
        val states = mutableListOf<RecordingLifecycleSnapshot>()
        val coordinator = coordinator(capture, FakeWakeWord(), pipeline) { states += it }
        runCurrent()

        coordinator.startManualTurn()

        assertEquals(listOf("wake", "listening:true", "listening:false"), pipeline.events)
        assertEquals(1, pipeline.dialogueResets)
        assertTrue(states.last().permissionRequired)
        assertFalse(coordinator.isRecording)
    }

    @Test fun `capture configuration pauses monitoring and reports new capability before rearm`() = runTest {
        val capture = FakeCapture()
        val wake = FakeWakeWord()
        val pipeline = FakePipeline()
        val states = mutableListOf<RecordingLifecycleSnapshot>()
        val coordinator = coordinator(capture, wake, pipeline) { states += it }
        runCurrent()
        coordinator.onForeground()
        runCurrent()
        assertTrue(capture.monitoring)
        assertTrue(wake.armed)

        coordinator.reconfigureCapture {
            assertFalse(capture.monitoring, "配置替换前必须先释放共享麦克风")
            capture.vadAvailable = false
        }

        assertFalse(wake.armed)
        assertTrue(states.last().vadUnavailable)
        coordinator.onDialogueState(DialogueSnapshot(), taskDirective = null)
        runCurrent()
        assertTrue(capture.monitoring)
        assertTrue(wake.armed)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        capture: FakeCapture,
        wake: FakeWakeWord,
        pipeline: FakePipeline,
        onState: (RecordingLifecycleSnapshot) -> Unit,
    ) = RecordingCoordinator(
        capture = capture,
        wakeWord = wake,
        pipeline = pipeline,
        // 协调器持有常驻监控任务,注入 backgroundScope(共享调度器,测试体结束自动取消)
        scope = backgroundScope,
        isPlaybackSpeaking = { false },
        onState = onState,
        elapsedRealtimeMs = { testScheduler.currentTime },
        timing = RecordingTiming(
            wakeTurnTimeoutMs = 5_000,
            followUpListenMs = 1_000,
            maxInteractionMs = 10_000,
        ),
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class FakeCapture : RecordingCapture {
        val pcm = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        val raw = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        val vad = MutableSharedFlow<VadEvent>(extraBufferCapacity = 16)
        override val pcmBlocks = pcm
        override val rawPcmBlocks = raw
        override val vadEvents = vad
        override var vadAvailable = true
        override var openMicBargeInAvailable = true
        var startResult = true
        var segments = emptyList<ByteArray>()
        var processedTail = byteArrayOf()
        var monitoring = false
        var followUpEnabled = false
        var bargeInListening = false
        val startPreRoll = mutableListOf<Boolean>()

        override fun startMonitoring(): Boolean { monitoring = true; return true }
        override fun stopMonitoring() { monitoring = false }
        override fun setOpenMicBargeInListening(enabled: Boolean) { bargeInListening = enabled }
        override fun setFollowUpListening(enabled: Boolean) { followUpEnabled = enabled }
        override fun detectOpenMicBargeIn(block: ByteArray) = false
        override fun detectFollowUpSpeech(block: ByteArray) = false
        override fun start(includeBargeInPreRoll: Boolean): Boolean {
            startPreRoll += includeBargeInPreRoll
            return startResult
        }
        override fun stop() = Unit
        override fun finishProcessedAudio() = processedTail
        override fun finishSegments() = segments
        override fun close() = Unit
    }

    private class FakeWakeWord : WakeWordPort {
        override val keyword = "你好飞飞"
        var initialized = false
        var armed = false
        override fun initialize() { initialized = true }
        override fun arm() { armed = true }
        override fun accept(pcm: ByteArray) = Unit
        override fun pause() { armed = false }
        override fun disarm() { armed = false }
        override fun close() = Unit
    }

    private class FakePipeline : RecordingPipeline {
        override var dialogueSnapshot = DialogueSnapshot()
        val events = mutableListOf<String>()
        var streamingBlocks = 0
        var finishStreaming = 0
        val cloudSegments = mutableListOf<ByteArray>()
        val turnSegments = mutableListOf<ByteArray>()
        val realtimeBlocks = mutableListOf<ByteArray>()
        var startRealtime = 0
        var finishRealtime = 0
        var dialogueResets = 0
        val expiredInteractions = mutableListOf<String>()
        val expiredTaskRevisions = mutableListOf<Long?>()

        override fun onWake() { events += "wake" }
        override fun onListeningStart(interruptPlayback: Boolean) { events += "listening:$interruptPlayback" }
        override fun onListeningStop() { events += "listening:false" }
        override fun onVadStart() { events += "vad:start" }
        override fun onVadEnd() { events += "vad:end" }
        override fun appendStreamingCloudAudio(block: ByteArray) { streamingBlocks++ }
        override fun finishStreamingCloudAudio() { finishStreaming++ }
        override fun cancelStreamingCloudAudio() { events += "stream:cancel" }
        override fun onCloudSegment(segment: ByteArray) { cloudSegments += segment }
        override fun onTurnSegment(segment: ByteArray) { turnSegments += segment }
        override fun appendRealtimeChatAudio(block: ByteArray) { realtimeBlocks += block }
        override fun startRealtimeChat() { startRealtime++ }
        override fun finishRealtimeChat() { finishRealtime++ }
        override fun onFollowUpExpired(interactionId: String, taskRevision: Long?) {
            expiredInteractions += interactionId
            expiredTaskRevisions += taskRevision
        }
        override fun resetDialogue() {
            dialogueResets++
            dialogueSnapshot = DialogueSnapshot()
        }
    }
}
