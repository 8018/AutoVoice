package com.autovoice.server.ttsserver;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tts-server → 网关 telemetry 转发：单事件一条 POST（body {@code {utteranceId, events:[event]}}），
 * 异步 enqueue；gatewayTelemetryUrl 尾部斜杠归一，拼 {@code /events}。
 */
class TtsTelemetryForwarderTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void postsSingleEventBatchToGatewayEventsEndpoint() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        TtsTelemetryForwarder forwarder = new TtsTelemetryForwarder(
                new OkHttpClient(), server.url("/api/telemetry").toString());

        forwarder.record("utt-1", new TelemetryEvent("tts_request", 123L, "info", Map.of("text", "你好")));

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/api/telemetry/events", req.getPath());
        JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertEquals("utt-1", body.get("utteranceId").asText());
        JsonNode ev = body.get("events").get(0);
        assertEquals("tts_request", ev.get("stage").asText());
        assertEquals(123L, ev.get("tsMs").asLong());
        assertEquals("info", ev.get("level").asText());
        assertEquals("你好", ev.get("payload").get("text").asText());
    }

    @Test
    void normalizesTrailingSlashInBaseUrl() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        TtsTelemetryForwarder forwarder = new TtsTelemetryForwarder(
                new OkHttpClient(), server.url("/api/telemetry/").toString());

        forwarder.record("utt-2", new TelemetryEvent("tts_synth", 1L, "info", Map.of()));

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/api/telemetry/events", req.getPath(), "尾部斜杠应归一，不能拼出 //events");
    }

    @Test
    void nullUtteranceIdIsForwardedAsNullWithoutThrowing() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        TtsTelemetryForwarder forwarder = new TtsTelemetryForwarder(
                new OkHttpClient(), server.url("/").toString());

        forwarder.record(null, new TelemetryEvent("tts_synth", 1L, "info", Map.of()));

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/events", req.getPath());
        JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertTrue(body.get("utteranceId").isNull());
    }

    @Test
    void unreachableGatewayDoesNotThrow() {
        // 端口未监听：异步 enqueue 失败只 Log.w，record 不抛
        TtsTelemetryForwarder forwarder = new TtsTelemetryForwarder(
                new OkHttpClient(), "http://127.0.0.1:1/telemetry");
        forwarder.record("utt-3", new TelemetryEvent("tts_synth", 1L, "info", Map.of()));
    }
}
