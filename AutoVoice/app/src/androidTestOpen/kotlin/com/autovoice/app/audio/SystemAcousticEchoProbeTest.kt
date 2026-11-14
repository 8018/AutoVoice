package com.autovoice.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autovoice.adapterfirered.FireRedAdmission
import com.autovoice.adapterfirered.FireRedAdmissionProvider
import com.autovoice.adapterfirered.SenseVoiceAsrEngine
import com.autovoice.tts.PlaybackIdentity
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.AudioReply
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Speaker-only device diagnostic. Run in a quiet room with the target app's microphone permission granted. */
@RunWith(AndroidJUnit4::class)
class SystemAcousticEchoProbeTest {
    @SuppressLint("MissingPermission")
    @Test
    fun speakerOnlyCaptureReportsPlatformAecAndLocalAsr(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(AudioManager::class.java)
        val source = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val initialMode = manager.mode
        val available = AcousticEchoCanceler.isAvailable()
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "AudioRecord buffer unavailable: $minimum" }
        val fixture = instrumentation.context.assets.open("playback/command-24k-mono.wav").use { it.readBytes() }
        val wave = repeatFixture(fixture)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val identity = PlaybackIdentity("platform:system-aec-probe")
        val started = AtomicBoolean(false)
        val terminal = AtomicReference<String?>()
        val playbackRoute = AtomicReference<Int?>()
        val realPlatform = AndroidPlaybackPcmPlatform(context)
        val platform = object : PlaybackPcmPlatform by realPlatform {
            override fun openTrack(onRouteChanged: () -> Unit): PlaybackPcmTrack {
                val real = realPlatform.openTrack(onRouteChanged)
                return object : PlaybackPcmTrack by real {
                    override fun renderTiming(frame: Long): PlaybackRenderTiming? =
                        real.renderTiming(frame)?.also { playbackRoute.set(it.route) }
                }
            }
        }
        val driver = OpenPcmPlaybackDriver(platform, scope, render = null, events = { stage, _, payload ->
            if (payload["playbackId"] == identity.playbackId) {
                if (stage == "start") started.set(true) else terminal.compareAndSet(null, stage)
            }
        })
        fun report(message: String) {
            Log.i(TAG, message)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\n$TAG $message\n") })
        }

        try {
            val record = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 2048))
                .build()
            try {
                val aec = if (available) AcousticEchoCanceler.create(record.audioSessionId) else null
                try {
                    val initiallyEnabled = aec?.enabled
                    val hasControl = aec?.hasControl()
                    val enableStatus = if (hasControl == true) aec?.setEnabled(true) else null
                    report("setup source=${record.audioSource} audioMode=$initialMode available=$available " +
                        "created=${aec != null} hasControl=$hasControl initialEnabled=$initiallyEnabled " +
                        "enableStatus=$enableStatus enabled=${aec?.enabled}")
                    check(aec?.enabled == true && (enableStatus == null || enableStatus == AudioEffect.SUCCESS)) {
                        "Platform AEC could not be verified as enabled"
                    }
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val begin = SystemClock.elapsedRealtime()
                    val pcm = ByteArrayOutputStream()
                    val block = ByteArray(1024)
                    val inputTypes = linkedSetOf<Int>()
                    val outputTypes = linkedSetOf<Int>()
                    var enabledBlocks = 0
                    var recordedBlocks = 0
                    try {
                        driver.play(AudioReply("audio/wav", wave), identity)
                        while (terminal.get() == null && SystemClock.elapsedRealtime() - begin < 20_000) {
                            val read = record.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
                            check(read >= 0 && read % 2 == 0) { "Unexpected PCM read length: $read" }
                            if (read == 0) {
                                delay(3)
                            } else if (started.get() && terminal.get() == null) {
                                check(pcm.size() + read <= RATE * 2 * 16) { "Capture exceeded the probe window" }
                                pcm.write(block, 0, read)
                                record.routedDevice?.type?.let(inputTypes::add)
                                playbackRoute.get()?.let { id ->
                                    manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                                        .firstOrNull { it.id == id }?.type?.let(outputTypes::add)
                                }
                                recordedBlocks++
                                if (aec?.enabled == true) enabledBlocks++
                            }
                        }
                    } finally {
                        record.stop()
                    }
                    assertEquals("completed", terminal.get())
                    check(pcm.size() > RATE * 2 && enabledBlocks == recordedBlocks) {
                        "Missing playback capture or system AEC became disabled"
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - begin
                    val chars = withContext(Dispatchers.IO) {
                        val modelRoot = requireNotNull(context.getExternalFilesDir("speech-models"))
                        SenseVoiceAsrEngine.create(modelRoot, FireRedAdmissionProvider {
                            FireRedAdmission(System.nanoTime() / 1_000_000 + 30_000,
                                finalized = { true }, originalPermitValid = { true })
                        }).use { asr ->
                            check(asr.awaitReady(30_000)) { "Local diagnostic ASR unavailable" }
                            asr.recognize("system-acoustic-probe", pcm.toByteArray(), AsrSink.NOOP)?.text?.length ?: 0
                        }
                    }
                    report("speaker_only audioModeBefore=$initialMode audioModeAfter=${manager.mode} " +
                        "inputRouteTypes=$inputTypes outputRouteTypes=$outputTypes samples=${pcm.size() / 2} " +
                        "enabledBlocks=$enabledBlocks recordedBlocks=$recordedBlocks elapsedMs=$elapsedMs " +
                        "localTranscriptChars=$chars")
                } finally {
                    aec?.release()
                }
            } finally {
                record.release()
            }
        } finally {
            try {
                driver.close()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun repeatFixture(wav: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(wav.copyOfRange(0, 4).decodeToString() == "RIFF" && wav.copyOfRange(8, 12).decodeToString() == "WAVE")
        require(input.getShort(20).toInt() == 1 && input.getShort(22).toInt() == 1)
        require(input.getInt(24) == 24_000 && input.getShort(34).toInt() == 16)
        var chunk = 12
        while (chunk + 8 <= wav.size) {
            val length = input.getInt(chunk + 4)
            val start = chunk + 8
            require(length >= 0 && length <= wav.size - start)
            if (wav.copyOfRange(chunk, chunk + 4).decodeToString() == "data") {
                require(length > 0 && length % 2 == 0)
                val size = length * 13
                return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".encodeToByteArray()).putInt(36 + size)
                    put(wav, 8, 28)
                    put("data".encodeToByteArray()).putInt(size)
                    repeat(13) { put(wav, start, length) }
                }.array()
            }
            chunk = start + length + (length % 2)
        }
        error("Missing fixture PCM")
    }

    private companion object {
        const val TAG = "SystemAcousticEchoProbe"
        const val RATE = 16_000
    }
}
