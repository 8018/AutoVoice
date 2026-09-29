package com.autovoice.app

import android.util.Log
import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayConnectionState
import com.autovoice.tts.RealtimePlaybackToken
import com.autovoice.voicecore.StreamingAudioReply
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Realtime transport owns chat generation/readiness; the app DM owns whether the chat domain is selected. */
internal class GatewayRealtimeChatChannel(
    private val client: GatewayClient,
    private val bridge: GatewayBridge,
    private val protocol: GatewayProtocolSender,
    private val scope: CoroutineScope,
    private val ensureReady: suspend () -> Unit,
    private val sessionId: () -> String,
    private val onReply: (RealtimePlaybackToken, StreamingAudioReply) -> Unit,
) : RealtimeChatRunner {
    @Volatile private var desired = false
    @Volatile private var ready = false
    private val reconnectRunning = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val activeChatId = AtomicReference<String?>(null)

    fun isCurrentOutput(token: RealtimePlaybackToken): Boolean =
        desired && generation.get() == token.generation

    fun onReply(reply: StreamingAudioReply) {
        if (desired && ready) {
            onReply(RealtimePlaybackToken(generation.get(), UUID.randomUUID().toString()), reply)
        }
    }

    fun onTransportLost() {
        if (!desired) return
        bridge.finishChat()
        onStreamFailed()
    }

    fun onStreamFailed() {
        generation.incrementAndGet()
        ready = false
        activeChatId.set(null)
        if (!desired || !reconnectRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                var delayMs = 500L
                repeat(3) {
                    delay(delayMs)
                    if (!desired) return@launch
                    val recovered = runCatching { connect() }.isSuccess
                    if (recovered) return@launch
                    delayMs *= 2
                }
                Log.w("GatewayRealtimeChat", "realtime chat reconnect exhausted")
            } finally {
                reconnectRunning.set(false)
            }
        }
    }

    override suspend fun startRealtimeChat() {
        desired = true
        connect()
    }

    private suspend fun connect() {
        generation.incrementAndGet()
        ready = false
        ensureReady()
        if (!desired) throw CancellationException("realtime chat exited before connection")
        val chatId = UUID.randomUUID().toString()
        activeChatId.set(chatId)
        bridge.beginChat(chatId)
        try {
            protocol.chatStart(sessionId(), chatId)
            bridge.awaitChatReady(chatId)
            if (!desired || activeChatId.get() != chatId) {
                throw CancellationException("realtime chat exited before ready")
            }
            ready = true
        } catch (failure: Exception) {
            if (activeChatId.compareAndSet(chatId, null)) {
                val sid = sessionId()
                if (sid.isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
                    runCatching { protocol.chatFinish(sid, chatId) }
                }
                bridge.finishChat()
            }
            throw failure
        }
    }

    override fun appendRealtimeAudio(pcm: ByteArray) {
        if (desired && ready && pcm.isNotEmpty()) protocol.chatAudio(pcm)
    }

    override fun finishRealtimeChat() {
        generation.incrementAndGet()
        desired = false
        ready = false
        val chatId = activeChatId.getAndSet(null)
        val sid = sessionId()
        if (chatId != null && sid.isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
            runCatching { protocol.chatFinish(sid, chatId) }
        }
        bridge.finishChat()
    }
}
