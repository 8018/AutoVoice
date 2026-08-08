package com.autovoice.server.asrgateway;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [IflytekIatAsrProvider] 协议测试（JUnit5 + MockWebServer WS 升级）：
 *  - 签名 URL（固定 clock → 断言 authorization/date/host 结构与精确 date）；
 *  - 完整会话：0/1/2 分帧发送（common/business/audio 逐帧校验）→ 中间 + 终帧结果拼接；
 *  - code != 0 → AsrException；超时 → AsrException；连接失败 → AsrException。
 *
 * 凭据用测试桩值，不碰真实密钥。
 */
class IflytekIatAsrProviderTest {

    static final String APP_ID = "TEST_APPID";
    static final String API_KEY = "TEST_APIKEY";
    static final String API_SECRET = "TEST_APISECRET";
    static final Instant FIXED_TIME = Instant.parse("2026-01-09T00:00:00Z");
    static final String FIXED_DATE = "Fri, 09 Jan 2026 00:00:00 GMT";

    static final String BUSINESS_JSON =
            "{\"language\":\"zh_cn\",\"domain\":\"iat\",\"accent\":\"mandarin\",\"ptt\":1}";

    static final ObjectMapper MAPPER = new ObjectMapper();

    MockWebServer server;
    OkHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /**
     * 服务端监听器基类：收到对端 close 帧即回 close（完成关闭握手），
     * 否则连接悬挂，MockWebServer.shutdown() 等到超时报 queue 错误。
     */
    abstract static class ServerListener extends WebSocketListener {
        @Override
        public void onClosing(@NotNull WebSocket ws, int code, @NotNull String reason) {
            ws.close(1000, "bye");
        }
    }

    private static SessionContext ctx(String sessionId) {
        return new SessionContext(sessionId, "zh-CN", Map.of());
    }

    /** 服务端回一帧听写结果（ws 词序列；status 2 为终帧）。 */
    private static String resultFrame(int status, String... words) {
        StringBuilder ws = new StringBuilder("[");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) ws.append(",");
            ws.append("{\"bg\":0,\"cw\":[{\"w\":\"").append(words[i]).append("\"}]}");
        }
        ws.append("]");
        return "{\"code\":0,\"message\":\"Success\",\"data\":{\"status\":" + status
                + ",\"result\":{\"sn\":1,\"ws\":" + ws + "}}}";
    }

    private static String errorFrame(int code, String message) {
        return "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":{}}";
    }

    // ------------------------------------------------------------------ 签名

    /** 签名 URL：date 精确匹配固定 clock，host 取 endpoint，authorization 结构完整。 */
    @Test
    void signedUrlCarriesAuthorizationDateAndHost() throws Exception {
        // 注意：WS 升级监听器的 onOpen Response 是桩（无路径/query），签名 URL 必须用
        // takeRequest() 按原始请求行取（含 query）
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send(resultFrame(2)); // 空结果终帧 → transcribe 立即返回
            }
        }));
        IflytekIatAsrProvider provider = new IflytekIatAsrProvider(client, APP_ID, API_KEY, API_SECRET,
                server.url("/v2/iat").toString(), Clock.fixed(FIXED_TIME, ZoneOffset.UTC));

        assertEquals("", provider.transcribe(new byte[0], ctx("s1")));

        HttpUrl url = server.takeRequest().getRequestUrl();
        assertNotNull(url, "握手请求应携带签名 URL");
        assertEquals("/v2/iat", url.encodedPath());
        assertEquals(FIXED_DATE, url.queryParameter("date"), "date 应取自注入 clock（RFC1123 GMT）: " + url);
        assertEquals(server.getHostName(), url.queryParameter("host"));
        String authorization = new String(
                Base64.getDecoder().decode(url.queryParameter("authorization")), StandardCharsets.US_ASCII);
        assertTrue(authorization.contains("api_key=\"" + API_KEY + "\""), authorization);
        assertTrue(authorization.contains("algorithm=\"hmac-sha256\""), authorization);
        assertTrue(authorization.contains("headers=\"host date request-line\""), authorization);
        assertTrue(authorization.contains("signature=\""), authorization);
    }

    // ------------------------------------------------------------------ 完整会话

    /** 3 帧 PCM → 0/1/2 分帧发送，逐帧校验协议字段与 audio 切片；两段结果拼接返回。 */
    @Test
    void transcribesFramedPcmAndJoinsPartialResults() throws Exception {
        List<String> receivedFrames = new CopyOnWriteArrayList<>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String message) {
                receivedFrames.add(message);
                if (receivedFrames.size() == 1) ws.send(resultFrame(1, "打", "开")); // 中间结果
                if (receivedFrames.size() == 2) ws.send(resultFrame(2, "空", "调")); // 终帧
            }
        }));
        IflytekIatAsrProvider provider = new IflytekIatAsrProvider(client, APP_ID, API_KEY, API_SECRET,
                server.url("/v2/iat").toString(), Clock.systemUTC());
        byte[] pcm = new byte[IflytekIatAsrProvider.FRAME_BYTES * 3];

        assertEquals("打开空调", provider.transcribe(pcm, ctx("s1")));

        assertEquals(3, receivedFrames.size());
        checkFrame(receivedFrames.get(0), 0, pcm, 0);
        checkFrame(receivedFrames.get(1), 1, pcm, IflytekIatAsrProvider.FRAME_BYTES);
        checkFrame(receivedFrames.get(2), 2, pcm, IflytekIatAsrProvider.FRAME_BYTES * 2);
    }

    /** 单帧内不足一帧的余量：尾帧（status=2）携带剩余字节。 */
    @Test
    void sendsPartialTailFrameAsStatus2() throws Exception {
        List<String> receivedFrames = new CopyOnWriteArrayList<>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String message) {
                receivedFrames.add(message);
                if (receivedFrames.size() == 1) ws.send(resultFrame(2));
            }
        }));
        IflytekIatAsrProvider provider = new IflytekIatAsrProvider(client, APP_ID, API_KEY, API_SECRET,
                server.url("/v2/iat").toString(), Clock.systemUTC());
        byte[] pcm = new byte[IflytekIatAsrProvider.FRAME_BYTES + 100]; // 一帧 + 100B 尾

        assertEquals("", provider.transcribe(pcm, ctx("s1")));

        assertEquals(2, receivedFrames.size());
        checkFrame(receivedFrames.get(0), 0, pcm, 0);
        checkFrame(receivedFrames.get(1), 2, pcm, IflytekIatAsrProvider.FRAME_BYTES);
    }

    private static void checkFrame(String frameJson, int expectedStatus, byte[] pcm, int offset) throws Exception {
        JsonNode root = MAPPER.readTree(frameJson);
        assertEquals(APP_ID, root.path("common").path("app_id").asText(), "首帧应携带 app_id");
        JsonNode business = root.path("business");
        assertEquals("zh_cn", business.path("language").asText());
        assertEquals("iat", business.path("domain").asText());
        assertEquals("mandarin", business.path("accent").asText());
        assertEquals(1, business.path("ptt").asInt());
        JsonNode data = root.path("data");
        assertEquals(expectedStatus, data.path("status").asInt());
        assertEquals("audio/L16;rate=16000", data.path("format").asText());
        assertEquals("raw", data.path("encoding").asText());
        byte[] audio = Base64.getDecoder().decode(data.path("audio").asText());
        // 尾帧可能不足整帧：按实际发送长度比较
        int end = Math.min(offset + IflytekIatAsrProvider.FRAME_BYTES, pcm.length);
        assertArrayEquals(Arrays.copyOfRange(pcm, offset, end), audio);
    }

    // ------------------------------------------------------------------ 错误路径

    @Test
    void nonzeroCodeThrowsAsrException() {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String message) {
                ws.send(errorFrame(10161, "base64 decode error"));
            }
        }));
        IflytekIatAsrProvider provider = new IflytekIatAsrProvider(client, APP_ID, API_KEY, API_SECRET,
                server.url("/v2/iat").toString(), Clock.systemUTC());

        AsrException e = assertThrows(AsrException.class,
                () -> provider.transcribe(new byte[IflytekIatAsrProvider.FRAME_BYTES], ctx("s1")));
        assertTrue(e.getMessage().contains("code=10161"), e.getMessage());
        assertTrue(e.getMessage().contains("base64 decode error"), e.getMessage());
    }

    @Test
    void timesOutWhenServerNeverReplies() {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
        }));
        IflytekIatAsrProvider provider = new IflytekIatAsrProvider(client, APP_ID, API_KEY, API_SECRET,
                server.url("/v2/iat").toString(), BUSINESS_JSON, Clock.systemUTC(), 500);

        AsrException e = assertThrows(AsrException.class,
                () -> provider.transcribe(new byte[IflytekIatAsrProvider.FRAME_BYTES], ctx("s1")));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
    }

    @Test
    void connectFailureThrowsAsrException() {
        // 端口 1 无监听：握手失败 → onFailure → AsrException
        IflytekIatAsrProvider provider = new IflytekIatAsrProvider(client, APP_ID, API_KEY, API_SECRET,
                "ws://127.0.0.1:1/v2/iat", Clock.systemUTC());

        AsrException e = assertThrows(AsrException.class,
                () -> provider.transcribe(new byte[IflytekIatAsrProvider.FRAME_BYTES], ctx("s1")));
        assertTrue(e.getMessage().contains("iflytek iat"), e.getMessage());
    }
}
