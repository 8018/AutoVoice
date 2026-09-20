package com.autovoice.audiofrontend

import android.content.Context
import android.util.Log
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

/** A single frame emitted by the microphone audio front end. */
data class AudioFrontendFrame(
    val processedPcm: ByteArray,
    val vadEvent: VadEvent?,
)

/** Diagnostic snapshot for the current capture turn. */
data class AudioFrontendDiagnostics(
    val maxVadProbability: Float? = null,
    val speechStartEvents: Int = 0,
    val speechEndEvents: Int = 0,
    val inputBlocks: Int = 0,
    val openSegmentStart: Int? = null,
)

/**
 * Microphone front-end boundary: raw PCM enters once, signal processing and VAD run together, and
 * callers receive only the processed stream plus VAD observations. Capture and business routing do
 * not know which denoiser or VAD implementation is installed.
 */
interface AudioFrontendEngine : AutoCloseable {
    val vadAvailable: Boolean
    val diagnostics: AudioFrontendDiagnostics

    fun startTurn()
    fun process(block: ByteArray): AudioFrontendFrame
    fun finishSegments(): List<ByteArray>
}

/** Production composition for the local RNNoise + Silero front end. */
object AudioFrontendFactory {
    private const val SILERO_VAD_ASSET = "silero_vad.onnx"

    fun create(
        context: Context,
        vadConfig: VadConfig,
        ecnr: String,
    ): AudioFrontendEngine {
        val denoiseEnabled = when (ecnr) {
            DemoConfig.ECNR_RNNOISE -> true
            DemoConfig.ECNR_NONE -> false
            else -> error("unsupported ECNR provider '$ecnr'; supported: rnnoise, none")
        }
        val segmenter = try {
            VadSegmenter(
                vad = SileroVad(context, SILERO_VAD_ASSET),
                gate = vadConfig.createSegmentationGate(),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Silero VAD model unavailable; signal processing remains active", error)
            null
        }
        return LocalAudioFrontendEngine(segmenter, RnnoiseProcessor(), denoiseEnabled)
    }

    fun createVad(context: Context): VadEngine? = try {
        SileroVad(context, SILERO_VAD_ASSET)
    } catch (error: Throwable) {
        Log.w(TAG, "Silero VAD model unavailable", error)
        null
    }

    private const val TAG = "AudioFrontendFactory"
}

internal class LocalAudioFrontendEngine(
    private val segmenter: VadSegmenter?,
    private val denoiser: FrontendSignalProcessor,
    private val denoiseEnabled: Boolean,
) : AudioFrontendEngine {
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
        segmenter?.resetForTurn()
    }

    override fun process(block: ByteArray): AudioFrontendFrame {
        require(block.size == INPUT_BLOCK_BYTES) {
            "audio frontend expects $INPUT_BLOCK_BYTES-byte PCM16 blocks, got ${block.size}"
        }
        val vadEvent = segmenter?.feed(block)
        val input = pcm16BytesToShorts(block).copyOfRange(0, RnnoiseProcessor.FRAME_SIZE)
        val output = if (denoiseEnabled) denoiser.process(input) else input
        return AudioFrontendFrame(pcm16ShortsToBytes(output), vadEvent)
    }

    override fun finishSegments(): List<ByteArray> = segmenter?.finish() ?: emptyList()

    override fun close() {
        runCatching { segmenter?.close() }
        runCatching { denoiser.close() }
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
