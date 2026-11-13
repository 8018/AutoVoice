package com.autovoice.app

import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.voicebusiness.dialog.DialogueSnapshot
import com.autovoice.voicebusiness.dialog.DialogueState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingCoordinatorTest {
    @Test fun `preparing response accepts ordinary speech without another wake and cancels pending playback`() = runTest {
        val capture = FakeCapture().apply { detectFollowUp = { followUpEnabled } }
        val pipeline = FakePipeline()
        val coordinator = coordinator(capture, FakeWakeWord(), pipeline) {}
        runCurrent();coordinator.onForeground();runCurrent()
        val preparing = DialogueSnapshot(DialogueState.RESPONDING, "interaction", "choices")
        pipeline.dialogueSnapshot = preparing
        coordinator.onDialogueState(preparing)
        assertTrue(capture.followUpEnabled)
        capture.raw.emit(ByteArray(960));runCurrent()
        assertTrue(coordinator.isRecording)
        assertEquals(listOf(true), capture.startPreRoll)
        assertEquals(listOf("listening:true"), pipeline.events)
        // Business cancellation of the pending reply opens a user window; noise is not admission.
        val waiting = DialogueSnapshot(DialogueState.LISTENING, "interaction")
        pipeline.dialogueSnapshot = waiting
        coordinator.onDialogueState(waiting)
        coordinator.stopTurn()
        assertTrue(capture.followUpEnabled)
        assertFalse(coordinator.isRecording)
    }

    @Test fun `queued silent preparation VAD cannot bypass echo protection after playback starts`() = runTest {
        val capture = FakeCapture()
        val pipeline = FakePipeline()
        var speaking = false
        val coordinator = RecordingCoordinator(capture, FakeWakeWord(), pipeline, backgroundScope,
            isPlaybackSpeaking = { speaking }, onState = {}, ioDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent();coordinator.onForeground();runCurrent()
        val preparing = DialogueSnapshot(DialogueState.RESPONDING, "interaction", "choices")
        pipeline.dialogueSnapshot = preparing
        coordinator.onDialogueState(preparing)
        capture.detectFollowUp = {
            speaking = true
            coordinator.onPlaybackStage(com.autovoice.tts.PlaybackStage.STARTED)
            true
        }
        capture.raw.emit(ByteArray(960));runCurrent()
        assertFalse(coordinator.isRecording)
        assertFalse(capture.followUpEnabled)
        assertTrue(capture.bargeInListening)
        assertTrue(capture.startPreRoll.isEmpty())
    }

    @Test fun `terminal playback callback rearms follow up after dialogue callback precedes playback flag`() = runTest {
        val capture = FakeCapture()
        val pipeline = FakePipeline()
        var speaking = true
        val coordinator = RecordingCoordinator(capture, FakeWakeWord(), pipeline, backgroundScope,
            isPlaybackSpeaking = { speaking }, onState = {}, ioDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent();coordinator.onForeground();runCurrent()
        val waiting = DialogueSnapshot(DialogueState.LISTENING, "interaction")
        pipeline.dialogueSnapshot = waiting
        coordinator.onDialogueState(waiting)
        assertFalse(capture.followUpEnabled)
        speaking = false
        coordinator.onPlaybackStage(com.autovoice.tts.PlaybackStage.COMPLETED)
        assertTrue(capture.followUpEnabled)
    }

    @Test fun `unadmitted barge in keeps playback running and rearms after noise capture`() = runTest {
        val capture = FakeCapture()
        val pipeline = FakePipeline()
        val coordinator = RecordingCoordinator(capture, FakeWakeWord(), pipeline, backgroundScope,
            isPlaybackSpeaking = { true }, onState = {}, ioDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        coordinator.onForeground()
        runCurrent()
        val speaking = DialogueSnapshot(DialogueState.SPEAKING, "interaction", "reply")
        pipeline.dialogueSnapshot = speaking
        coordinator.onDialogueState(speaking)
        coordinator.onPlaybackStage(com.autovoice.tts.PlaybackStage.STARTED)
        capture.raw.emit(ByteArray(960))
        runCurrent()
        assertTrue(coordinator.isRecording)
        assertEquals(listOf(true), capture.startPreRoll)
        assertEquals(listOf("listening:false"), pipeline.events)
        coordinator.stopTurn()
        assertFalse(coordinator.isRecording)
        assertTrue(capture.bargeInListening, "a false VAD candidate must not consume the only interruption chance")
        capture.raw.emit(ByteArray(960))
        runCurrent()
        assertEquals(listOf(true, true), capture.startPreRoll)
    }

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

    @Test fun `dialogue exit disables follow up and rearms wake`() = runTest {
        val capture = FakeCapture()
        val wake = FakeWakeWord()
        val pipeline = FakePipeline()
        val coordinator = coordinator(capture, wake, pipeline) {}
        runCurrent()
        coordinator.onForeground()
        runCurrent()

        val waiting = DialogueSnapshot(DialogueState.LISTENING, "interaction")
        pipeline.dialogueSnapshot = waiting
        coordinator.onDialogueState(waiting)
        assertTrue(capture.followUpEnabled)

        pipeline.dialogueSnapshot = DialogueSnapshot()
        coordinator.onDialogueState(pipeline.dialogueSnapshot)
        runCurrent()

        assertFalse(capture.followUpEnabled)
        assertTrue(wake.armed)
    }

    @Test fun `microphone readiness failure closes the interaction`() = runTest {
        val capture = FakeCapture()
        val pipeline = FakePipeline()
        val coordinator = coordinator(capture, FakeWakeWord(), pipeline) {}
        runCurrent()
        coordinator.onForeground()
        runCurrent()
        capture.monitoringResult = false
        val waiting = DialogueSnapshot(DialogueState.LISTENING, "interaction")
        pipeline.dialogueSnapshot = waiting
        coordinator.onDialogueState(waiting)
        assertEquals(listOf("interaction"), pipeline.hardExpiredInteractions)
        assertFalse(capture.followUpEnabled)
    }

    @Test fun `queued follow up detection cannot reopen capture after dialogue exit`() = runTest {
        val capture = FakeCapture()
        val wake = FakeWakeWord()
        val pipeline = FakePipeline()
        val coordinator = coordinator(capture, wake, pipeline) {}
        runCurrent()
        coordinator.onForeground()
        runCurrent()
        val waiting = DialogueSnapshot(DialogueState.LISTENING, "interaction")
        pipeline.dialogueSnapshot = waiting
        coordinator.onDialogueState(waiting)
        capture.detectFollowUp = {
            // Detection was already in flight when the exit semantic reset the dialogue.
            pipeline.dialogueSnapshot = DialogueSnapshot()
            coordinator.onDialogueState(pipeline.dialogueSnapshot)
            true
        }
        capture.raw.emit(byteArrayOf(1, 2))
        runCurrent()

        assertFalse(coordinator.isRecording)
        assertFalse(capture.followUpEnabled)
        assertTrue(capture.startPreRoll.isEmpty())
        assertTrue(wake.armed)
    }

    @Test fun `follow up detection still starts capture while dialogue is listening`() = runTest {
        val capture = FakeCapture().apply { detectFollowUp = { true } }
        val pipeline = FakePipeline()
        val coordinator = coordinator(capture, FakeWakeWord(), pipeline) {}
        runCurrent()
        coordinator.onForeground()
        runCurrent()
        val waiting = DialogueSnapshot(DialogueState.LISTENING, "interaction")
        pipeline.dialogueSnapshot = waiting
        coordinator.onDialogueState(waiting)
        capture.raw.emit(byteArrayOf(1, 2))
        runCurrent()
        assertTrue(coordinator.isRecording)
        assertEquals(listOf(true), capture.startPreRoll)
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
        coordinator.onDialogueState(DialogueSnapshot())
        runCurrent()
        assertTrue(capture.monitoring)
        assertTrue(wake.armed)
    }

    @Test fun `cancelled wake setup cannot arm or stop a replacement setup`() = runTest {
        val capture = FakeCapture()
        val wake = FakeWakeWord()
        val states = mutableListOf<RecordingLifecycleSnapshot>()
        val coordinator = coordinator(capture, wake, FakePipeline()) { states += it }
        wake.onInitialize = {
            wake.onInitialize = {}
            coordinator.reconfigureCapture {}
            coordinator.onForeground()
        }
        coordinator.onForeground()
        runCurrent()
        assertEquals(1, wake.armCalls)
        assertTrue(capture.monitoring)
        assertTrue(wake.armed)
        assertTrue(states.none { it.wakeError != null })
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
        var detectFollowUp: () -> Boolean = { false }
        val startPreRoll = mutableListOf<Boolean>()

        var monitoringResult = true
        override fun startMonitoring(): Boolean {
            monitoring = monitoringResult
            return monitoringResult
        }
        override fun stopMonitoring() { monitoring = false }
        override fun setOpenMicBargeInListening(enabled: Boolean) { bargeInListening = enabled }
        override fun setFollowUpListening(enabled: Boolean) { followUpEnabled = enabled }
        override fun detectOpenMicBargeIn(block: ByteArray): Boolean {
            if (!bargeInListening) return false
            bargeInListening = false
            return true
        }
        override fun detectFollowUpSpeech(block: ByteArray) = detectFollowUp()
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
        var armCalls = 0
        var onInitialize: () -> Unit = {}
        override fun initialize() { initialized = true;onInitialize() }
        override fun arm() { armed = true;armCalls++ }
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
        val hardExpiredInteractions = mutableListOf<String>()

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
        override fun onInteractionExpired(interactionId: String) {
            hardExpiredInteractions += interactionId
        }
        override fun resetDialogue() {
            dialogueResets++
            dialogueSnapshot = DialogueSnapshot()
        }
    }
}
