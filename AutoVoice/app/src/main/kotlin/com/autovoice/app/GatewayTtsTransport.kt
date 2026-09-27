package com.autovoice.app

import com.autovoice.gatewayclient.GatewayException
import com.autovoice.voicecore.AudioReply
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

/** Independent TTS request/response correlation over the shared gateway connection. */
internal class GatewayTtsTransport(
    private val bridge: GatewayBridge,
    private val protocol: GatewayProtocolSender,
    private val ensureReady: suspend () -> Unit,
) {
    suspend fun request(text: String, utteranceId: String): AudioReply? {
        val ttsId = UUID.randomUUID().toString()
        val slot = bridge.newTtsSlot(ttsId, utteranceId)
        return try {
            withTimeoutOrNull(TTS_TIMEOUT_MS) {
                ensureReady()
                protocol.tts(text, ttsId, utteranceId.takeIf(String::isNotBlank))
                slot.await()
            }
        } catch (_: GatewayException) {
            null
        } finally {
            bridge.clearTtsSlot(slot)
        }
    }

    private companion object {
        const val TTS_TIMEOUT_MS = 5_000L
    }
}
