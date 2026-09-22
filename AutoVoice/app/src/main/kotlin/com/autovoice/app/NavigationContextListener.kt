package com.autovoice.app

import com.autovoice.messaging.MessageListener
import com.autovoice.voicecore.GatewayMessage

/** Task protocol listener, independent of ASR, NLU and TTS listeners. */
internal class NavigationContextListener(
    private val onMissing: (NavigationTaskContextRef) -> Unit,
) : MessageListener {
    override fun onMessage(message: GatewayMessage) {
        if (message.type != "navigation_context_result") return
        val ref = runCatching {
            val p = message.payload
            if (p["status"]?.asString != "CONTEXT_MISSING") return
            NavigationTaskContextRef(p["taskId"].asString, p["taskRevision"].asLong,
                p["interactionId"].asString, p["selectionId"].asString)
        }.getOrNull() ?: return
        onMissing(ref)
    }
}
