package com.autovoice.voiceengine.cloud

import com.autovoice.gatewayclient.GatewayPayloadParser
import com.autovoice.messaging.ListenerRegistration
import com.autovoice.messaging.MessageDispatcher
import com.autovoice.messaging.MessageListener
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudSpeechEnginesTest {
    @Test
    fun `ASR publishes established and partial independently of NLU`() {
        val bridge = FakeBridge()
        val transcript = mutableListOf<AsrResult>()
        var established = 0
        val asr = CloudAsrEngine(bridge, { _, _, _ -> }, {}).also { it.register() }
        asr.recognize("turn-1", byteArrayOf(1), object : AsrSink {
            override fun onTurnEstablished() { established++ }
            override fun onTranscript(result: AsrResult) { transcript += result }
        })
        bridge.send("asr_turn_started")
        bridge.send("asr_partial", "text" to "导航去机场", "isFinal" to false)
        assertEquals(1, established)
        assertEquals(listOf(AsrResult("导航去机场", false)), transcript)

        asr.release("turn-1")
        bridge.send("asr_partial", "text" to "完整文字", "isFinal" to true)
        assertEquals(1, transcript.size)
        asr.close()
    }

    @Test
    fun `NLU routes decision pending text and final reply without starting another upload`() = runBlocking {
        val bridge = FakeBridge()
        val decisions = mutableListOf<DecisionEntry>()
        val partials = mutableListOf<String>()
        val pending = Channel<Unit>(Channel.BUFFERED)
        var pendingCount = 0
        var uploads = 0
        val nlu = CloudNluEngine(
            bridge = bridge,
            parser = GatewayPayloadParser(),
            sink = DecisionSink(decisions::add),
            pendingSignals = pending,
            onPendingReceived = { pendingCount++ },
            onReplyText = { text, _, _ -> partials += text },
            request = { _, _ -> uploads++; TextReply("reply") },
        ).also { it.register() }

        assertEquals("reply", (nlu.understand("turn-1", byteArrayOf(1), null) as TextReply).text)
        assertEquals(1, uploads)
        bridge.send("decision", "arbiter" to "cloud", "route" to "llm", "reason" to "model")
        bridge.send("pending")
        bridge.send("reply_partial", "text" to "你好", "isFinal" to false)
        bridge.send("reply", "kind" to "text", "text" to "完成")
        assertEquals(1, decisions.size)
        assertEquals(1, pendingCount)
        assertTrue(pending.tryReceive().isSuccess)
        assertEquals(listOf("你好"), partials)
        assertEquals("完成", (bridge.reply.await() as TextReply).text)
        bridge.send("error", "code" to "CLOUD_FAILED")
        assertEquals(1, bridge.errors)
    }

    @Test
    fun `unassociated ASR is ignored while chat ASR goes to dedicated sink`() {
        val bridge = FakeBridge().apply { associated = false }
        val chat = mutableListOf<String>()
        val asr = CloudAsrEngine(bridge, { text, _, _ -> chat += text }, {}).also { it.register() }
        bridge.send("asr_partial", "text" to "ignored")
        assertTrue(chat.isEmpty())
        bridge.send("asr_partial", "text" to "chat speech", "chat" to true)
        assertEquals(listOf("chat speech"), chat)
        assertNull(bridge.replySlot(JsonObject()))
        asr.close()
    }

    private class FakeBridge : CloudSpeechBridge {
        private val dispatcher = MessageDispatcher()
        val reply = CompletableDeferred<com.autovoice.voicecore.Reply>()
        var associated = true
        var errors = 0

        override fun register(types: Set<String>, listener: MessageListener): ListenerRegistration =
            dispatcher.register(types, listener)

        override fun replySlot(payload: JsonObject): CloudReplySlot? =
            if (associated) CloudReplySlot("turn-1", reply) else null

        override fun handleSpeechError(message: GatewayMessage) { errors++ }

        fun send(type: String, vararg fields: Pair<String, Any>) {
            val payload = JsonObject()
            fields.forEach { (name, value) ->
                when (value) {
                    is Boolean -> payload.addProperty(name, value)
                    is Number -> payload.addProperty(name, value)
                    else -> payload.addProperty(name, value.toString())
                }
            }
            dispatcher.dispatch(GatewayMessage(type, payload))
        }
    }
}
