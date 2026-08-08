package com.autovoice.server.asrgateway;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AliyunTokenClientTest {

    MockWebServer server;
    AliyunTokenClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new AliyunTokenClient(new OkHttpClient(), "AK123", "SK456", server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void parsesTokenAndCachesUntilNearExpiry() throws Exception {
        // ExpireTime 2000000000（2033 年）→ 远离过期 → 第二次调用命中缓存
        server.enqueue(new MockResponse().setBody("""
                {"Token":"tok-abc","ExpireTime":2000000000}"""));

        assertEquals("tok-abc", client.token());
        assertEquals("tok-abc", client.token());

        // 缓存语义：第二次调用不再发请求
        assertEquals(1, server.getRequestCount());

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("GET", req.getMethod());
        // 签名方式：AK/SK 明文 query（nls token 接口实际签名方式）
        assertEquals("AK123", req.getRequestUrl().queryParameter("AccessKeyId"));
        assertEquals("SK456", req.getRequestUrl().queryParameter("AccessKeySecret"));
    }

    @Test
    void refetchesWhenExpireTimeWithinHeadroom() throws Exception {
        long nowSeconds = System.currentTimeMillis() / 1000;
        // 距过期 30s < 60s 提前量 → 第二次调用必须重新获取
        server.enqueue(new MockResponse().setBody("{\"Token\":\"tok-1\",\"ExpireTime\":" + (nowSeconds + 30) + "}"));
        server.enqueue(new MockResponse().setBody("{\"Token\":\"tok-2\",\"ExpireTime\":" + (nowSeconds + 3000) + "}"));

        assertEquals("tok-1", client.token());
        assertEquals("tok-2", client.token());

        assertEquals(2, server.getRequestCount());
    }
}
