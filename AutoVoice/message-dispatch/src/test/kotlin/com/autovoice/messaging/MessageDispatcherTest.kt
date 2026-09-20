package com.autovoice.messaging

import com.autovoice.voicecore.GatewayMessage
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageDispatcherTest {
    @Test
    fun `fans one type out to multiple listeners and supports unregister`() {
        val dispatcher = MessageDispatcher()
        val received = mutableListOf<String>()
        val first = dispatcher.register(setOf("reply")) { received += "first" }
        dispatcher.register(setOf("reply", "error")) { received += "second:${it.type}" }

        dispatcher.dispatch(GatewayMessage("reply", JsonObject()))
        first.unregister()
        dispatcher.dispatch(GatewayMessage("reply", JsonObject()))
        dispatcher.dispatch(GatewayMessage("ignored", JsonObject()))

        assertEquals(listOf("first", "second:reply", "second:reply"), received)
    }

    @Test
    fun `listener failure is reported and does not block peers or later messages`() {
        val failures = mutableListOf<String>()
        val received = mutableListOf<String>()
        val dispatcher = MessageDispatcher { message, failure ->
            failures += "${message.type}:${failure.message}"
        }
        dispatcher.register(setOf("reply")) { error("broken observer") }
        dispatcher.register(setOf("reply")) { received += it.type }

        dispatcher.dispatch(GatewayMessage("reply", JsonObject()))
        dispatcher.dispatch(GatewayMessage("reply", JsonObject()))

        assertEquals(listOf("reply", "reply"), received)
        assertEquals(listOf("reply:broken observer", "reply:broken observer"), failures)
    }
}
