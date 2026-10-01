package com.autovoice.app
import com.autovoice.voicebusiness.navigation.NavigationTaskContextRef

import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayConnectionState

/** Navigation task context uses the shared gateway transport but owns its own message contract. */
internal class GatewayNavigationContextChannel(
    bridge: GatewayBridge,
    private val protocol: GatewayProtocolSender,
    private val client: GatewayClient,
    private val isReady: () -> Boolean,
    private val sessionId: () -> String,
    private val onMissing: (NavigationTaskContextRef) -> Unit,
) : AutoCloseable {
    private val listener = bridge.register(
        setOf("navigation_context_result"),
        NavigationContextListener { onMissing(it) },
    )

    fun publish(context: NavigationTaskContextRef) {
        val sid = sessionId()
        if (!isReady() || sid.isBlank() || client.connectionState.value != GatewayConnectionState.READY) return
        protocol.navigationSelection(sid, context)
    }

    override fun close() = listener.unregister()
}
