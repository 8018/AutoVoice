package com.autovoice.adapterwebrtc

import com.autovoice.audiofrontend.*
import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.health.*
import java.io.ByteArrayOutputStream

/** One persistent APM owner. Gate and segment resets never recreate signal processing. */
class WebRtcAudioFrontend internal constructor(private val backend: WebRtcBackend, config: VadConfig,
    private val preRollSamples: Int = 1600, private val maxSegmentSamples: Int = 160000,
    private val maxBufferedSamples: Int = 480000, private val renderQueueCapacity: Int = 20,
    private val clockNanos: () -> Long = System::nanoTime,
    private var frontendReporter: ComponentReporter = ComponentReporter.none(),
    private var vadReporter: ComponentReporter = ComponentReporter.none(),
    private var auxVadReporter: ComponentReporter = ComponentReporter.none(),
    private var aecReporter: ComponentReporter = ComponentReporter.none()) : AudioFrontendEngine, RenderReferenceSink {
    private val renderQueue = ArrayDeque<RenderReferenceFrame>()
    private var playbackId: Long? = null
    private var invalidatedThrough = -1L
    private var nextRenderSample: Long? = null
    private var renderPending = ShortArray(0)
    private var renderHeadOffset = 0
    private var renderPendingStartNanos: Long? = null
    private var lastReferenceNanos: Long? = null
    private var playbackStartedNanos = 0L
    private var referenceBroken = false
    var aecHealth = Health.UNKNOWN
        private set
    override val echoCancellationActive: Boolean
        @Synchronized get() = !closed && processingFailure == null && !referenceBroken &&
            aecHealth == Health.HEALTHY && playbackId != null &&
            lastReferenceNanos?.let { clockNanos() - it in 0..200_000_000L } == true
    var renderFailure: ErrorCode? = null
        private set
    private var frontendToken: ComponentToken? = null
    private var vadToken: ComponentToken? = null
    private var auxToken: ComponentToken? = null
    private var aecToken: ComponentToken? = null
    private var reportedAec: Pair<Health,ErrorCode?>? = null
    @Volatile private var processingFailure: ComponentError? = null
    override val unavailabilityReason: String? get() = processingFailure?.message
    private val gates = VadPurpose.entries.associateWith { SampleVadGate(config.minSpeechMs * 16, config.minSilenceMs * 16) }
        private var pending = ShortArray(0)
    private var position = 0L
    private var closed = false
    private var tracking = false
    private var historySeeded = false
    private var observedInputEnd: Long? = null
    private var retainedStart = 0L
    private class RetainedPcm : ByteArrayOutputStream() {
        fun slice(from: Int, until: Int): ByteArray {
            check(from >= 0 && until >= from && until <= count)
            return buf.copyOfRange(from,until)
        }
    }
    private val retained = RetainedPcm()
    private val segments = mutableListOf<ByteArray>()
    private var segmentStart: Long? = null
    private var startEvents = 0
    private var endEvents = 0
    private var blocks = 0
    init {
        require(preRollSamples >= 0 && maxSegmentSamples > 0 && maxBufferedSamples >= maxSegmentSamples && maxBufferedSamples <= 960000 && renderQueueCapacity > 0)
        require(config.minSpeechMs * 16 <= maxSegmentSamples) { "minimum speech exceeds maximum segment" }
        WebRtcFrontend.validateVadConfig(config)
        bindPrimary("initialize")
        bindAuxiliary("initialize")
    }
    private fun bindPrimary(stage: String) {
        frontendToken = frontendReporter.start(stage)
        frontendReporter.report(requireNotNull(frontendToken),ComponentUpdate.ready())
        processingFailure?.let { frontendReporter.report(requireNotNull(frontendToken),ComponentUpdate.health(Health.UNAVAILABLE,it)) }
        vadToken = vadReporter.start(stage);vadReporter.report(requireNotNull(vadToken),ComponentUpdate.ready())
    }
    private fun bindAuxiliary(stage: String) {
        auxToken = auxVadReporter.start(stage);auxVadReporter.report(requireNotNull(auxToken),ComponentUpdate.ready())
        aecToken = aecReporter.start(stage);aecReporter.report(requireNotNull(aecToken),ComponentUpdate.ready())
        reportedAec = null;reportAec()
    }
    @Synchronized override fun attachReporting(frontendReporter: ComponentReporter, vadReporter: ComponentReporter) {
        check(!closed);this.frontendReporter = frontendReporter;this.vadReporter = vadReporter;bindPrimary("reuse_existing")
    }
    @Synchronized override fun attachAuxiliaryReporting(auxVadReporter: ComponentReporter, aecReporter: ComponentReporter) {
        check(!closed);this.auxVadReporter = auxVadReporter;this.aecReporter = aecReporter;bindAuxiliary("reuse_existing")
    }
    private fun reportAec() {
        val state = aecHealth to renderFailure
        if (reportedAec == state) return
        aecToken?.let { aecReporter.report(it,ComponentUpdate.health(aecHealth,renderFailure?.let(::ComponentError))) }
        reportedAec = state
    }
    private inline fun <T> captureOperation(action: () -> T): T {
        check(!closed);check(processingFailure == null) { "WebRTC capture unavailable" }
        return try { action() } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            processingFailure = initializationError(error,if (error.message?.contains("overflow") == true) ErrorCode.RESOURCE_EXHAUSTED else ErrorCode.COMPONENT_RUNTIME_FAILED)
            frontendToken?.let { frontendReporter.report(it,ComponentUpdate.health(Health.UNAVAILABLE,processingFailure)) }
            throw error
        }
    }
    override val continuousProcessing = true
    override val ownsAuxiliaryVad = true
    override val vadAvailable get() = !closed
    override val diagnostics get() = AudioFrontendDiagnostics(speechStartEvents = startEvents, speechEndEvents = endEvents, inputBlocks = blocks,observedInputEndSample = observedInputEnd)
    @Synchronized override fun startTurn() {
        check(!closed);check(processingFailure == null) { "WebRTC capture unavailable" }
        historySeeded = false;observedInputEnd = null
        tracking = true;retainedStart = position + pending.size;retained.reset();segments.clear();segmentStart = null
        startEvents = 0;endEvents = 0;blocks = 0
        resetVad(VadPurpose.SEGMENTATION)
    }
    @Synchronized override fun startTurn(history: AudioInputHistory) {
        val processedPreRoll = history.batch.processedBlocks
        check(!closed);check(processingFailure == null) { "WebRTC capture unavailable" }
        if (processedPreRoll.isEmpty()) { startTurn();return }
        var next = processedPreRoll.first().startSample
        var total = 0L
        for (block in processedPreRoll) {
            require(block.format == PcmFormat() && block.validSamples > 0 && block.startSample == next) { "pre-roll must be continuous processed capture PCM" }
            next += block.validSamples;total += block.validSamples
        }
        require(next == position && total <= maxBufferedSamples) { "pre-roll must end at current DSP position and fit input capacity" }
        val start = processedPreRoll.first().startSample
        require(history.auxiliaryConfirmedAtSample in (start+1)..position) { "auxiliary confirmation outside processed history" }
        val observations = history.batch.vadObservations
        require(observations.all { it.purpose == VadPurpose.SEGMENTATION && it.validSamples > 0 && it.startSample >= 0 && it.startSample < position && it.startSample+it.validSamples <= position && it.startSample+it.validSamples > start }) { "history must carry original main observations" }
        require(observations.zipWithNext().all { (a,b) -> a.startSample < b.startSample }) { "main history observations must be ordered" }
        // History belongs to the already-running SAME main detector/gate. Never reset/replay DSP.
        historySeeded = true;observedInputEnd = history.observedEndSample
        tracking = observedInputEnd == null;retainedStart = start;retained.reset();segments.clear();segmentStart = start
        startEvents = observations.sumOf { it.events.count { event -> event == VadEvent.SpeechStart } }
        endEvents = if (observedInputEnd == null) 0 else 1;blocks = 0
        history.batch.inSampleRange(start,observedInputEnd ?: position).processedBlocks.forEach { retained.write(it.pcm) }
        observedInputEnd?.let(::closeSegment)
    }
    @Synchronized override fun resetVad(purpose: VadPurpose) { check(!closed);check(processingFailure == null) { "WebRTC capture unavailable" };gates.getValue(purpose).reset();backend.resetVad(purpose) }
    @Synchronized override fun process(block: ByteArray): FrontendBatch {
        check(!closed)
        require(block.size == 1024) { "capture requires 512 real PCM16 samples" }
        return captureOperation {
        val input = decode(block)
        pending += input
        val pcm = mutableListOf<PositionedPcmBlock>()
        val observations = mutableListOf<VadObservation>()
        while (pending.size >= 160) {
            val frame = pending.copyOfRange(0,160)
            val batch = processFrame(frame,160)
            pending = pending.copyOfRange(160,pending.size)
            pcm += batch.processedBlocks;observations += batch.vadObservations
        }
        blocks++
        FrontendBatch(pcm,observations)
        }
    }
    private fun processFrame(input: ShortArray, valid: Int): FrontendBatch {
        val inputOffset = (retainedStart - position).coerceIn(0L,valid.toLong()).toInt()
        if (tracking) check(retained.size() / 2 + valid - inputOffset <= maxBufferedSamples) { "capture retention overflow" }
        val delayMs = consumeRender()
        val result = backend.processCapture(input,delayMs,valid)
        reportAec()
        check(result.pcm.size == 160) { "invalid native capture result" }
        val block = PositionedPcmBlock(position,encode(result.pcm.copyOf(valid)),valid)
        if (tracking) retained.write(block.pcm,inputOffset*2,(valid-inputOffset)*2)
        val observations = if (valid < 160) emptyList() else VadPurpose.entries.map { purpose ->
            val speech = requireNotNull(result.speech[purpose]) { "missing native VAD result" }
            // A speech edge observed before render protection is available may be speaker echo.
            // Do not retain that edge or its accumulated speech toward a later protected trigger.
            val gate = gates.getValue(purpose)
            val events = if (purpose == VadPurpose.BARGE_IN && !echoCancellationActive) {
                gate.reset()
                emptyList()
            } else gate.feed(speech,position,valid)
            if (tracking && purpose == VadPurpose.SEGMENTATION) {
                for (event in events) when (event) {
                    VadEvent.SpeechStart -> { startEvents++;if (segmentStart == null) segmentStart = maxOf(retainedStart,gates.getValue(purpose).onsetSample - preRollSamples) }
                    VadEvent.SpeechEnd -> { endEvents++;closeSegment(position + valid)
                        if (historySeeded) { observedInputEnd = position+valid;tracking = false } }
                }
            }
            VadObservation(position,valid,speech,events = events,purpose = purpose)
        }
        position += valid
        if (tracking) while (segmentStart?.let { position - it >= maxSegmentSamples } == true) {
            val end = requireNotNull(segmentStart) + maxSegmentSamples
            closeSegment(end);segmentStart = end
        }
        return FrontendBatch(listOf(block),observations)
    }
    @Synchronized override fun acceptRender(frame: RenderReferenceFrame): Boolean {
        if (closed || frame.playbackId <= invalidatedThrough || playbackId?.let { frame.playbackId < it } == true) return false
        require(frame.format == PcmFormat() && frame.validSamples in 1..1600 && frame.pcm.size == frame.validSamples * 2 && frame.startSample >= 0) {
            "render requires bounded 16kHz mono PCM16 with real sample positions"
        }
        if (playbackId != frame.playbackId) {
            clearReference();playbackId = frame.playbackId;playbackStartedNanos = clockNanos()
        }
        if (referenceBroken) return false
        if (renderQueue.size >= renderQueueCapacity) {
            renderQueue.clear();renderPending = ShortArray(0);renderHeadOffset = 0;renderPendingStartNanos = null
            referenceBroken = true;renderFailure = ErrorCode.RENDER_QUEUE_OVERFLOW;aecHealth = Health.UNAVAILABLE
            reportAec();return false
        }
        renderQueue.addLast(frame.copy(pcm = frame.pcm.copyOf()))
        return true
    }
    @Synchronized override fun invalidateRender(playbackId: Long) {
        invalidatedThrough = maxOf(invalidatedThrough,playbackId)
        if (this.playbackId?.let { it <= invalidatedThrough } == true) { clearReference();this.playbackId = null;reportAec() }
    }
    private fun clearReference() {
        renderQueue.clear();renderPending = ShortArray(0);renderHeadOffset = 0;renderPendingStartNanos = null;nextRenderSample = null;lastReferenceNanos = null
        referenceBroken = false;renderFailure = null;aecHealth = Health.UNKNOWN
    }
    private fun consumeRender(): Int {
        val now = clockNanos()
        while (renderQueue.isNotEmpty()) {
            val frame = renderQueue.first()
            val sliceStartNanos = frame.timestampNanos + renderHeadOffset * 62_500L
            // A queued 100ms AudioTrack write is not 100ms of already rendered sound.
            // Advance APM only when each 10ms slice's first sample is due. Multiple
            // capture calls from the same 32ms input block cannot replay a slice.
            if (sliceStartNanos > now) break
            val sliceStartSample = frame.startSample + renderHeadOffset
            if (nextRenderSample != null && nextRenderSample != sliceStartSample) {
                renderQueue.clear();renderPending = ShortArray(0);renderHeadOffset = 0;renderPendingStartNanos = null
                referenceBroken = true;renderFailure = ErrorCode.RENDER_REFERENCE_STALE;aecHealth = Health.UNAVAILABLE
                break
            }
            if (renderPending.isEmpty()) renderPendingStartNanos = sliceStartNanos
            val count = minOf(160 - renderPending.size,frame.validSamples - renderHeadOffset)
            renderPending += decode(frame.pcm.copyOfRange(renderHeadOffset * 2,(renderHeadOffset + count) * 2))
            renderHeadOffset += count
            nextRenderSample = sliceStartSample + count
            if (renderHeadOffset == frame.validSamples) { renderQueue.removeFirst();renderHeadOffset = 0 }
            if (renderPending.size == 160) {
                backend.processRender(renderPending)
                // Freshness refers to the presented first sample, never a future packet end.
                lastReferenceNanos = requireNotNull(renderPendingStartNanos)
                renderPending = ShortArray(0);renderPendingStartNanos = null
            }
        }
        if (playbackId == null) aecHealth = Health.UNKNOWN
        else if (referenceBroken) aecHealth = Health.UNAVAILABLE
        else if (now - (lastReferenceNanos ?: playbackStartedNanos) > 200_000_000L) {
            renderFailure = ErrorCode.RENDER_REFERENCE_STALE;aecHealth = Health.UNAVAILABLE
        } else if (lastReferenceNanos != null) { renderFailure = null;aecHealth = Health.HEALTHY }
        // This is the age of the last presented reference, not a hardware latency
        // measurement. Capture currently carries no AudioRecord timestamp; its
        // buffering latency still needs device calibration before claiming AEC quality.
        return ((now - (lastReferenceNanos ?: now)) / 1_000_000L).toInt().coerceIn(0,500)
    }
    @Synchronized override fun finishBatch(): FrontendBatch {
        check(!closed)
        if (observedInputEnd != null || pending.isEmpty()) return FrontendBatch.EMPTY
        return captureOperation {
            val valid = pending.size
            val output = processFrame(pending.copyOf(160),valid)
            pending = ShortArray(0)
            output
        }
    }
    override fun finishProcessedAudio() = finishBatch().processedPcm
    @Synchronized override fun finishSegments(): List<ByteArray> {
        closeSegment(position)
        return segments.toList().also {
            segments.clear();tracking = false;retained.reset()
        }
    }
    @Synchronized override fun abortInput() {
        if (closed) return
        tracking = false;historySeeded = false;observedInputEnd = null
        retained.reset();segments.clear();segmentStart = null
        // No native reset/flush, so abort remains safe even after a native processing failure.
        gates.getValue(VadPurpose.SEGMENTATION).reset()
    }
    private fun closeSegment(end: Long) {
        val start = segmentStart ?: return
        segmentStart = null
        if (end <= start) return
        // Once the speech gate confirmed a segment, keep its short final split too.
        // Every slice is bounded; no whole-turn copy is needed for each VAD boundary.
        var from = start
        while (from < end) {
            val until = minOf(end,from + maxSegmentSamples)
            segments += retained.slice(((from - retainedStart) * 2).toInt(),((until - retainedStart) * 2).toInt())
            from = until
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true;pending = ShortArray(0);retained.reset();segments.clear();clearReference()
        try { backend.close() } finally {
            frontendToken?.let { frontendReporter.close(it) };vadToken?.let { vadReporter.close(it) }
            auxToken?.let { auxVadReporter.close(it) };aecToken?.let { aecReporter.close(it) }
        }
    }
    private fun decode(bytes: ByteArray) = ShortArray(bytes.size / 2) { i -> ((bytes[2*i].toInt() and 255) or (bytes[2*i+1].toInt() shl 8)).toShort() }
    private fun encode(samples: ShortArray) = ByteArray(samples.size * 2).also { b -> samples.forEachIndexed { i,s -> b[2*i]=s.toByte();b[2*i+1]=(s.toInt() shr 8).toByte() } }
}
