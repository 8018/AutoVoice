package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayConnectionState
import com.autovoice.gatewayclient.GatewayException
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.session.CloudRequestFailedException
import com.autovoice.voicecore.session.CloudUnavailableException
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** One explicit text request over the shared gateway. It owns no audio or ASR lifecycle. */
internal class GatewayBusinessTextChannel(
    private val client: GatewayClient,
    private val bridge: GatewayBridge,
    private val protocol: GatewayProtocolSender,
    private val ensureReady: suspend () -> Unit,
    private val sessionId: () -> String,
    private val onTransportFailure: (Throwable) -> Unit,
) {
    suspend fun run(requestId: String, text: String, language: String = "zh-CN"): Reply {
        require(requestId.isNotBlank() && text.isNotBlank())
        val segmentId = "text-${UUID.randomUUID()}"
        val slot = bridge.newReplySlot(segmentId, requestId)
        var sent = false
        try {
            ensureReady()
            if (!client.supportsCapability(GatewayProtocolSender.TEXT_RECOGNITION_CAPABILITY)) {
                throw CloudRequestFailedException("UNSUPPORTED_CAPABILITY: server does not accept text")
            }
            protocol.textRequest(sessionId(), requestId, requestId, segmentId, text, language)
            sent = true
            return slot.await()
        } catch (cancelled: CancellationException) {
            if (sent && client.connectionState.value == GatewayConnectionState.READY) {
                runCatching { protocol.cancelTurn(segmentId, "text_request_discarded") }
            }
            throw cancelled
        } catch (error: GatewayRemoteException) {
            if (error.code == "CONNECTION_FAILED" || error.code == "CONNECTION_CLOSED") {
                onTransportFailure(error)
                throw CloudUnavailableException("text connection lost; request will not be replayed", error)
            }
            throw CloudRequestFailedException("text request rejected (${error.code}): ${error.message}", error)
        } catch (error: GatewayException) {
            onTransportFailure(error)
            throw CloudUnavailableException("text connection lost; request will not be replayed", error)
        } finally {
            bridge.clearReplySlot(slot)
        }
    }
}
