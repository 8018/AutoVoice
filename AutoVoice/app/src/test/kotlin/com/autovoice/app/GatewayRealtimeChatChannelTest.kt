package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.tts.RealtimePlaybackToken
import com.autovoice.voicecore.arbiter.DecisionSink
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayRealtimeChatChannelTest {
    @Test
    fun `exit while connection is opening cannot rearm chat or accept old output`() = runBlocking {
        val http = OkHttpClient()
        val client = GatewayClient("ws://localhost:1/ws", http, Gson())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = GatewayBridge(client, DecisionSink {}, scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val chat = GatewayRealtimeChatChannel(
            client, bridge, GatewayProtocolSender(client), scope,
            ensureReady = { entered.complete(Unit); release.await() },
            sessionId = { "session" },
            onReply = { _, _ -> throw AssertionError("closed chat must not emit a reply") },
        )
        try {
            val opening = async { runCatching { chat.startRealtimeChat() } }
            entered.await()
            chat.finishRealtimeChat()
            release.complete(Unit)
            assertTrue(opening.await().exceptionOrNull() is CancellationException)
            assertFalse(chat.isCurrentOutput(RealtimePlaybackToken(1, "reply")))
        } finally {
            scope.cancel()
            client.disconnect()
            http.dispatcher.executorService.shutdown()
        }
    }
}
