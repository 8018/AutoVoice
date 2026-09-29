package com.autovoice.app

import com.autovoice.gatewayclient.GatewayPayloadParser
import com.autovoice.messaging.ListenerRegistration
import com.autovoice.messaging.MessageListener
import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.AsrEngine
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.NluEngine
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.arbiter.DecisionSink
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.SendChannel

/** Voice-side cloud ASR. The gateway only dispatches protocol frames; ASR owns transcript output. */
internal class CloudAsrEngine(
    private val bridge: GatewayBridge,
    private val onAsrResult: (String, Boolean, String) -> Unit,
    private val onAsrTurnEstablished: (String) -> Unit,
) : AsrEngine, MessageListener {
    private val messageTypes = setOf("asr_turn_started", "asr_partial")
    private val turnSinks = ConcurrentHashMap<String, AsrSink>()

    fun register(): ListenerRegistration = bridge.register(messageTypes, this)

    /** The cloud upload is shared with NLU; ASR only installs the turn's independent output sink. */
    override fun recognize(turnId: String, segment: ByteArray, sink: AsrSink): AsrResult? {
        if (turnId.isNotBlank()) turnSinks[turnId] = sink
        return null
    }

    fun release(turnId: String) {
        if (turnId.isNotBlank()) turnSinks.remove(turnId)
    }

    fun close() = turnSinks.clear()

    override fun onMessage(message: GatewayMessage) {
        when (message.type) {
            "asr_turn_started" -> {
                val slot = bridge.replySlot(message.payload) ?: return
                val sink = turnSinks[slot.utteranceId]
                if (sink != null) sink.onTurnEstablished()
                else onAsrTurnEstablished(slot.utteranceId)
            }
            "asr_partial" -> {
                val text = message.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                val final = message.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                if (message.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                    onAsrResult(text, final, "")
                } else {
                    val slot = bridge.replySlot(message.payload) ?: return
                    val sink = turnSinks[slot.utteranceId]
                    if (sink != null) sink.onTranscript(AsrResult(text, final))
                    else onAsrResult(text, final, slot.utteranceId)
                }
            }
        }
    }
}

/** Voice-side cloud NLU. A reply is a semantic candidate; it is not adopted until arbitration. */
internal class CloudNluEngine(
    private val bridge: GatewayBridge,
    private val parser: GatewayPayloadParser,
    private val sink: DecisionSink,
    private val pendingSignals: SendChannel<Unit>,
    private val onPendingReceived: (String) -> Unit,
    private val onReplyText: (String, Boolean, String) -> Unit,
    private val request: suspend (ByteArray, String) -> Reply = { _, _ ->
        error("CloudNluEngine request transport is not configured")
    },
) : NluEngine<Reply>, MessageListener {
    private val messageTypes = setOf("decision", "reply_partial", "reply", "pending", "error")

    fun register(): ListenerRegistration = bridge.register(messageTypes, this)

    /** Await semantic output from the same request whose ASR events are already being observed. */
    override suspend fun understand(turnId: String, segment: ByteArray, asr: AsrResult?): Reply =
        request(segment, turnId)

    override fun onMessage(message: GatewayMessage) {
        when (message.type) {
            "decision" -> parseDecision(message.payload)?.let(sink::onDecision)
            "reply_partial" -> {
                val text = message.payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return
                val final = message.payload.get("isFinal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                if (message.payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                    onReplyText(text, final, "")
                } else {
                    val slot = bridge.replySlot(message.payload) ?: return
                    onReplyText(text, final, slot.utteranceId)
                }
            }
            "reply" -> {
                val slot = bridge.replySlot(message.payload) ?: return
                parser.reply(message.payload)?.let { slot.deferred.complete(it) }
            }
            "pending" -> {
                val slot = bridge.replySlot(message.payload) ?: return
                pendingSignals.trySend(Unit)
                onPendingReceived(slot.utteranceId)
            }
            "error" -> bridge.handleSpeechError(message)
        }
    }

    private fun parseDecision(payload: JsonObject): DecisionEntry? {
        val arbiter = payload.get("arbiter")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val route = payload.get("route")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val reason = payload.get("reason")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val utteranceId = payload.get("utteranceId")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val timestampMs = payload.get("timestampMs")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: System.currentTimeMillis()
        return DecisionEntry(arbiter, route, reason, utteranceId, timestampMs)
    }
}
