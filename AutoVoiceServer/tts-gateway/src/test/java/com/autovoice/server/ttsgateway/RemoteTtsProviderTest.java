package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 网关 → tts-server 转发：请求体透传 / base64 回包 / 5xx、空音频、超时 → 异常。 */
class RemoteTtsProviderTest {

    private MockWebServer server;
    private RemoteTtsProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new RemoteTtsProvider(new OkHttpClient(),
                server.url("/tts").toString(), 15_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static SessionContext ctx(String sessionId) {
        return new SessionContext(sessionId, null, null);
    }

    @Test
    void forwardsTextAndSessionIdAndDecodesBase64() throws Exception {
        byte[] wav = {0x52, 0x49, 0x46, 0x46, 0x00};
        server.enqueue(new MockResponse().setBody(
                "{\"mime\":\"audio/wav\",\"dataBase64\":\"" + Base64.getEncoder().encodeToString(wav) + "\"}"));

        Reply reply = provider.synthesize("打开空调", ctx("demo-1"));

        RecordedRequest request = server.takeRequest();
        assertEquals("/tts", request.getPath());
        assertTrue(request.getHeader("Content-Type").startsWith("application/json"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"text\":\"打开空调\""), "应透传文本: " + body);
        assertTrue(body.contains("\"sessionId\":\"demo-1\""), "应透传 sessionId: " + body);

        assertEquals("audio", reply.kind());
        assertEquals("audio/wav", reply.mime());
        assertArrayEquals(wav, reply.data());
    }

    @Test
    void serverErrorYieldsRuntimeException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> provider.synthesize("打开空调", ctx("demo-1")));
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void emptyAudioYieldsRuntimeException() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"mime\":\"audio/wav\",\"dataBase64\":\"\"}"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> provider.synthesize("打开空调", ctx("demo-1")));
        assertTrue(e.getMessage().contains("empty audio"), e.getMessage());
    }

    @Test
    void timeoutYieldsRuntimeException() throws Exception {
        RemoteTtsProvider slow = new RemoteTtsProvider(new OkHttpClient(),
                server.url("/tts").toString(), 50);
        server.enqueue(new MockResponse().setBody("{\"mime\":\"audio/wav\",\"dataBase64\":\"eA==\"}")
                .setBodyDelay(500, TimeUnit.MILLISECONDS));

        assertThrows(RuntimeException.class, () -> slow.synthesize("打开空调", ctx("demo-1")));
    }
}
