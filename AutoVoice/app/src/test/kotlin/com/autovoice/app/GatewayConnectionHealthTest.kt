package com.autovoice.app

import com.autovoice.app.health.*
import com.autovoice.voicecore.CloudConfig
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.health.*
import com.autovoice.voiceengine.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production GatewayCloudRunner/OkHttp handshake -> production factory diagnostic binding. */
class GatewayConnectionHealthTest {
    @Test fun `real rejected warmup retry loss and recovery own fresh health attempts without initial business latch`() = runBlocking {
        val server = MockWebServer();val socket = AtomicReference<WebSocket>()
        fun rejectedResponse() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket,code: Int,reason: String) { ws.close(code,reason) }
            override fun onMessage(ws: WebSocket,text: String) {
                ws.send("""{"type":"error","payload":{"code":"BAD_AUTH","message":"secret-url-token"}}""")
            }
        })
        server.enqueue(rejectedResponse())
        fun readyResponse() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket,code: Int,reason: String) { ws.close(code,reason) }
            override fun onOpen(ws: WebSocket,response: Response) { socket.set(ws) }
            override fun onMessage(ws: WebSocket,text: String) {
                ws.send("""{"type":"ready","payload":{"sessionId":"test-session"}}""")
            }
        })
        server.enqueue(readyResponse());server.enqueue(rejectedResponse());server.enqueue(readyResponse());server.start()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val runner = GatewayCloudRunner(CloudConfig(enabled = true,gatewayUrl = "ws://localhost:${server.port}/",waitMs = 10),
            DecisionSink {},scope,recognitionGate = RecognitionGate(),asrModule = AsrModule(),nluModule = NluModule())
        val registry = ComponentStatusRegistry()
        val reporting = ChannelComponentReporting(registry,"open",registry.beginGeneration("open"));reporting.registerDefaults()
        val config = reporting.reporter("config");config.report(config.start("fixture"),ComponentUpdate.ready())
        val binding = VoiceEngineFactory.bindCloudHealth(runner,reporting.reporter("cloud"),true)
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<CloudConnectionDiagnostic>()
        val diagnosticBinding = runner.onConnectionDiagnostic
        runner.onConnectionDiagnostic = { diagnostics.add(it);diagnosticBinding(it) }
        val losses = AtomicInteger();runner.onCloudUnavailable = { losses.incrementAndGet() }
        suspend fun awaitStatus(test: (ComponentStatus) -> Boolean): ComponentStatus = withTimeout(5000) {
            while (true) { val s = registry.snapshot.value.components.getValue("cloud");if (test(s)) return@withTimeout s;delay(5) }
            error("unreachable")
        }
        try {
            runner.warmUp()
            val failed = awaitStatus { it.lifecycle == Lifecycle.FAILED }
            assertEquals(0,losses.get());assertTrue(failed.error!!.retryable)
            assertFalse(ComponentLogSink.diagnosticsJson(registry.snapshot.value).contains("secret-url-token"))
            runner.warmUp()
            val ready = awaitStatus { it.lifecycle == Lifecycle.READY && it.health == Health.HEALTHY }
            assertNotEquals(failed.attemptId,ready.attemptId);assertEquals(2,server.requestCount)
            socket.get().close(1001,"controlled loss")
            val lost = awaitStatus { it.health == Health.UNAVAILABLE }
            assertEquals(Lifecycle.READY,lost.lifecycle);assertEquals(ready.attemptId,lost.attemptId)
            withTimeout(1000) { while (losses.get() == 0) delay(5) }
            runner.warmUp()
            withTimeout(5000) { while (diagnostics.none { it.attempt == 3L && it.outcome == CloudConnectionOutcome.FAILED }) delay(5) }
            val retryFailed = awaitStatus { it.health == Health.UNAVAILABLE && it.sequence > lost.sequence }
            assertEquals(Lifecycle.READY,retryFailed.lifecycle);assertEquals(ready.attemptId,retryFailed.attemptId)
            runner.warmUp()
            val recovered = awaitStatus { it.lifecycle == Lifecycle.READY && it.health == Health.HEALTHY }
            assertEquals(ready.attemptId,recovered.attemptId);assertEquals(4,server.requestCount)
            assertEquals(listOf(1L,2L,3L,4L),diagnostics.filter { it.outcome == CloudConnectionOutcome.STARTED }.map { it.attempt })
            assertEquals(2,registry.snapshot.value.recentEvents.count { it.descriptor.componentId == "cloud" && it.update.lifecycle == Lifecycle.INITIALIZING })
            binding.accept(CloudConnectionDiagnostic(1,CloudConnectionOutcome.FAILED))
            assertEquals(recovered,registry.snapshot.value.components.getValue("cloud"))
            binding.close();runner.close()
            val closed = registry.snapshot.value.components.getValue("cloud")
            binding.accept(CloudConnectionDiagnostic(100,CloudConnectionOutcome.STARTED))
            runner.warmUp();delay(30)
            assertEquals(closed,registry.snapshot.value.components.getValue("cloud"));assertEquals(4,server.requestCount)
            val generation = registry.beginGeneration("open")
            val newer = ChannelComponentReporting(registry,"open",generation);newer.registerDefaults()
            binding.accept(CloudConnectionDiagnostic(101,CloudConnectionOutcome.FAILED))
            assertEquals(generation,registry.snapshot.value.generation)
        } finally { runner.close();binding.close();scope.cancel();server.shutdown() }
    }

    @Test fun `disabled cloud never warms or fabricates diagnostics and diagnostic exceptions do not break handshake`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(ws: WebSocket,code: Int,reason: String) { ws.close(code,reason) }
            override fun onMessage(ws: WebSocket,text: String) { ws.send("""{"type":"ready","payload":{"sessionId":"ok"}}""") }
        }));server.start()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        fun runner(enabled: Boolean) = GatewayCloudRunner(CloudConfig(enabled = enabled,gatewayUrl = "ws://localhost:${server.port}/",waitMs = 10),
            DecisionSink {},scope,recognitionGate = RecognitionGate(),asrModule = AsrModule(),nluModule = NluModule())
        val disabled = runner(false);val enabled = runner(true)
        val ready = CompletableDeferred<Unit>()
        disabled.onConnectionDiagnostic = { error("disabled must not report") }
        enabled.onConnectionDiagnostic = { error("broken diagnostic observer") }
        enabled.onConnectionEvent = { stage,_,_ -> if (stage == com.autovoice.app.telemetry.TelemetryStages.WS_READY) ready.complete(Unit) }
        try {
            disabled.warmUp();delay(30);assertEquals(0,server.requestCount)
            enabled.warmUp();withTimeout(5000) { ready.await() };assertEquals(1,server.requestCount)
        } finally { disabled.close();enabled.close();scope.cancel();server.shutdown() }
    }
}
