package com.autovoice.app

import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.voicecore.dialog.DialogueSnapshot
import com.autovoice.voicecore.dialog.DialogueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Android 采集驱动边界；实现只管理 AudioRecord/VAD/RNNoise 资源，不决定音频去向。 */
interface RecordingCapture : AutoCloseable {
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
    fun onFollowUpExpired(interactionId: String, taskRevision: Long?)
    fun onListeningExpired(expected: DialogueSnapshot, taskRevision: Long?) {
        expected.interactionId?.let { onFollowUpExpired(it, taskRevision) }
    }
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

internal data class RecordingTiming(
    val wakeTurnTimeoutMs: Long = 10_000L,
    val followUpListenMs: Long = 10_000L,
    val maxInteractionMs: Long = 60_000L,
)

/** Opaque task listening policy. The recorder executes it without knowing the business domain. */
internal typealias TaskListeningDirective = com.autovoice.voicecore.dialog.TaskListeningDirective

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
    private val elapsedRealtimeMs: () -> Long,
    private val timing: RecordingTiming = RecordingTiming(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val jobs = mutableListOf<Job>()
    private val denoisedBlocks = mutableListOf<ByteArray>()
    private val cloudStreamLock = Any()
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
    private var wakeTurnTimeoutJob: Job? = null
    private var taskListeningDirective: TaskListeningDirective? = null
    private val listening = com.autovoice.voicecore.dialog.DialogueListeningController(
        scope, elapsedRealtimeMs, { pipeline.dialogueSnapshot },
        { expected, revision ->
            capture.setFollowUpListening(false)
            pipeline.onListeningExpired(expected, revision)
        }, timing.followUpListenMs, timing.maxInteractionMs,
    )

    val isRecording: Boolean get() = recording
    val isChatLocked: Boolean get() = chatLocked

    init {
        onState(state)
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
        updateState {
            it.copy(
                permissionRequired = false,
                vadUnavailable = !capture.vadAvailable,
                recording = true,
                wakeListening = false,
            )
        }
        wakeTurnTimeoutJob?.cancel()
        if (fromWake) {
            wakeTurnTimeoutJob = scope.launch {
                delay(timing.wakeTurnTimeoutMs)
                if (wakeTurn && recording && !chatLocked) {
                    onLog("唤醒后等待命令超时，自动收口")
                    stopTurn()
                }
            }
        }
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

    fun onBackground() {
        foreground = false
        stopFollowUpListening(resetDialogue = true)
        wakeSetupJob?.cancel()
        wakeSetupJob = null
        if (chatLocked) {
            recording = false
            capture.stop()
            pipeline.finishRealtimeChat()
            synchronized(denoisedBlocks) { denoisedBlocks.clear() }
            updateState { it.copy(recording = false) }
        } else if (recording) {
            cancelTurn(rearm = false)
        } else {
            pauseWakeObservation()
        }
        capture.stopMonitoring()
        updateState { it.copy(wakeListening = false, openMicBargeInAvailable = false) }
    }

    fun stopTurn() {
        if (!recording || chatLocked) return
        recording = false
        resetCloudStreamGate()
        wakeTurn = false
        wakeTurnTimeoutJob?.cancel()
        wakeTurnTimeoutJob = null
        capture.stop()
        updateState { it.copy(recording = false) }
        val tail = capture.finishProcessedAudio()
        val denoised = synchronized(denoisedBlocks) {
            if (tail.isNotEmpty()) denoisedBlocks += tail
            concatBlocks(denoisedBlocks).also { denoisedBlocks.clear() }
        }
        val cloudSegments = capture.finishSegments()
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
        rearmWakeWhenIdle()
    }

    fun cancelTurn(rearm: Boolean = true) {
        if (!recording || chatLocked) return
        recording = false
        resetCloudStreamGate()
        wakeTurn = false
        wakeTurnTimeoutJob?.cancel()
        wakeTurnTimeoutJob = null
        capture.stop()
        synchronized(denoisedBlocks) { denoisedBlocks.clear() }
        pipeline.cancelStreamingCloudAudio()
        pipeline.onListeningStop()
        updateState { it.copy(recording = false) }
        if (rearm) rearmWakeWhenIdle()
    }

    fun onPlaybackStage(stage: PlaybackStage) {
        capture.setOpenMicBargeInListening(stage == PlaybackStage.STARTED && !chatLocked)
    }

    fun onDialogueState(snapshot: DialogueSnapshot, taskDirective: TaskListeningDirective?) {
        dialogueSnapshot = snapshot
        taskListeningDirective = taskDirective
        if (foreground && !chatLocked) listening.update(snapshot, taskDirective)
        val waiting = snapshot.state == DialogueState.FOLLOW_UP_LISTENING ||
            snapshot.state == DialogueState.AWAKE
        if (!waiting) {
            capture.setFollowUpListening(false)
        }
        if (waiting) {
            armFollowUpListening(snapshot)
        } else if (snapshot.state == DialogueState.DORMANT) {
            if (recording && !chatLocked) cancelTurn(rearm = false)
            stopFollowUpListening(resetDialogue = false)
            rearmWakeWhenIdle()
        }
    }

    fun setChatMode(enabled: Boolean): Boolean {
        if (chatLocked == enabled) return false
        chatLocked = enabled
        updateState { it.copy(chatMode = enabled) }
        if (enabled) {
            stopFollowUpListening(resetDialogue = false)
            capture.setOpenMicBargeInListening(false)
            startChatCapture()
        } else {
            pipeline.finishRealtimeChat()
            if (recording) {
                recording = false
                capture.stop()
                synchronized(denoisedBlocks) { denoisedBlocks.clear() }
                updateState { it.copy(recording = false) }
            }
            pipeline.resetDialogue()
            rearmWakeWhenIdle()
        }
        return true
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
            if (recording) pipeline.appendRealtimeChatAudio(block)
            return
        }
        if (recording) return
        if (state.wakeListening) {
            runCatching { wakeWord.accept(block) }.onFailure(::onWakeFailure)
        }
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
                if (wakeTurn && recording) stopTurn()
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
                    wakeInitialized = true
                }
                if (!canMonitorWake()) {
                    wakeWord.pause()
                    capture.stopMonitoring()
                    return@launch
                }
                wakeWord.arm()
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
        onLog("延时聆听检测到人声，建立临时 capture")
        startTurn(fromWake = true, includeBargeInPreRoll = true)
    }

    private fun armFollowUpListening(snapshot: DialogueSnapshot) {
        val interactionId = snapshot.interactionId ?: return
        val taskRevision = taskListeningDirective?.revision
        if (startingTurn || chatLocked || !foreground || recording) return
        pauseWakeObservation()
        if (!capture.startMonitoring()) {
            pipeline.onFollowUpExpired(interactionId, taskRevision)
            return
        }
        capture.setFollowUpListening(true)
    }

    private fun stopFollowUpListening(resetDialogue: Boolean) {
        listening.close()
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

    private fun startChatCapture() {
        if (!chatLocked || !foreground || recording) return
        pauseWakeObservation()
        synchronized(denoisedBlocks) { denoisedBlocks.clear() }
        if (!capture.startMonitoring()) {
            updateState { it.copy(permissionRequired = true) }
            return
        }
        recording = true
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
        wakeTurnTimeoutJob?.cancel()
        listening.close()
        jobs.forEach(Job::cancel)
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
