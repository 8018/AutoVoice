package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [AliyunTtsProvider] 协议测试（JUnit5 + MockWebServer WS 升级）：验证
 * run-task 帧的完整 schema（task_group/task/function/model/input/parameters）与
 * 事件驱动（task-started → binary → task-finished SUCCEEDED）→ wav 返回；
 * FAILED / task-failed / 超时 → RuntimeException（调用方降级为文本）。
 */
class AliyunTtsProviderTest {

    static final String API_KEY = "sk-test-dashscope";
    static final String TEXT = "空调调到二十四度";
    // 假 wav 字节（RIFF 头），server 端 WS 原样推送
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

    /**
     * server 端 WS 监听器基类：响应客户端关闭，避免 MockWebServer.shutdown() 挂起
     * （同 IflytekIatAsrProviderTest 模式）。
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

    private static String finishedFrame(String status) {
        return "{\"header\":{\"event\":\"task-finished\",\"task_id\":\"t1\",\"task_status\":\""
                + status + "\"},\"payload\":{\"output\":{},\"usage\":{\"characters\":6}}}";
    }

    @Test
    void synthesizesWavOverWebSocket() throws Exception {
        // run-task 是 WS 文本帧（握手后发送），在 server 端 listener 中捕获校验
        List<String> receivedFrames = new CopyOnWriteArrayList<>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String message) {
                receivedFrames.add(message);
            }

            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("{\"header\":{\"event\":\"task-started\",\"task_id\":\"t1\"},\"payload\":{}}");
                ws.send(ByteString.of(WAV_BYTES));                       // 音频分片
                ws.send("{\"header\":{\"event\":\"result-generated\",\"task_id\":\"t1\"},\"payload\":{}}");
                ws.send(finishedFrame("SUCCEEDED"));
            }
        }));

        Reply reply = provider.synthesize(TEXT, ctx("s1"));

        // SUCCEEDED + 累积二进制 → Reply audio：kind=audio / mime=audio/wav / data 相等
        assertEquals("audio", reply.kind());
        assertEquals("audio/wav", reply.mime());
        assertArrayEquals(WAV_BYTES, reply.data());

        // 握手请求：Bearer 鉴权
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("GET", req.getMethod());
        assertEquals("Bearer " + API_KEY, req.getHeader("Authorization"));
        assertEquals("websocket", req.getHeader("Upgrade"));

        // run-task 帧完整 schema
        assertEquals(1, receivedFrames.size(), "连接建立后应恰好发送一帧 run-task");
        JsonNode runTask = mapper.readTree(receivedFrames.get(0));
        JsonNode header = runTask.path("header");
        assertEquals("run-task", header.path("action").asText());
        assertEquals("out", header.path("streaming").asText());
        assertTrue(header.path("task_id").asText().length() > 0);
        JsonNode payload = runTask.path("payload");
        assertEquals("audio", payload.path("task_group").asText());
        assertEquals("tts", payload.path("task").asText());
        assertEquals("SpeechSynthesizer", payload.path("function").asText());
        assertEquals("sambert-zhimiao-emo-v1", payload.path("model").asText()); // Task 63 换音色：知妙·女声
        assertEquals(TEXT, payload.path("input").path("text").asText());
        JsonNode params = payload.path("parameters");
        assertEquals("PlainText", params.path("text_type").asText());
        assertEquals("wav", params.path("format").asText());
        assertEquals(16_000, params.path("sample_rate").asInt());
    }

    @Test
    void recordsSynthSuccessEvent() throws Exception {
        RecordingRecorder rec = new RecordingRecorder();
        AliyunTtsProvider recording = new AliyunTtsProvider(
                new OkHttpClient(), API_KEY, server.url("/").toString(), 15_000, rec);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("{\"header\":{\"event\":\"task-started\",\"task_id\":\"t1\"},\"payload\":{}}");
                ws.send(ByteString.of(WAV_BYTES));
                ws.send(finishedFrame("SUCCEEDED"));
            }
        }));

        Reply reply = recording.synthesize(TEXT, ctx("s1"), "utt-9");

        assertEquals("audio", reply.kind());
        assertEquals(1, rec.events.size());
        assertEquals("utt-9", rec.utteranceIds.get(0));
        var ev = rec.events.get(0);
        assertEquals(TelemetryStages.TTS_SYNTH, ev.stage());
        assertEquals("info", ev.level());
        assertEquals(WAV_BYTES.length, ev.payload().get("bytes"));
        assertTrue(((Number) ev.payload().get("durationMs")).longValue() >= 0, "应带合成耗时");
    }

    @Test
    void recordsSynthErrorEvent() {
        RecordingRecorder rec = new RecordingRecorder();
        AliyunTtsProvider recording = new AliyunTtsProvider(
                new OkHttpClient(), API_KEY, server.url("/").toString(), 15_000, rec);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("{\"header\":{\"event\":\"task-started\",\"task_id\":\"t1\"},\"payload\":{}}");
                ws.send(finishedFrame("FAILED"));
            }
        }));

        assertThrows(RuntimeException.class, () -> recording.synthesize(TEXT, ctx("s2"), "utt-9"));

        assertEquals(1, rec.events.size());
        assertEquals("utt-9", rec.utteranceIds.get(0));
        assertEquals(TelemetryStages.TTS_SYNTH, rec.events.get(0).stage());
        assertEquals("error", rec.events.get(0).level());
        assertTrue(rec.events.get(0).payload().get("error").toString().contains("FAILED"));
    }

    @Test
    void taskFinishedFailedThrowsRuntimeException() {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("{\"header\":{\"event\":\"task-started\"},\"payload\":{}}");
                ws.send(finishedFrame("FAILED"));
            }
        }));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> provider.synthesize(TEXT, ctx("s2")));
        assertTrue(ex.getMessage().contains("FAILED"), ex.getMessage());
    }

    @Test
    void taskFailedEventThrowsRuntimeException() {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("{\"header\":{\"event\":\"task-failed\",\"task_id\":\"t1\"},"
                        + "\"payload\":{\"error_code\":\"InvalidParameter\",\"error_message\":\"bad text\"}}");
            }
        }));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> provider.synthesize(TEXT, ctx("s3")));
        assertTrue(ex.getMessage().contains("InvalidParameter"), ex.getMessage());
    }

    @Test
    void timeoutThrowsRuntimeException() {
        // server 连接后不回任何帧 → 短超时（500ms）兜底
        AliyunTtsProvider slow = new AliyunTtsProvider(
                new OkHttpClient(), API_KEY, server.url("/").toString(), 500);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
        }));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> slow.synthesize(TEXT, ctx("s4")));
        assertTrue(ex.getMessage().contains("timeout"), ex.getMessage());
    }

    @Test
    void connectFailureThrowsRuntimeException() throws Exception {
        MockWebServer dead = new MockWebServer();
        dead.start();
        String url = dead.url("/").toString();
        dead.shutdown(); // 端口关闭 → WS 握手失败 → onFailure
        AliyunTtsProvider broken = new AliyunTtsProvider(new OkHttpClient(), API_KEY, url, 5_000);

        assertThrows(RuntimeException.class, () -> broken.synthesize(TEXT, ctx("s5")));
    }

    /** 构造带坏尺寸字段的 wav：标准 44 字节头 + data，RIFF chunkSize/dataSize 篡改为
     *  DashScope 实采垃圾值（0x7FFFFFBF / 0x7FFFFF9B，声明 ~2GB 而实际仅几十 KB）。 */
    private static byte[] brokenHeaderWav(int dataLen) {
        byte[] wav = new byte[44 + dataLen];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 0, 4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 8, 4);
        System.arraycopy("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 36, 4);
        wav[4] = (byte) 0xBF; wav[5] = (byte) 0xFF; wav[6] = (byte) 0xFF; wav[7] = 0x7F;   // chunkSize=0x7FFFFFBF
        wav[40] = (byte) 0x9B; wav[41] = (byte) 0xFF; wav[42] = (byte) 0xFF; wav[43] = 0x7F; // dataSize=0x7FFFFF9B
        return wav;
    }

    private static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    @Test
    void fixWavHeaderRewritesBrokenSizes() {
        byte[] broken = brokenHeaderWav(8000);
        byte[] fixed = AliyunTtsProvider.fixWavHeader(broken);

        assertEquals(8000L, readU32(fixed, 40), "dataSize 按实际数据长度重写");
        assertEquals(36L + 8000, readU32(fixed, 4), "RIFF chunkSize = 36 + dataSize");
        assertEquals(44 + 8000, fixed.length, "数据内容不动");
    }

    @Test
    void fixWavHeaderPassesThroughValidAndNonWav() {
        byte[] good = brokenHeaderWav(8000);
        good[40] = 0x40; good[41] = 0x1F; good[42] = 0x00; good[43] = 0x00; // dataSize=8000
        good[4] = 0x64; good[5] = 0x1F; good[6] = 0x00; good[7] = 0x00;     // chunkSize=8000+36=8036=0x1F64
        assertSame(good, AliyunTtsProvider.fixWavHeader(good), "头已正确原样返回");

        byte[] pcm = new byte[1000];
        assertSame(pcm, AliyunTtsProvider.fixWavHeader(pcm), "非 wav 原样返回");
        byte[] tiny = new byte[10];
        assertSame(tiny, AliyunTtsProvider.fixWavHeader(tiny), "<44 字节原样返回");
    }

    @Test
    void synthesizeFixesBrokenWavHeader() throws Exception {
        byte[] broken = brokenHeaderWav(200);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new ServerListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("{\"header\":{\"event\":\"task-started\",\"task_id\":\"t1\"},\"payload\":{}}");
                ws.send(ByteString.of(broken));                       // 坏头音频分片
                ws.send(finishedFrame("SUCCEEDED"));
            }
        }));

        Reply reply = provider.synthesize(TEXT, ctx("s6"));

        assertEquals(200L, readU32(reply.data(), 40), "synthesize 出口修复坏头");
        assertEquals(44 + 200, reply.data().length);
    }
}
