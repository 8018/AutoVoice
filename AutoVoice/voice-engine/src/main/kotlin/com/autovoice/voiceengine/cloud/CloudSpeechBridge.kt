package com.autovoice.voiceengine.cloud

import com.autovoice.messaging.ListenerRegistration
import com.autovoice.messaging.MessageListener
import com.autovoice.voicecore.GatewayMessage
import com.autovoice.voicecore.Reply
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred

/** Cloud ASR/NLU see only typed messages and correlated speech slots, never the transport client. */
interface CloudSpeechBridge {
    fun register(types: Set<String>, listener: MessageListener): ListenerRegistration
    fun replySlot(payload: JsonObject): CloudReplySlot?
    fun handleSpeechError(message: GatewayMessage)
}

data class CloudReplySlot(val utteranceId: String, val deferred: CompletableDeferred<Reply>)
