package com.autovoice.app.channel

import android.content.ContextWrapper
import com.autovoice.adapteriflytek.IflytekWakeWordObserver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WakeWordFactoryTest {
    // This common test runs for both flavors and exercises the actual independent wake adapter.
    @Test
    fun `both channels initialize independent wake and preserve its credential failure`() {
        SelectedSpeechChannel.create()
        val port = WakeWordFactory.create(ContextWrapper(null), {}, {}, WakeWordCredentials("", "", ""))
        val error = assertThrows(IllegalStateException::class.java) { port.initialize() }
        assertEquals(IflytekWakeWordObserver.NOT_CONFIGURED_MSG, error.message)
        assertEquals(IflytekWakeWordObserver.DEFAULT_KEYWORD, port.keyword)
        port.accept(ByteArray(1024))
        port.close()
    }
}
