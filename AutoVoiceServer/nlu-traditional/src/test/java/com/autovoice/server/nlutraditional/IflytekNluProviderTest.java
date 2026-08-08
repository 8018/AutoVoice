package com.autovoice.server.nlutraditional;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.SessionContext;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IflytekNluProviderTest {

    static final String APPID = "test-appid";
    static final String API_KEY = "test-apikey";

    MockWebServer server;
    IflytekNluProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new IflytekNluProvider(new OkHttpClient(), APPID, API_KEY,
                server.url("/v1/aiui/v1/text_ai").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void understandReturnsCanonicalIntentWithSignedHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture("iflytek-semantic-ac.json")));

        Intent i = provider.understand("把温度调到24度", ctx("s1")).get(5, TimeUnit.SECONDS);

        assertEquals("climate", i.domain());
        assertEquals("set_temperature", i.intent());
        assertEquals(24.0, (double) i.slots().get("temperature").value(), 0.001);
        assertEquals("driver", i.slots().get("zone").value());
        assertEquals(IflytekNluProvider.SOURCE, i.source());

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        String appid = req.getHeader("X-Appid");
        String curTime = req.getHeader("X-CurTime");
        String param = req.getHeader("X-Param");
        String checksum = req.getHeader("X-CheckSum");
        assertNotNull(appid);
        assertNotNull(curTime);
        assertNotNull(param);
        assertNotNull(checksum);
        assertEquals(APPID, appid);
        assertTrue(curTime.matches("\\d{10}")); // Unix 秒

        // X-Param 解码后应为约定的语义参数 JSON
        assertEquals("{\"nlp_version\":\"3.0\",\"scene\":\"main\"}",
                new String(Base64.getDecoder().decode(param), StandardCharsets.UTF_8));

        // 请求体是纯文本，签名基于同一份 body 重算必须一致
        String body = req.getBody().readUtf8();
        assertEquals("把温度调到24度", body);
        assertTrue(req.getHeader("Content-Type").startsWith("text/plain"));
        assertEquals(hmacMd5Base64(API_KEY, curTime + param + body), checksum);
    }

    @Test
    void unknownWhenErrorCodeResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"10110\"}"));

        Intent i = provider.understand("你好", ctx("s2")).get(5, TimeUnit.SECONDS);

        assertTrue(i.isUnknown());
        assertEquals(IflytekNluProvider.SOURCE, i.source());
    }

    @Test
    void networkFailureThrowsRuntimeException() throws Exception {
        server.shutdown(); // 连接被拒绝 → IOException → RuntimeException

        CompletionException ex = assertThrows(CompletionException.class,
                () -> provider.understand("你好", ctx("s3")).join());
        assertTrue(ex.getCause() instanceof RuntimeException);
    }

    private static SessionContext ctx(String sessionId) {
        return new SessionContext(sessionId, "zh", Map.of());
    }

    private static String fixture(String name) throws Exception {
        return new String(java.util.Objects.requireNonNull(
                        IflytekNluProviderTest.class.getClassLoader().getResourceAsStream(name))
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 与 provider 相同的签名算法，独立重算用于校验 X-CheckSum。 */
    private static String hmacMd5Base64(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacMD5");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
