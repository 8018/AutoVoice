package com.autovoice.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autovoice.app.AudioPlayer
import com.autovoice.app.channel.PlaybackDriverPorts
import com.autovoice.app.channel.SelectedSpeechChannel
import com.autovoice.audiofrontend.FrontendBatch
import com.autovoice.audiofrontend.RenderReferenceSink
import com.autovoice.audiofrontend.VadPurpose
import com.autovoice.audiofrontend.inSampleRange
import com.autovoice.audiofrontend.vad.VadEvent
import com.autovoice.tts.PlaybackIdentity
import com.autovoice.tts.TtsPlaybackDriver
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.AsrSink
import com.autovoice.adapterfirered.SenseVoiceAsrEngine
import com.autovoice.adapterfirered.FireRedAdmission
import com.autovoice.adapterfirered.FireRedAdmissionProvider
import java.io.ByteArrayOutputStream
import com.autovoice.voicecore.health.ComponentReporter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run in a quiet room with speaker output and RECORD_AUDIO already granted to the target app.
 * Reports aggregate capture statistics. Optional local SenseVoice diagnostic reports character count only; no network or retained microphone audio.
 */
@RunWith(AndroidJUnit4::class)
class OpenAcousticEchoProbeTest {
    @SuppressLint("MissingPermission")
    @Test
    fun speakerOnlyCaptureReportsWebRtcEchoAndVadStatistics(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Grant RECORD_AUDIO to the target app before running the acoustic probe"
        }
        val channel = SelectedSpeechChannel.create()
        val config = context.assets.open("demo-offline.json").bufferedReader().use {
            channel.validateConfig(DemoConfig.fromJson(it.readText()))
        }
        val source = instrumentation.context.assets.open("playback/command-24k-mono.wav").use { it.readBytes() }
        val audio = repeatFixture(source, repetitions = 13)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = AtomicLong(-1L)
        val terminal = AtomicReference<String?>()
        val playback = PlaybackIdentity("platform:acoustic-echo-probe")
        val mode = InstrumentationRegistry.getArguments().getString("referenceGain") ?: "production"
        require(mode == "production" || mode == "unity") { "referenceGain must be production or unity" }
        val originalGain = GainRange()
        val recognizeEcho = InstrumentationRegistry.getArguments().getString("recognizeEcho") == "true"
        val onPlayback: (String, String, Map<String, Any?>) -> Unit = { stage, _, payload ->
            if (payload["playbackId"] == playback.playbackId) {
                if (stage == "start") started.compareAndSet(-1L, SystemClock.elapsedRealtime())
                else terminal.compareAndSet(null, stage)
            }
        }

        try {
            channel.frontendFactory.create(context, config.vad, config.ecnr).use { frontend ->
                check(frontend.vadAvailable && frontend.unavailabilityReason == null)
                val render = frontend as RenderReferenceSink
                val driver: TtsPlaybackDriver = if (mode == "unity") {
                    val platform = AndroidPlaybackPcmPlatform(context)
                    val unityPlatform = object : PlaybackPcmPlatform by platform {
                        override fun openTrack(onRouteChanged: () -> Unit): PlaybackPcmTrack {
                            val real = platform.openTrack(onRouteChanged)
                            return object : PlaybackPcmTrack by real {
                                override fun renderTiming(frame: Long): PlaybackRenderTiming? =
                                    real.renderTiming(frame)?.let { timing ->
                                        originalGain.record(timing.gain)
                                        timing.copy(gain = 1f)
                                    }
                            }
                        }
                    }
                    OpenPcmPlaybackDriver(unityPlatform, scope, render, onPlayback)
                } else {
                    channel.createPlaybackDriver(
                        AudioPlayer { error("Unused legacy player") },
                        PlaybackDriverPorts(
                            context, scope, render, onPlayback, ComponentReporter.none(), ComponentReporter.none(),
                        ),
                    )
                }
                try {
                    val minimum = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    )
                    check(minimum > 0) { "AudioRecord minimum buffer unavailable: $minimum" }
                    val record = AudioRecord.Builder()
                        .setAudioSource(channel.capturePolicy.audioSource)
                        .setAudioFormat(
                            AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                        )
                        .setBufferSizeInBytes(maxOf(minimum, BLOCK_SAMPLES * 4))
                        .build()
                    try {
                        channel.capturePolicy.effectsFactory.create().use { effects ->
                            effects.attach(record.audioSessionId)
                            record.startRecording()
                            try {
                                assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)
                                val requestedAt = SystemClock.elapsedRealtime()
                                driver.play(AudioReply("audio/wav", audio), playback)
                                val deadline = requestedAt + 25_000
                                val read = ShortArray(BLOCK_SAMPLES)
                                val raw = ByteBuffer.allocate(BLOCK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
                                var filled = 0
                                var position = 0L
                                var playbackStartSample: Long? = null
                                var measurementStartSample: Long? = null
                                val allPlayback = Metrics()
                                val afterWarmup = Metrics()
                                // Optional local-only diagnostic; bounded in memory, never written or uploaded.
                                val echoPcm = if (recognizeEcho) ByteArrayOutputStream() else null
                                while (terminal.get() == null && SystemClock.elapsedRealtime() < deadline) {
                                    val count = record.read(read, filled, read.size - filled, AudioRecord.READ_NON_BLOCKING)
                                    check(count >= 0) { "AudioRecord read failed: $count" }
                                    if (count == 0) {
                                        delay(3)
                                        continue
                                    }
                                    filled += count
                                    if (filled < read.size) continue
                                    filled = 0
                                    raw.clear()
                                    read.forEach { raw.putShort(it) }
                                    val batch = frontend.process(raw.array())
                                    val playStarted = started.get()
                                    if (playStarted >= 0 && terminal.get() == null) {
                                        echoPcm?.write(batch.processedPcm)
                                        val from = playbackStartSample ?: position.also { playbackStartSample = it }
                                        val until = position + read.size
                                        allPlayback.add(read, batch.inSampleRange(from, until), frontend.echoCancellationActive)
                                        if (SystemClock.elapsedRealtime() - playStarted >= 2_000) {
                                            val warm = measurementStartSample ?: position.also { measurementStartSample = it }
                                            afterWarmup.add(read, batch.inSampleRange(warm, until), frontend.echoCancellationActive)
                                        }
                                    }
                                    position += read.size
                                }
                                record.stop()
                                assertEquals("Playback did not complete normally", "completed", terminal.get())
                                assertTrue("No playback-period PCM or VAD observations", allPlayback.hasObservations)
                                assertTrue("No post-warmup PCM or VAD observations", afterWarmup.hasObservations)
                                assertTrue("WebRTC never reported usable render reference", afterWarmup.aecActiveBlocks > 0)
                                val elapsedMs = SystemClock.elapsedRealtime() - requestedAt
                                val transcriptChars = if (echoPcm != null) withContext(Dispatchers.IO) {
                                    val modelRoot = requireNotNull(context.getExternalFilesDir("speech-models"))
                                    SenseVoiceAsrEngine.create(modelRoot, FireRedAdmissionProvider {
                                        FireRedAdmission(System.nanoTime() / 1_000_000 + 30_000,
                                            finalized = { true }, originalPermitValid = { true })
                                    }).use { asr ->
                                        check(asr.awaitReady(30_000)) { "Local diagnostic ASR unavailable" }
                                        asr.recognize("acoustic-diagnostic", echoPcm.toByteArray(), AsrSink.NOOP)?.text?.length ?: 0
                                    }
                                } else -1
                                val summary = "$TAG speaker_only referenceGain=$mode originalGain=$originalGain " +
                                    "elapsedMs=$elapsedMs localTranscriptChars=$transcriptChars playback=[$allPlayback] " +
                                    "post_warmup=[$afterWarmup]"
                                Log.i(TAG, summary)
                                val output = android.os.Bundle().apply { putString("stream", "\n$summary\n") }
                                instrumentation.sendStatus(2, output)
                            } finally {
                                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                            }
                        }
                    } finally {
                        record.release()
                    }
                } finally {
                    driver.close()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private class GainRange {
        private var minimum = Float.POSITIVE_INFINITY
        private var maximum = Float.NEGATIVE_INFINITY

        @Synchronized
        fun record(gain: Float) {
            if (gain.isFinite()) {
                minimum = minOf(minimum, gain)
                maximum = maxOf(maximum, gain)
            }
        }

        @Synchronized
        override fun toString(): String = if (minimum.isFinite()) "$minimum..$maximum" else "unobserved"
    }

    private class Metrics {
        private var rawEnergy = 0.0
        private var processedEnergy = 0.0
        private var rawSamples = 0L
        private var processedSamples = 0L
        private var segmentationStarts = 0
        private var bargeInStarts = 0
        private var bargeInFrames = 0
        private var bargeInSpeechFrames = 0
        var aecActiveBlocks = 0
            private set

        val hasObservations get() = rawSamples > 0 && processedSamples > 0 && bargeInFrames > 0

        fun add(raw: ShortArray, processed: FrontendBatch, aecActive: Boolean) {
            rawSamples += raw.size
            raw.forEach { rawEnergy += it.toDouble() * it }
            processed.processedBlocks.forEach { block ->
                val pcm = ByteBuffer.wrap(block.pcm).order(ByteOrder.LITTLE_ENDIAN)
                repeat(block.validSamples) {
                    val sample = pcm.short.toDouble()
                    processedEnergy += sample * sample
                }
                processedSamples += block.validSamples
            }
            for (observation in processed.vadObservations) {
                when (observation.purpose) {
                    VadPurpose.SEGMENTATION -> segmentationStarts += observation.events.count { it == VadEvent.SpeechStart }
                    VadPurpose.BARGE_IN -> {
                        bargeInFrames++
                        if (observation.speechDetected == true) bargeInSpeechFrames++
                        bargeInStarts += observation.events.count { it == VadEvent.SpeechStart }
                    }
                    VadPurpose.FOLLOW_UP -> Unit
                }
            }
            if (aecActive) aecActiveBlocks++
        }

        override fun toString(): String {
            val rawMean = rawEnergy / rawSamples
            val processedMean = processedEnergy / processedSamples
            val ratioDb = if (rawMean > 0 && processedMean > 0) {
                String.format(java.util.Locale.ROOT, "%.2f", 10 * log10(rawMean / processedMean))
            } else "unavailable"
            return "rawSamples=$rawSamples processedSamples=$processedSamples rawToProcessedDb=$ratioDb " +
                "segmentationStarts=$segmentationStarts bargeInStarts=$bargeInStarts " +
                "bargeInSpeechFrames=$bargeInSpeechFrames bargeInFrames=$bargeInFrames aecActiveBlocks=$aecActiveBlocks"
        }
    }

    /** Keeps the real fixture signal and rewrites only the output WAV container lengths. */
    private fun repeatFixture(wav: ByteArray, repetitions: Int): ByteArray {
        val input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(wav.copyOfRange(0, 4).decodeToString() == "RIFF")
        require(wav.copyOfRange(8, 12).decodeToString() == "WAVE")
        require(input.getShort(20).toInt() == 1 && input.getShort(22).toInt() == 1)
        require(input.getInt(24) == 24_000 && input.getShort(34).toInt() == 16)
        var chunk = 12
        while (chunk + 8 <= wav.size) {
            val length = input.getInt(chunk + 4)
            val start = chunk + 8
            require(length >= 0 && length <= wav.size - start)
            if (wav.copyOfRange(chunk, chunk + 4).decodeToString() == "data") {
                require(length > 0 && length % 2 == 0)
                val bytes = length * repetitions
                val output = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
                output.put("RIFF".encodeToByteArray()).putInt(36 + bytes)
                output.put(wav, 8, 28)
                output.put("data".encodeToByteArray()).putInt(bytes)
                repeat(repetitions) { output.put(wav, start, length) }
                return output.array()
            }
            chunk = start + length + (length % 2)
        }
        error("Fixture WAV has no PCM data chunk")
    }

    companion object {
        private const val TAG = "OpenAcousticEchoProbe"
        private const val SAMPLE_RATE = 16_000
        private const val BLOCK_SAMPLES = 512
    }
}
