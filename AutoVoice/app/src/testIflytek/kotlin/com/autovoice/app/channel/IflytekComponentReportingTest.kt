package com.autovoice.app.channel

import android.content.Context
import android.content.ContextWrapper
import com.autovoice.app.health.*
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.health.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IflytekComponentReportingTest {
    private fun config() = DemoConfig.fromJson("""{"mode":"offline","local":{"asr":"iflytek.offline","nlu":"rule.nlu"},"cloud":{"enabled":false,"gatewayUrl":"","waitMs":3000}}""")
    private fun reporting(): ChannelComponentReporting {
        val registry = ComponentStatusRegistry()
        return ChannelComponentReporting(registry, "iflytek", registry.beginGeneration("iflytek")).also {
            it.registerDefaults()
            it.readConfig(::config)
            val runtime = it.reporter("iflytek.runtime")
            runtime.report(runtime.start("authorize"), ComponentUpdate.ready()) // Controlled boundary, not real SDK authorization.
        }
    }
    @Test fun `uninitialized and failed command adapters never receive recognition audio`() = runTest {
        val health = reporting()
        var calls = 0
        val channel = IflytekSpeechChannel(StandardTestDispatcher(testScheduler)) {
            object : IflytekCommand {
                override fun initialize(context: Context): Unit = throw UnsatisfiedLinkError("private native path and token")
                override fun recognize(segment: ByteArray): String { calls++;return "不应识别" }
                override fun close() = Unit
            }
        }
        channel.attachReporting(health)
        val engines = channel.createLocalEngines(ContextWrapper(null), config(), backgroundScope)
        assertNull(engines.nlu.understand("t", byteArrayOf(1), null).recognizedText)
        runCurrent()
        assertEquals(ErrorCode.NATIVE_LIBRARY_MISSING, health.registry.snapshot.value.components.getValue("local.asr").error!!.code)
        assertNull(engines.nlu.understand("t", byteArrayOf(1), null).recognizedText)
        assertEquals(0, calls)
        engines.close();runCurrent()
    }
    @Test fun `command initialization handover waits for previous release without blocking caller`() = runTest {
        val calls = mutableListOf<String>()
        var created = 0
        val channel = IflytekSpeechChannel(StandardTestDispatcher(testScheduler)) {
            val id = ++created
            object : IflytekCommand {
                override fun initialize(context: Context) { calls += "init$id" }
                override fun recognize(segment: ByteArray): String? = null
                override fun close() { calls += "close$id" }
            }
        }
        val first = channel.createLocalEngines(ContextWrapper(null), config(), backgroundScope)
        runCurrent()
        val second = channel.createLocalEngines(ContextWrapper(null), config(), backgroundScope)
        runCurrent()
        assertEquals(listOf("init1"), calls)
        first.close();runCurrent()
        assertEquals(listOf("init1", "close1", "init2"), calls)
        second.close();runCurrent()
        assertEquals(listOf("init1", "close1", "init2", "close2"), calls)
    }
    @Test fun `cancelled queued command cannot let successor bypass active predecessor`() = runTest {
        val calls = mutableListOf<String>()
        var created = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val channel = IflytekSpeechChannel(dispatcher) {
            val id = ++created
            object : IflytekCommand {
                override fun initialize(context: Context) { calls += "init$id" }
                override fun recognize(segment: ByteArray): String? = null
                override fun close() { calls += "close$id" }
            }
        }
        val first = channel.createLocalEngines(ContextWrapper(null), config(), backgroundScope)
        runCurrent()
        val middleScope = CoroutineScope(SupervisorJob() + dispatcher)
        val middle = channel.createLocalEngines(ContextWrapper(null), config(), middleScope)
        runCurrent()
        middle.close();middleScope.cancel();runCurrent()
        val last = channel.createLocalEngines(ContextWrapper(null), config(), backgroundScope)
        runCurrent()
        assertEquals(listOf("init1"), calls)
        first.close();runCurrent()
        assertEquals(listOf("init1", "close1", "close2", "init3"), calls)
        last.close();runCurrent()
    }

    @Test fun `same generation old local assembly close cannot release new reports`() = runTest {
        val h = reporting()
        val channel = IflytekSpeechChannel(StandardTestDispatcher(testScheduler))
        channel.attachReporting(h)
        val fake = config().copy(local = config().local.copy(asr = "iflytek.fake-cmd"))
        val old = channel.createLocalEngines(ContextWrapper(null), fake, backgroundScope)
        val replacement = channel.createLocalEngines(ContextWrapper(null), fake, backgroundScope)
        try {
            old.close();runCurrent()
            assertEquals(Lifecycle.DISABLED, h.registry.snapshot.value.components.getValue("local.asr").lifecycle)
            assertEquals(Lifecycle.READY, h.registry.snapshot.value.components.getValue("local.nlu").lifecycle)
        } finally { replacement.close();runCurrent() }
    }

}
