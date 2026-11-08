package com.autovoice.app.channel

import android.content.ContextWrapper
import com.autovoice.app.AudioPlayer
import com.autovoice.voicecore.*
import com.autovoice.tts.PlaybackIdentity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileNotFoundException

class SpeechChannelTest {
    private val channel = SelectedSpeechChannel.create()
    private fun config() = DemoConfig.fromJson(File("src/${BuildFlavor.name}/assets/demo-offline.json").readText())

    @Test
    fun `selected channel accepts its assets and rejects unknown and mixed providers`() {
        val cfg = config()
        channel.validateConfig(cfg)
        val invalid = listOf(
            "frontend" to cfg.copy(frontend = "unknown"),
            "vad.provider" to cfg.copy(vad = cfg.vad.copy(provider = "unknown")),
            "ecnr" to cfg.copy(ecnr = "unknown"),
            "local.asr" to cfg.copy(local = cfg.local.copy(asr = "unknown")),
            "local.nlu" to cfg.copy(local = cfg.local.copy(nlu = "unknown")),
        )
        for ((field, value) in invalid) {
            val error = assertThrows(IllegalArgumentException::class.java) { channel.validateConfig(value) }
            assertTrue(error.message!!.contains(field), error.message)
        }
        val other = if (channel.id == "open") "iflytek.offline" else "firered.ctc.int8"
        assertThrows(IllegalArgumentException::class.java) {
            channel.validateConfig(cfg.copy(local = cfg.local.copy(asr = other)))
        }
        if (channel.id == "open") {
            assertThrows(IllegalArgumentException::class.java) { channel.validateConfig(cfg.copy(ecnr = "rnnoise")) }
        }
    }

    @Test fun `open rejects explicitly supplied Silero probability and leaves native aux bridge unavailable`() {
        val cfg = config()
        if (channel.id == "open") {
            val explicit = cfg.copy(vad = cfg.vad.copy(thresholdProvided = true))
            assertThrows(IllegalArgumentException::class.java) { channel.validateConfig(explicit) }
            assertThrows(IllegalArgumentException::class.java) { channel.validateConfig(cfg.copy(vad = cfg.vad.copy(threshold = 0.8))) }
            assertFalse(channel.capabilities.auxiliaryBridgeAvailable)
        } else {
            channel.validateConfig(cfg.copy(vad = cfg.vad.copy(threshold = 0.8,thresholdProvided = true)))
            assertTrue(channel.capabilities.auxiliaryBridgeAvailable)
        }
    }

    @Test
    fun `only missing assets use channel fallback and invalid content never does`() {
        var notices = 0
        val load = {
            loadChannelConfig(channel, "demo-offline.json", "demo-dev.json", "unit-token",
                readAsset = { throw FileNotFoundException("fixture") }, onMissingAsset = { notices++ })
        }
        if (channel.id == "iflytek") {
            val fallback = load()
            assertEquals("offline", fallback.mode)
            assertFalse(fallback.cloud.enabled)
            assertEquals("iflytek.fake-cmd", fallback.local.asr)
            assertEquals(2000L, fallback.cloud.waitMs)
            assertEquals("unit-token", fallback.cloud.authToken)
        } else {
            assertThrows(IllegalStateException::class.java) { load() }
        }
        assertEquals(1, notices)
        assertThrows(IllegalStateException::class.java) {
            loadChannelConfig(channel, "demo-dev.json", "demo-dev.json", "", readAsset = { "invalid json" })
        }
        assertThrows(IllegalStateException::class.java) {
            loadChannelConfig(channel, "demo-dev.json", "demo-dev.json", "", readAsset = {
                """{"mode":"dev","local":{"asr":"unknown","nlu":"rule.nlu"},
                    "cloud":{"enabled":true,"gatewayUrl":"ws://fixture/ws","waitMs":3000}}"""
            })
        }
    }

    @Test
    fun `open local recognition is explicitly unavailable and emits no synthetic transcript`() = runTest {
        if (channel.id != "open") return@runTest
        val cfg = config()
        val frontend = channel.frontendFactory.create(ContextWrapper(null), cfg.vad, cfg.ecnr)
        assertNull(channel.capabilities.frontendUnavailableReason) // Actual native construction, not an optimistic static capability.
        assertNotNull(frontend.unavailabilityReason) // JVM has no Android native library.
        assertFalse(frontend.vadAvailable)
        assertThrows(IllegalStateException::class.java) { frontend.process(ByteArray(1024)) }
        frontend.close()
        val engines = channel.createLocalEngines(ContextWrapper(null), cfg, backgroundScope)
        var transcripts = 0
        var established = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            engines.asr.recognize("test", ByteArray(1024), object : AsrSink {
                override fun onTranscript(result: AsrResult) { transcripts++ }
                override fun onTurnEstablished() { established++ }
            })
        }
        assertNotNull(channel.capabilities.localAsrUnavailableReason)
        assertTrue(failure.message!!.contains("FireRed"))
        assertEquals(0, transcripts)
        assertEquals(0, established)
        engines.close()
    }

    @Test
    fun `selected playback driver preserves playback identity and stop`() {
        var observed: PlaybackIdentity? = null
        var stopped = false
        val player = object : AudioPlayer {
            override fun play(reply: AudioReply) { error("identity missing") }
            override fun play(reply: AudioReply, identity: PlaybackIdentity) { observed = identity }
            override fun stop() { stopped = true }
        }
        val driver = channel.createPlaybackDriver(player)
        val identity = PlaybackIdentity("turn", "playback")
        driver.play(AudioReply("audio/pcm", byteArrayOf(1)), identity)
        assertEquals(identity, observed)
        driver.stop()
        assertTrue(stopped)
    }
}

private object BuildFlavor { val name get() = com.autovoice.app.BuildConfig.FLAVOR }
