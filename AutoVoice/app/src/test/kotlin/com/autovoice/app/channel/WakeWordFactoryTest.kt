package com.autovoice.app.channel

import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.app.health.ComponentStatusRegistry
import com.autovoice.voicecore.health.*
import android.content.ContextWrapper
import com.autovoice.adapteriflytek.IflytekWakeWordObserver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WakeWordFactoryTest {
    // This common test runs for both flavors and exercises the actual independent wake adapter.
    @Test
    fun `both channels initialize independent wake and preserve its credential failure`() {
        SelectedSpeechChannel.create()
        val port = WakeWordFactory.create(ContextWrapper(null), {}, {}, credentials = WakeWordCredentials("", "", ""))
        val error = assertThrows(IllegalStateException::class.java) { port.initialize() }
        assertEquals(IflytekWakeWordObserver.NOT_CONFIGURED_MSG, error.message)
        assertEquals(IflytekWakeWordObserver.DEFAULT_KEYWORD, port.keyword)
        port.accept(ByteArray(1024))
        port.close()
    }
    @Test
    fun `missing shared runtime credentials produce one root failure and dependent wake is blocked`() {
        val channel = SelectedSpeechChannel.create().id
        val registry = ComponentStatusRegistry()
        val reporting = ChannelComponentReporting(registry, channel, registry.beginGeneration(channel))
        reporting.registerDefaults()
        val config = reporting.reporter("config")
        config.report(config.start("config_validate"), ComponentUpdate.ready())
        val port = WakeWordFactory.create(ContextWrapper(null), {}, {}, reporting = { reporting },
            credentials = WakeWordCredentials("", "", ""))
        try {
            assertThrows(IllegalStateException::class.java) { port.initialize() }
            val snapshot = registry.snapshot.value
            val runtime = snapshot.components.getValue("iflytek.runtime")
            assertEquals(Lifecycle.FAILED, runtime.lifecycle)
            assertEquals(ErrorCode.SDK_CREDENTIALS_MISSING, runtime.error!!.code)
            val wake = snapshot.components.getValue("wake")
            assertEquals(Lifecycle.BLOCKED, wake.lifecycle)
            assertEquals("iflytek.runtime", wake.rootCauseId)
            assertNull(wake.error)
            assertEquals(1, snapshot.components.values.count { it.lifecycle == Lifecycle.FAILED })
            if (channel == "open") assertFalse(snapshot.components.getValue("local.asr").blockedBy.contains("iflytek.runtime"))
        } finally { port.close() }
    }

}
