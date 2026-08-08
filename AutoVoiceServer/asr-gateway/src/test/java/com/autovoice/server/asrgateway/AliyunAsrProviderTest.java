package com.autovoice.server.asrgateway;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.SessionContext;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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

class AliyunAsrProviderTest {

    static final String APPKEY = "APPKEY";
    static final String TOKEN = "TEST-NLS-TOKEN";

    MockWebServer server;
    AliyunAsrProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new AliyunAsrProvider(new OkHttpClient(), APPKEY, server.url("/").toString(), () -> TOKEN);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void transcribes() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"status":20000000,"result":"空调调到二十四度"}""")
                .setHeader("Content-Type", "application/json"));

        String text = provider.transcribe(new byte[]{0, 0, 0, 0}, ctx("s1"));

        // brief 样例逐字：result 字段 → 返回识别文本
        assertEquals("空调调到二十四度", text);

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        // query：appkey/format=pcm/sample_rate=16000/enable_punctuation_prediction=true
        assertEquals(APPKEY, req.getRequestUrl().queryParameter("appkey"));
        assertEquals("pcm", req.getRequestUrl().queryParameter("format"));
        assertEquals("16000", req.getRequestUrl().queryParameter("sample_rate"));
        assertEquals("true", req.getRequestUrl().queryParameter("enable_punctuation_prediction"));
        // header：X-NLS-Token + Content-Type: audio/L16;rate=16000;channels=1
        assertEquals(TOKEN, req.getHeader("X-NLS-Token"));
        assertEquals("audio/L16;rate=16000;channels=1", req.getHeader("Content-Type"));
        // body = PCM 原样透传
        assertArrayEquals(new byte[]{0, 0, 0, 0}, req.getBody().readByteArray());
    }

    @Test
    void errorStatusThrowsAsrException() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"status":40000001,"message":"invalid appkey"}"""));

        AsrException ex = assertThrows(AsrException.class,
                () -> provider.transcribe(new byte[]{1}, ctx("s2")));
        assertTrue(ex.getMessage().contains("40000001"));
    }

    @Test
    void httpNon2xxThrowsAsrException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));

        AsrException ex = assertThrows(AsrException.class,
                () -> provider.transcribe(new byte[]{1}, ctx("s3")));
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void malformedJsonThrowsAsrException() throws Exception {
        server.enqueue(new MockResponse().setBody("not-json"));

        AsrException ex = assertThrows(AsrException.class,
                () -> provider.transcribe(new byte[]{1}, ctx("s4")));
        assertTrue(ex.getMessage().contains("not valid json"));
    }

    @Test
    void tokenSupplierFailureThrowsAsrException() throws Exception {
        AliyunAsrProvider broken = new AliyunAsrProvider(new OkHttpClient(), APPKEY,
                server.url("/").toString(), () -> {
                    throw new IllegalStateException("token fetch failed");
                });

        AsrException ex = assertThrows(AsrException.class,
                () -> broken.transcribe(new byte[]{1}, ctx("s5")));
        assertTrue(ex.getMessage().contains("token"));
    }

    private static SessionContext ctx(String sessionId) {
        return new SessionContext(sessionId, "zh-CN", Map.of());
    }
}
