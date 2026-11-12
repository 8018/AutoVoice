package com.autovoice.audiofrontend

import android.content.Context
import android.util.Log
import com.autovoice.voicecore.health.*
import com.autovoice.audiofrontend.ecnr.RnnoiseProcessor
import com.autovoice.audiofrontend.ecnr.FrontendSignalProcessor
import com.autovoice.audiofrontend.vad.SileroVad
import com.autovoice.audiofrontend.vad.VadEngine
import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.audiofrontend.vad.VadSegmenter
import com.autovoice.audiofrontend.vad.createSegmentationGate
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.VadConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Production composition for the local RNNoise + Silero front end. */
object AudioFrontendFactory {
    private const val SILERO_VAD_ASSET = "silero_vad.onnx"

    fun create(
        context: Context,
        vadConfig: VadConfig,
        ecnr: String,
        frontendReporter: ComponentReporter = ComponentReporter.none(),
        vadReporter: ComponentReporter = ComponentReporter.none(),
    ): AudioFrontendEngine {
        val frontendToken = if (ecnr == DemoConfig.ECNR_NONE) frontendReporter.start("create") else null
        val vadToken = vadReporter.start("model_create")
        var vadFailure: ComponentError? = null
        val denoiseEnabled = when (ecnr) {
            DemoConfig.ECNR_RNNOISE -> true
            DemoConfig.ECNR_NONE -> false
            else -> error("unsupported ECNR provider '$ecnr'; supported: rnnoise, none")
        }
        val segmenter = try {
            VadSegmenter(
                vad = SileroVad(context, SILERO_VAD_ASSET),
                gate = vadConfig.createSegmentationGate(),
            ).also { vadReporter.report(vadToken, ComponentUpdate.ready()) }
        } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            vadFailure = initializationError(error, ErrorCode.MODEL_LOAD_FAILED)
            vadReporter.report(vadToken, ComponentUpdate.failed(requireNotNull(vadFailure)))
            Log.w(TAG, "Silero VAD model unavailable; signal processing remains active", error)
            null
        }
        if (!denoiseEnabled) frontendReporter.report(requireNotNull(frontendToken), ComponentUpdate.ready())
        return LocalAudioFrontendEngine(segmenter, RnnoiseProcessor(), denoiseEnabled, frontendReporter, frontendToken, vadReporter, vadToken, vadFailure)
    }

    fun createVad(context: Context): VadEngine? = try {
        SileroVad(context, SILERO_VAD_ASSET)
    } catch (error: Throwable) {
        error.rethrowCancellationOrFatal()
        Log.w(TAG, "Silero VAD model unavailable", error)
        null
    }

    private const val TAG = "AudioFrontendFactory"
}

internal class LocalAudioFrontendEngine(
    private val segmenter: VadSegmenter?,
    private val denoiser: FrontendSignalProcessor,
    private val denoiseEnabled: Boolean,
    private var frontendReporter: ComponentReporter = ComponentReporter.none(),
    private var frontendToken: ComponentToken? = null,
    private var vadReporter: ComponentReporter = ComponentReporter.none(),
    private var vadToken: ComponentToken? = null,
    private val vadFailure: ComponentError? = null,
) : AudioFrontendEngine {
    private var frontendFailure: ComponentError? = null
    override val unavailabilityReason: String? get() = frontendFailure?.message
    override fun attachReporting(frontendReporter: ComponentReporter, vadReporter: ComponentReporter) {
        this.frontendReporter = frontendReporter;this.vadReporter = vadReporter
        if (processorReady || !denoiseEnabled || frontendFailure != null) {
            frontendToken = frontendReporter.start("reuse_existing")
            frontendReporter.report(requireNotNull(frontendToken), if (!processorReady && frontendFailure != null)
                ComponentUpdate.failed(requireNotNull(frontendFailure)) else ComponentUpdate.ready())
            if (processorReady && frontendFailure != null) frontendReporter.report(requireNotNull(frontendToken), ComponentUpdate.health(Health.UNAVAILABLE, frontendFailure))
        } else frontendToken = null
        vadToken = vadReporter.start("reuse_existing")
        vadReporter.report(requireNotNull(vadToken), if (segmenter != null) ComponentUpdate.ready()
            else ComponentUpdate.failed(vadFailure ?: ComponentError(ErrorCode.MODEL_LOAD_FAILED)))
    }
    private var processorReady = false
    private var pendingSamples = ShortArray(0)
    private var inputPosition = 0L
    private var outputPosition = 0L
    private var closed = false
    override val vadAvailable: Boolean get() = segmenter != null

    override val diagnostics: AudioFrontendDiagnostics
        get() = segmenter?.let {
            AudioFrontendDiagnostics(
                maxVadProbability = it.maxProbability,
                speechStartEvents = it.speechStartEvents,
                speechEndEvents = it.speechEndEvents,
                inputBlocks = it.blockCount,
                openSegmentStart = it.openStart.takeIf { index -> index >= 0 },
            )
        } ?: AudioFrontendDiagnostics()

    override fun startTurn() {
        pendingSamples = ShortArray(0)
        inputPosition = 0;outputPosition = 0
        segmenter?.resetForTurn()
    }

    override fun process(block: ByteArray): AudioFrontendFrame {
        require(block.size == INPUT_BLOCK_BYTES) {
            "audio frontend expects $INPUT_BLOCK_BYTES-byte PCM16 blocks, got ${block.size}"
        }
        val vadEvent = segmenter?.feed(block)
        val observation = segmenter?.let { VadObservation(inputPosition,512,it.lastSpeechDetected,it.lastProbability,listOfNotNull(vadEvent)) }
        inputPosition += 512
        if (!denoiseEnabled) return batch(block.copyOf(),observation)

        pendingSamples = pendingSamples + pcm16BytesToShorts(block)
        val completeFrames = pendingSamples.size / RnnoiseProcessor.FRAME_SIZE
        val output = ShortArray(completeFrames * RnnoiseProcessor.FRAME_SIZE)
        var consumed = 0
        repeat(completeFrames) {
            val frame = pendingSamples.copyOfRange(consumed, consumed + RnnoiseProcessor.FRAME_SIZE)
            processDenoiser(frame).copyInto(output, consumed)
            consumed += RnnoiseProcessor.FRAME_SIZE
        }
        pendingSamples = pendingSamples.copyOfRange(consumed, pendingSamples.size)
        return batch(pcm16ShortsToBytes(output),observation)
    }

    private fun flushProcessedAudio(): ByteArray {
        if (!denoiseEnabled || pendingSamples.isEmpty()) {
            pendingSamples = ShortArray(0)
            return ByteArray(0)
        }
        val sampleCount = pendingSamples.size
        val padded = pendingSamples.copyOf(RnnoiseProcessor.FRAME_SIZE)
        pendingSamples = ShortArray(0)
        return pcm16ShortsToBytes(processDenoiser(padded).copyOf(sampleCount))
    }

    private fun batch(pcm: ByteArray, observation: VadObservation? = null): FrontendBatch {
        val blocks = if (pcm.isEmpty()) emptyList() else listOf(PositionedPcmBlock(outputPosition,pcm))
        outputPosition += pcm.size / 2
        return FrontendBatch(blocks,listOfNotNull(observation))
    }
    override fun finishBatch() = batch(flushProcessedAudio())
    override fun finishProcessedAudio() = finishBatch().processedPcm

    private fun processDenoiser(frame: ShortArray): ShortArray = try {
        if (frontendToken == null) frontendToken = frontendReporter.start("native_load")
        denoiser.process(frame).also {
            if (!processorReady) {
                // Existing reader retries processing; only an actual success earns a new attempt.
                // Repeated failed blocks keep the original failure instead of emitting retry loops.
                if (frontendFailure != null) frontendToken = frontendReporter.start("native_load")
                processorReady = true
                frontendToken?.let { token -> frontendReporter.report(token, ComponentUpdate.ready()) }
            }
            else if (frontendFailure != null) frontendToken?.let { frontendReporter.report(it, ComponentUpdate.health(Health.HEALTHY)) }
            frontendFailure = null
        }
    } catch (error: Throwable) {
        error.rethrowCancellationOrFatal()
        frontendFailure = initializationError(error)
        frontendToken?.let { token -> frontendReporter.report(token,
            if (processorReady) ComponentUpdate.health(Health.UNAVAILABLE, frontendFailure) else ComponentUpdate.failed(requireNotNull(frontendFailure))) }
        throw error
    }

    override fun finishSegments(): List<ByteArray> = segmenter?.finish() ?: emptyList()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { segmenter?.close() }
        runCatching { denoiser.close() }
        frontendToken?.let { frontendReporter.close(it) }
        vadToken?.let { vadReporter.close(it) }
    }

    private companion object {
        const val INPUT_BLOCK_BYTES = 1024
    }
}

internal fun pcm16BytesToShorts(bytes: ByteArray): ShortArray {
    val samples = ShortArray(bytes.size / 2)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
    return samples
}

internal fun pcm16ShortsToBytes(shorts: ShortArray): ByteArray {
    val out = ByteArray(shorts.size * 2)
    ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
    return out
}
