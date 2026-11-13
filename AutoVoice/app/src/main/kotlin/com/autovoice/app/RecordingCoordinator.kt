package com.autovoice.app

import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.voicecore.health.rethrowCancellationOrFatal
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.autovoice.voicebusiness.dialog.DialogueSnapshot
import com.autovoice.voicebusiness.dialog.DialogueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Recorder-owned input epoch; null means idle DSP. Positions remain original capture samples. */
data class CaptureFrontendBatch(val inputEpoch: Long?, val batch: com.autovoice.audiofrontend.FrontendBatch,
    val followUpEpoch: Long? = null, val bargeInEpoch: Long? = null, val speechConfirmedForInput: Boolean = false)
data class CaptureFrontendFailure(val inputEpoch: Long?, val error: com.autovoice.voicecore.health.ComponentError)
data class CaptureCompletion(val inputEpoch: Long?, val batches: List<CaptureFrontendBatch>,
    val tail: ByteArray, val segments: List<ByteArray>, val failure: CaptureFrontendFailure? = null)
data class CaptureStreamingInput(val inputEpoch: Long, val captureStreamEpoch: Long, val startSample: Long)

/** Android 采集驱动边界；实现只管理 AudioRecord/VAD/RNNoise 资源，不决定音频去向。 */
interface RecordingCapture : AutoCloseable {
    val frontendBatchSignal: kotlinx.coroutines.flow.StateFlow<Long>? get() = null
    val inputEpoch: Long? get() = null
    val frontendFailure: CaptureFrontendFailure? get() = null
    fun drainFrontendBatches(): List<CaptureFrontendBatch> = emptyList()
    /** Null preserves the legacy stop/getter path. Open implementations freeze ownership atomically. */
    fun stopAndSnapshot(): CaptureCompletion? = null
    /** Continuous processed streaming ownership, without ordinary whole-turn retention. */
    fun beginStreamingInput(): CaptureStreamingInput? = null
    fun endStreamingInput() = Unit
    fun abortInput() = stop()
    fun detectAuxiliarySpeech(event: CaptureFrontendBatch, purpose: com.autovoice.audiofrontend.VadPurpose): Boolean = false
    val pcmBlocks: SharedFlow<ByteArray>
    val rawPcmBlocks: SharedFlow<ByteArray>
    val vadEvents: SharedFlow<VadEvent>
    val vadAvailable: Boolean
    val openMicBargeInAvailable: Boolean

    fun startMonitoring(): Boolean
    fun stopMonitoring()
    fun setOpenMicBargeInListening(enabled: Boolean)
    fun setFollowUpListening(enabled: Boolean)
    fun detectOpenMicBargeIn(block: ByteArray): Boolean
    fun detectFollowUpSpeech(block: ByteArray): Boolean
    fun start(includeBargeInPreRoll: Boolean = false): Boolean
    fun stop()
    fun finishProcessedAudio(): ByteArray
    fun finishSegments(): List<ByteArray>
}

/** 唤醒 SDK 只观察共享原始 PCM，不拥有麦克风。 */
internal interface WakeWordPort : AutoCloseable {
    val keyword: String
    fun initialize()
    fun arm()
    fun accept(pcm: ByteArray)
    fun pause()
    fun disarm()
}

/** 录音协调器到语音引擎的单向端口；引擎替换时实现可动态转发到新实例。 */
internal interface RecordingPipeline {
    val dialogueSnapshot: DialogueSnapshot
    fun onWake()
    fun onListeningStart(interruptPlayback: Boolean)
    fun onListeningStop()
    fun onVadStart()
    fun onVadEnd()
    fun appendStreamingCloudAudio(block: ByteArray)
    fun finishStreamingCloudAudio()
    fun cancelStreamingCloudAudio()
    fun onCloudSegment(segment: ByteArray)
    fun onTurnSegment(segment: ByteArray)
    fun appendRealtimeChatAudio(block: ByteArray)
    fun startRealtimeChat()
    fun finishRealtimeChat()
    fun onInteractionExpired(interactionId: String)
    fun resetDialogue()
}

internal data class RecordingLifecycleSnapshot(
    val recording: Boolean = false,
    val wakeListening: Boolean = false,
    val permissionRequired: Boolean = false,
    val vadUnavailable: Boolean = false,
    val openMicBargeInAvailable: Boolean = false,
    val wakeError: String? = null,
    val chatMode: Boolean = false,
)

/**
 * Owns the microphone-use lifecycle, not dialogue state, ASR/NLU, arbitration or playback.
 * Exactly one shared capture is routed to wake monitoring, a normal turn, follow-up VAD or realtime.
 */
internal class RecordingCoordinator(
    private val capture: RecordingCapture,
    private val wakeWord: WakeWordPort,
    private val pipeline: RecordingPipeline,
    private val scope: CoroutineScope,
    private val isPlaybackSpeaking: () -> Boolean,
    private val onState: (RecordingLifecycleSnapshot) -> Unit,
    private val onLocalSegment: (ByteArray) -> Unit = {},
    private val onLog: (String) -> Unit = {},
    private val onWakeError: (Throwable) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val jobs = mutableListOf<Job>()
    private val denoisedBlocks = mutableListOf<ByteArray>()
    private val cloudStreamLock = Any()
    // Coordinator lock precedes Recorder locks. Recorder signals only outside its own locks.
    private val captureBatchLock = Any()
    private var activeInputEpoch: Long? = null
    private var realtimeSessionActive = false
    private var stopAfterOpenDrain = false
    private val cloudPreRoll = ArrayDeque<ByteArray>()

    @Volatile private var state = RecordingLifecycleSnapshot(vadUnavailable = !capture.vadAvailable)
    @Volatile private var foreground = false
    @Volatile private var recording = false
    @Volatile private var chatLocked = false
    @Volatile private var wakeTurn = false
    @Volatile private var startingTurn = false
    @Volatile private var cloudVadActive = false
    @Volatile private var dialogueSnapshot = DialogueSnapshot()
    private var wakeInitialized = false
    private var wakeSetupJob: Job? = null

    val isRecording: Boolean get() = recording
    val isChatLocked: Boolean get() = chatLocked

    init {
        onState(state)
        capture.frontendBatchSignal?.let { signal -> jobs += scope.launch {
            signal.collect { synchronized(captureBatchLock) { consumeOpenBatches() } }
        } }
        jobs += scope.launch {
            capture.pcmBlocks.collect(::onDenoisedBlock)
        }
        jobs += scope.launch(ioDispatcher) {
            capture.rawPcmBlocks.collect(::onRawBlock)
        }
        jobs += scope.launch {
            capture.vadEvents.collect(::onVadEvent)
        }
    }

    fun startManualTurn() {
        if (recording) return
        startingTurn = true
        try {
            pipeline.onWake()
            startTurn(fromWake = false)
        } finally {
            startingTurn = false
        }
    }

    private fun startTurn(
        fromWake: Boolean,
        includeBargeInPreRoll: Boolean = false,
        interruptPlayback: Boolean = true,
    ) {
        if (recording) return
        // Capture is not ASR admission: keep the DM listening deadline running.
        capture.setFollowUpListening(false)
        pauseWakeObservation()
        if (isPlaybackSpeaking() && interruptPlayback) {
            onLog("检测到新轮录音，停止旧轮播放（barge-in）")
        }
        recording = true
        resetCloudStreamGate()
        wakeTurn = fromWake
        synchronized(denoisedBlocks) { denoisedBlocks.clear() }
        pipeline.onListeningStart(interruptPlayback)
        if (!capture.start(includeBargeInPreRoll)) {
            recording = false
            wakeTurn = false
            pipeline.onListeningStop()
            pipeline.resetDialogue()
            updateState { it.copy(recording = false, permissionRequired = true) }
            rearmWakeWhenIdle()
            return
        }
        activeInputEpoch = capture.inputEpoch
        updateState {
            it.copy(
                permissionRequired = false,
                vadUnavailable = !capture.vadAvailable,
                recording = true,
                wakeListening = false,
            )
        }
        synchronized(captureBatchLock) { consumeOpenBatches() }
    }

    fun onForeground() {
        foreground = true
        if (chatLocked) startChatCapture() else startWakeMonitoring()
    }

    fun onAudioPermissionGranted() {
        updateState { it.copy(permissionRequired = false) }
        if (chatLocked) startChatCapture() else startWakeMonitoring()
    }

    fun onPermissionDenied() {
        updateState { it.copy(permissionRequired = true) }
    }

    /**
     * Pauses the shared microphone before replacing capture configuration. The caller rebuilds the
     * engine immediately afterwards and [onDialogueState] rearms the appropriate idle listener.
     */
    fun reconfigureCapture(configure: () -> Unit) {
        require(!recording && !chatLocked) { "capture cannot be reconfigured while recording" }
        wakeSetupJob?.cancel()
        wakeSetupJob = null
        pauseWakeObservation()
        capture.stopMonitoring()
        configure()
        updateState {
            it.copy(
                vadUnavailable = !capture.vadAvailable,
                wakeListening = false,
                openMicBargeInAvailable = false,
            )
        }
    }

    fun onBackground() = synchronized(captureBatchLock) {
        foreground = false
        stopFollowUpListening(resetDialogue = true)
        wakeSetupJob?.cancel()
        wakeSetupJob = null
        if (chatLocked) {
            endChatCapture()
        } else if (recording) {
            cancelTurn(rearm = false)
        } else {
            pauseWakeObservation()
        }
        capture.stopMonitoring()
        updateState { it.copy(wakeListening = false, openMicBargeInAvailable = false) }
    }

    fun stopTurn() = synchronized(captureBatchLock) {
        consumeOpenBatches(allowStop = false)
        stopTurnAfterDrain()
    }

    private fun stopTurnAfterDrain() {
        if (!recording || chatLocked) return
        val completion = capture.stopAndSnapshot()
        if (completion != null) {
            if (completion.inputEpoch != activeInputEpoch || completion.failure != null) {
                cancelTurn(rearm = false)
                return
            }
            // Snapshot owns its accepted range even if ongoing idle capture fails now.
            consumeCapturedBatches(completion.batches,allowStop = false)
        }
        recording = false
        if (capture.frontendBatchSignal == null) resetCloudStreamGate()
        wakeTurn = false
        if (completion == null) capture.stop()
        updateState { it.copy(recording = false) }
        val tail = completion?.tail ?: capture.finishProcessedAudio()
        if (capture.frontendBatchSignal != null) {
            if (cloudVadActive && tail.isNotEmpty()) pipeline.appendStreamingCloudAudio(tail)
            resetCloudStreamGate()
        }
        val denoised = synchronized(denoisedBlocks) {
            if (tail.isNotEmpty()) denoisedBlocks += tail
            concatBlocks(denoisedBlocks).also { denoisedBlocks.clear() }
        }
        val cloudSegments = completion?.segments ?: capture.finishSegments()
        if (denoised.size >= MIN_SEGMENT_BYTES && cloudSegments.isNotEmpty()) {
            pipeline.finishStreamingCloudAudio()
        } else {
            pipeline.cancelStreamingCloudAudio()
        }
        cloudSegments.forEach(pipeline::onCloudSegment)
        if (denoised.size >= MIN_SEGMENT_BYTES) {
            onLocalSegment(denoised)
            pipeline.onTurnSegment(denoised)
        } else {
            onLog("录音过短（${denoised.size}B < ${MIN_SEGMENT_BYTES}B），丢弃不送识别")
            pipeline.onListeningStop()
        }
        // The input capture has released the microphone. If it was only noise, the same
        // LISTENING snapshot can now start an actual follow-up window.
        if (dialogueSnapshot.state == DialogueState.LISTENING && dialogueSnapshot.turnId == null) {
            onDialogueState(pipeline.dialogueSnapshot)
        }
        rearmBargeInAfterCapture()
        rearmWakeWhenIdle()
    }

    private fun rearmBargeInAfterCapture() {
        if (foreground && !recording && !chatLocked &&
            dialogueSnapshot.state == DialogueState.SPEAKING && isPlaybackSpeaking()
        ) capture.setOpenMicBargeInListening(true)
    }

    fun cancelTurn(rearm: Boolean = true) {
        if (!recording || chatLocked) return
        recording = false
        resetCloudStreamGate()
        wakeTurn = false
        capture.abortInput()
        synchronized(denoisedBlocks) { denoisedBlocks.clear() }
        pipeline.cancelStreamingCloudAudio()
        pipeline.onListeningStop()
        updateState { it.copy(recording = false) }
        if (rearm) {
            rearmBargeInAfterCapture()
            rearmWakeWhenIdle()
        }
    }

    fun onPlaybackStage(stage: PlaybackStage) {
        // Once sound starts, only echo-protected detection may establish a candidate capture.
        // Invalidate queued silent-preparation observations before arming the playback gate.
        if (stage == PlaybackStage.STARTED) capture.setFollowUpListening(false)
        capture.setOpenMicBargeInListening(stage == PlaybackStage.STARTED && !chatLocked)
        // Business receives the terminal event before the physical playback flag is cleared.
        // Recheck here so its earlier LISTENING projection cannot leave the microphone unarmed.
        if (stage != PlaybackStage.STARTED && canCaptureWithoutPlayback(dialogueSnapshot)) {
            armFollowUpListening(dialogueSnapshot)
        }
    }

    fun onDialogueState(snapshot: DialogueSnapshot) {
        dialogueSnapshot = snapshot
        if (canCaptureWithoutPlayback(snapshot)) {
            armFollowUpListening(snapshot)
        } else {
            capture.setFollowUpListening(false)
        }
        if (snapshot.state == DialogueState.DORMANT) {
            if (recording && !chatLocked) cancelTurn(rearm = false)
            rearmWakeWhenIdle()
        }
    }

    fun setChatMode(enabled: Boolean): Boolean = synchronized(captureBatchLock) {
        if (chatLocked == enabled) return false
        if (enabled && recording) cancelTurn(rearm = false)
        chatLocked = enabled
        updateState { it.copy(chatMode = enabled) }
        if (enabled) {
            stopFollowUpListening(resetDialogue = false)
            capture.setOpenMicBargeInListening(false)
            startChatCapture()
        } else {
            endChatCapture()
            pipeline.resetDialogue()
            rearmWakeWhenIdle()
        }
        return true
    }

    private fun consumeOpenBatches(allowStop: Boolean = true) {
        if (capture.frontendBatchSignal == null) return
        val failure = capture.frontendFailure
        if (failure != null) {
            capture.drainFrontendBatches()
            if (recording && failure.inputEpoch == activeInputEpoch) {
                onLog(failure.error.code.message)
                if (chatLocked) {
                    endChatCapture(clearMode = true)
                    capture.stopMonitoring()
                } else cancelTurn(rearm = false)
            }
            return
        }
        consumeCapturedBatches(capture.drainFrontendBatches(),allowStop)
    }

    private fun consumeCapturedBatches(batches: List<CaptureFrontendBatch>, allowStop: Boolean) {
        for (event in batches) {
            if (chatLocked) {
                if (recording && event.inputEpoch == activeInputEpoch && activeInputEpoch != null)
                    event.batch.processedBlocks.forEach { pipeline.appendRealtimeChatAudio(it.pcm) }
                continue
            }
            if (event.inputEpoch == null) {
                if (!recording && capture.detectAuxiliarySpeech(event,com.autovoice.audiofrontend.VadPurpose.BARGE_IN)) onOpenMicBargeInDetected()
                else if (!recording && capture.detectAuxiliarySpeech(event,com.autovoice.audiofrontend.VadPurpose.FOLLOW_UP)) onFollowUpSpeechDetected()
                continue
            }
            if (!recording || event.inputEpoch != activeInputEpoch) continue
            if (event.speechConfirmedForInput && !cloudVadActive) onVadEvent(VadEvent.SpeechStart)
            for (block in event.batch.processedBlocks) {
                val observations = event.batch.vadObservations.filter {
                    it.purpose == com.autovoice.audiofrontend.VadPurpose.SEGMENTATION &&
                        it.startSample < block.startSample + block.validSamples &&
                        it.startSample + it.validSamples > block.startSample
                }
                observations.flatMap { it.events }.filter { it == VadEvent.SpeechStart }.forEach(::onVadEvent)
                onDenoisedBlock(block.pcm)
                observations.flatMap { it.events }.filter { it == VadEvent.SpeechEnd }.forEach(::onVadEvent)
            }
        }
        val stop = stopAfterOpenDrain
        stopAfterOpenDrain = false
        if (stop && allowStop && recording) stopTurn()
    }

    private fun onDenoisedBlock(block: ByteArray) {
        if (!recording || chatLocked) return
        synchronized(denoisedBlocks) { denoisedBlocks.add(block) }
        val streamNow = synchronized(cloudStreamLock) {
            if (cloudVadActive) true else {
                cloudPreRoll.addLast(block.copyOf())
                while (cloudPreRoll.size > CLOUD_PRE_ROLL_BLOCKS) cloudPreRoll.removeFirst()
                false
            }
        }
        if (streamNow) pipeline.appendStreamingCloudAudio(block)
    }

    private fun onRawBlock(block: ByteArray) {
        if (chatLocked) {
            if (recording && capture.frontendBatchSignal == null) pipeline.appendRealtimeChatAudio(block)
            return
        }
        if (recording) return
        if (state.wakeListening) {
            runCatching { wakeWord.accept(block) }.onFailure(::onWakeFailure)
        }
        if (capture.frontendBatchSignal != null) return // Open auxiliary observations come from the same processed batch, never raw PCM.
        if (capture.detectOpenMicBargeIn(block)) {
            scope.launch { onOpenMicBargeInDetected() }
        } else if (capture.detectFollowUpSpeech(block)) {
            scope.launch { onFollowUpSpeechDetected() }
        }
    }

    private fun onVadEvent(event: VadEvent) {
        if (chatLocked) return
        when (event) {
            VadEvent.SpeechStart -> {
                pipeline.onVadStart()
                val preRoll = synchronized(cloudStreamLock) {
                    cloudVadActive = true
                    cloudPreRoll.toList().also { cloudPreRoll.clear() }
                }
                preRoll.forEach(pipeline::appendStreamingCloudAudio)
            }
            VadEvent.SpeechEnd -> {
                synchronized(cloudStreamLock) {
                    cloudVadActive = false
                    cloudPreRoll.clear()
                }
                pipeline.onVadEnd()
                if (wakeTurn && recording) {
                    if (capture.frontendBatchSignal != null) stopAfterOpenDrain = true else stopTurn()
                }
            }
        }
    }

    private fun startWakeMonitoring() {
        if (chatLocked || !foreground || recording || wakeSetupJob?.isActive == true) return
        wakeSetupJob = scope.launch(ioDispatcher) {
            if (!capture.startMonitoring()) return@launch
            if (!canMonitorWake()) {
                capture.stopMonitoring()
                return@launch
            }
            try {
                if (!wakeInitialized) {
                    wakeWord.initialize()
                    currentCoroutineContext().ensureActive()
                    wakeInitialized = true
                }
                if (!canMonitorWake()) {
                    wakeWord.pause()
                    capture.stopMonitoring()
                    return@launch
                }
                wakeWord.arm()
                currentCoroutineContext().ensureActive()
                if (!canMonitorWake()) {
                    wakeWord.pause()
                    capture.stopMonitoring()
                    return@launch
                }
                updateState {
                    it.copy(
                        wakeListening = true,
                        wakeError = null,
                        openMicBargeInAvailable = capture.openMicBargeInAvailable,
                    )
                }
                onLog("离线唤醒已启用：${wakeWord.keyword}")
            } catch (error: Throwable) {
                error.rethrowCancellationOrFatal()
                onWakeFailure(error)
            }
        }
    }

    private fun canMonitorWake(): Boolean = foreground && !recording && !chatLocked

    private fun pauseWakeObservation() {
        updateState { it.copy(wakeListening = false) }
        wakeWord.pause()
    }

    private fun onWakeDetected(keyword: String) {
        if (!foreground || recording || chatLocked) return
        onLog("离线唤醒命中：$keyword${if (isPlaybackSpeaking()) "（barge-in）" else ""}")
        startingTurn = true
        try {
            pipeline.onWake()
            startTurn(fromWake = true)
        } finally {
            startingTurn = false
        }
    }

    fun deliverWake(keyword: String) = onWakeDetected(keyword)

    fun deliverWakeError(error: Throwable) = onWakeFailure(error)

    private fun onOpenMicBargeInDetected() {
        if (!foreground || recording || chatLocked || !isPlaybackSpeaking()) return
        onLog("播报期 VAD 命中，仅建立候选 capture；等待 ASR/NLU 确认为新会话")
        startTurn(fromWake = true, includeBargeInPreRoll = true, interruptPlayback = false)
    }

    private fun onFollowUpSpeechDetected() {
        if (!foreground || recording || chatLocked) return
        // Audio callbacks are queued onto the coordinator scope. The dialogue may have
        // exited before this callback runs; a stale VAD signal must not reopen capture.
        if (!canCaptureWithoutPlayback(dialogueSnapshot)) return
        onLog("静默聆听检测到人声，建立临时 capture")
        startTurn(fromWake = true, includeBargeInPreRoll = true)
    }

    private fun canCaptureWithoutPlayback(snapshot: DialogueSnapshot): Boolean =
        !isPlaybackSpeaking() && (
            (snapshot.state == DialogueState.LISTENING && snapshot.turnId == null) ||
                snapshot.state == DialogueState.RESPONDING
            )

    private fun armFollowUpListening(snapshot: DialogueSnapshot) {
        val interactionId = snapshot.interactionId ?: return
        if (startingTurn || chatLocked || !foreground || recording) return
        pauseWakeObservation()
        if (!capture.startMonitoring()) {
            updateState { it.copy(permissionRequired = true) }
            pipeline.onInteractionExpired(interactionId)
            return
        }
        capture.setFollowUpListening(true)
    }

    private fun stopFollowUpListening(resetDialogue: Boolean) {
        capture.setFollowUpListening(false)
        if (resetDialogue) pipeline.resetDialogue()
    }

    private fun onWakeFailure(error: Throwable) {
        onWakeError(error)
        runCatching { wakeWord.disarm() }
        updateState {
            it.copy(
                wakeListening = false,
                openMicBargeInAvailable = capture.openMicBargeInAvailable,
                wakeError = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun rearmWakeWhenIdle() {
        if (!chatLocked && foreground && !recording && dialogueSnapshot.state == DialogueState.DORMANT) {
            startWakeMonitoring()
        }
    }

    private fun startChatCapture() = synchronized(captureBatchLock) {
        if (!chatLocked || !foreground || recording) return
        pauseWakeObservation()
        synchronized(denoisedBlocks) { denoisedBlocks.clear() }
        if (!capture.startMonitoring()) {
            updateState { it.copy(permissionRequired = true) }
            return
        }
        if (capture.frontendBatchSignal != null) {
            val owner = capture.beginStreamingInput()
            if (owner == null) {
                capture.stopMonitoring()
                updateState { it.copy(recording = false,permissionRequired = false) }
                return
            }
            activeInputEpoch = owner.inputEpoch
        }
        recording = true
        realtimeSessionActive = true
        pipeline.startRealtimeChat()
        updateState {
            it.copy(
                chatMode = true,
                recording = true,
                wakeListening = false,
                permissionRequired = false,
                vadUnavailable = !capture.vadAvailable,
            )
        }
    }

    private fun endChatCapture(clearMode: Boolean = false) {
        if (recording) {
            recording = false
            if (capture.frontendBatchSignal != null) capture.endStreamingInput() else capture.stop()
        }
        activeInputEpoch = null
        if (realtimeSessionActive) {
            realtimeSessionActive = false
            pipeline.finishRealtimeChat()
        }
        if (clearMode) chatLocked = false
        synchronized(denoisedBlocks) { denoisedBlocks.clear() }
        updateState { it.copy(recording = false,chatMode = chatLocked) }
    }

    private fun resetCloudStreamGate() = synchronized(cloudStreamLock) {
        cloudVadActive = false
        cloudPreRoll.clear()
    }

    private fun updateState(transform: (RecordingLifecycleSnapshot) -> RecordingLifecycleSnapshot) {
        val next = synchronized(this) {
            transform(state).also { state = it }
        }
        onState(next)
    }

    override fun close() {
        foreground = false
        wakeSetupJob?.cancel()
        jobs.forEach(Job::cancel)
        synchronized(captureBatchLock) { if (realtimeSessionActive) endChatCapture() }
        runCatching { wakeWord.close() }
        capture.close()
    }

    private companion object {
        const val MIN_SEGMENT_BYTES = 9_600
        const val CLOUD_PRE_ROLL_BLOCKS = 10

        fun concatBlocks(blocks: List<ByteArray>): ByteArray {
            val result = ByteArray(blocks.sumOf(ByteArray::size))
            var offset = 0
            blocks.forEach {
                it.copyInto(result, offset)
                offset += it.size
            }
            return result
        }
    }
}
