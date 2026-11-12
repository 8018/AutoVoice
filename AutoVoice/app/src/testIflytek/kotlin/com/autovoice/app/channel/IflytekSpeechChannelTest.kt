package com.autovoice.app.channel

import android.content.Context
import android.content.ContextWrapper
import com.autovoice.voicecore.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IflytekSpeechChannelTest {
    @Test
    fun `real command stage initializes once and releases without creating fake final`() = runTest {
        var initialized = 0
        var released = 0
        var recognized = 0
        val channel = IflytekSpeechChannel(initializationDispatcher = StandardTestDispatcher(testScheduler)) {
            object : IflytekCommand {
                override fun initialize(context: Context) { initialized++ }
                override fun recognize(segment: ByteArray): String? { recognized++; return null }
                override fun close() { released++ }
            }
        }
        val cfg = DemoConfig.fromJson("""{"mode":"offline","local":{"asr":"iflytek.offline","nlu":"rule.nlu"},
            "cloud":{"enabled":false,"gatewayUrl":"","waitMs":3000}}""")
        val engines = channel.createLocalEngines(ContextWrapper(null), cfg, backgroundScope)
        runCurrent()
        assertEquals(1, initialized)
        assertNull(engines.asr.recognize("turn", ByteArray(1024), AsrSink.NOOP))
        val result = engines.nlu.understand("turn", ByteArray(1024), null)
        assertEquals(1, recognized)
        assertNull(result.recognizedText)
        assertEquals("unknown", result.intent.intent)
        engines.close()
        runCurrent()
        assertEquals(1, released)
    }

    @Test
    fun `explicit demo fake never initializes a real command stage`() = runTest {
        val channel = IflytekSpeechChannel(initializationDispatcher = StandardTestDispatcher(testScheduler)) { error("real stage must not be constructed") }
        val cfg = DemoConfig.fromJson("""{"mode":"offline","local":{"asr":"iflytek.fake-cmd","nlu":"rule.nlu"},
            "cloud":{"enabled":false,"gatewayUrl":"","waitMs":3000}}""")
        val engines = channel.createLocalEngines(ContextWrapper(null), cfg, backgroundScope)
        runCurrent()
        val result = engines.nlu.understand("turn", byteArrayOf(1), null)
        assertEquals("打开空调", result.recognizedText)
        engines.close()
        runCurrent()
    }
}
