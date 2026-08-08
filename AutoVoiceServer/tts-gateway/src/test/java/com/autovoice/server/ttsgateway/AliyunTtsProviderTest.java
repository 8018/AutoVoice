package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunTtsProviderTest {

    static final String API_KEY = "sk-test-dashscope";
    static final String TEXT = "空调调到二十四度";
    // 假 wav 字节（RIFF 头），MockWebServer 原样返回
    static final byte[] WAV_BYTES = {0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45};

    final ObjectMapper mapper = new ObjectMapper();

    MockWebServer server;
    AliyunTtsProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new AliyunTtsProvider(new OkHttpClient(), API_KEY, server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void synthesizesWavOn200() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(new Buffer().write(WAV_BYTES)));

        Reply reply = provider.synthesize(TEXT, ctx("s1"));

        // wav 字节 + 200 → Reply audio 回复：kind=audio / mime=audio/wav / data 相等
        assertEquals("audio", reply.kind());
        assertEquals("audio/wav", reply.mime());
        assertArrayEquals(WAV_BYTES, reply.data());

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        assertEquals("Bearer " + API_KEY, req.getHeader("Authorization"));
        assertNotNull(req.getHeader("Content-Type"));
        assertTrue(req.getHeader("Content-Type").startsWith("application/json"));

        // body：{"text":...,"format":"wav","sample_rate":16000}
        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        assertEquals(TEXT, body.path("text").asText());
        assertEquals("wav", body.path("format").asText());
        assertEquals(16_000, body.path("sample_rate").asInt());
    }

    @Test
    void non2xxThrowsRuntimeException() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"code\":\"InvalidApiKey\"}"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> provider.synthesize(TEXT, ctx("s2")));
        assertTrue(ex.getMessage().contains("401"));
    }

    private static SessionContext ctx(String sessionId) {
        return new SessionContext(sessionId, "zh-CN", Map.of());
    }
}
