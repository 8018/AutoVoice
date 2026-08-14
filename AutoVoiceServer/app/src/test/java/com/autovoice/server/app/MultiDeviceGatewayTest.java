package com.autovoice.server.app;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.gateway.GatewayCodec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 多设备并发 E2E（多设备加固 M7.2）：双连接并行处理、segmentId 对账不串。
 *
 * <p>核心断言用 LLM mock 的汇合闩实现：两段话语必须同时处于处理中（M2 每连接
 * in-flight=1、连接专属 executor——若 handler 串行化，第一个 chat 阻塞等第二个，
 * 第二个排在其后永远进不来 → 闩超时失败）。各自 reply 回显自己的 segmentId
 * （audio_start 携带），证明决策/回复不跨连接串话。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "autovoice.telemetry.enabled=true",
                "autovoice.telemetry.db-path=${java.io.tmpdir}/autovoice-e2e-${random.uuid}.db",
                "autovoice.telemetry.audio-dir=${java.io.tmpdir}/autovoice-e2e-audio"
        })
class MultiDeviceGatewayTest {

    /** 任意 16k 字节 PCM 即可：mock asr 不真识别，仅验证二进制帧通路。 */
    private static final byte[] PCM_16K = new byte[16_000];
    private static final long RECEIVE_TIMEOUT_MS = 15_000;

    @LocalServerPort
    private int port;

    /** RANDOM_PORT 下自动配置：telemetry E2E 的 POST /round + GET /rounds/{utt}（与 WS 并列）。 */
    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private AsrProvider asr;
    @MockBean
    private LlmProvider llm;
    @MockBean
    private TtsProvider tts;

    @Test
    void twoConnectionsProcessInParallelWithSegmentIdMatching() throws Exception {
        // 汇合闩：两段话语都进入 LLM（并行处理证明）后才放行。
        // 若连接处理被串行化，chat#1 在闩上等待 chat#2，而 chat#2 排在其后无法进入 → 超时失败。
        CountDownLatch bothArrived = new CountDownLatch(2);
        when(llm.chat(any(), any(), any())).thenAnswer(inv -> {
            bothArrived.countDown();
            try {
                if (!bothArrived.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("两段话语未并行进入 LLM（处理被串行化）");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            return CompletableFuture.completedFuture(Reply.ofText("好的"));
        });
        when(asr.transcribe(any(), any())).thenReturn("空调调到二十四度");
        // 3 参入口（utteranceId 贯通，Task 5）：synthesize 现以 synthesize(text, ctx, utteranceId) 调用
        when(tts.synthesize(any(), any(), any())).thenReturn(Reply.ofAudio("audio/wav", new byte[]{1, 2, 3}));

        DeviceSession a = DeviceSession.open(port, "multi-device-a", "seg-A-1", "utt-par-a");
        DeviceSession b = DeviceSession.open(port, "multi-device-b", "seg-B-1", "utt-par-b");
        try {
            a.speak();
            b.speak();

            Map<String, Object> replyA = a.awaitReply();
            Map<String, Object> replyB = b.awaitReply();

            // segmentId 端到端对账：各自的 reply 回显各自的 segmentId（不串话）
            assertEquals("seg-A-1", payload(replyA).get("segmentId"), "A 的 reply 应回显 A 的 segmentId");
            assertEquals("seg-B-1", payload(replyB).get("segmentId"), "B 的 reply 应回显 B 的 segmentId");
            assertEquals("空调调到二十四度", payload(replyA).get("asrText"));
            assertEquals("空调调到二十四度", payload(replyB).get("asrText"));
            assertTrue(bothArrived.getCount() == 0, "两段话语应都已进入 LLM 处理");
        } finally {
            a.close();
            b.close();
        }
    }

    /**
     * 端云事件按 utteranceId 汇合（telemetry Task 9）：双连接各说一句（audio_start 携带
     * 端侧 utteranceId），服务端插桩事件（cloud_asr/cloud_arbiter）在 reply 前已入库；
     * 端侧事件包 POST /api/telemetry/round 后，GET /api/telemetry/rounds/{utt} 应同时
     * 看到两类事件——证明端侧包与服务端插桩在同一 round 汇合。
     *
     * <p>时序前提：reply 到达即流水线完成（record 先于 sendReply enqueue）；POST 与 GET
     * 都经 telemetry 单写线程串行化（syncQuery 与写入同队列），无需轮询。</p>
     */
    @Test
    void telemetryRoundMergesDeviceAndServerEvents() throws Exception {
        // @MockBean 在测试方法间重置：本方法内自建 stub（llm 快速返回，不用汇合闩）
        when(asr.transcribe(any(), any())).thenReturn("空调调到二十四度");
        when(llm.chat(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(Reply.ofText("好的")));
        when(tts.synthesize(any(), any(), any())).thenReturn(Reply.ofAudio("audio/wav", new byte[]{1, 2, 3}));

        // 同现有用例：双连接各说一句（utteranceId = utt-e2e-a / utt-e2e-b）
        DeviceSession a = DeviceSession.open(port, "telemetry-a", "seg-E2E-A", "utt-e2e-a");
        DeviceSession b = DeviceSession.open(port, "telemetry-b", "seg-E2E-B", "utt-e2e-b");
        try {
            a.speak();
            b.speak();
            assertNotNull(a.awaitReply(), "A 应收到 reply");
            assertNotNull(b.awaitReply(), "B 应收到 reply");
        } finally {
            a.close();
            b.close();
        }

        // 端侧事件包（模拟设备端插桩）：POST /api/telemetry/round
        String deviceRound = """
                {"utteranceId":"utt-e2e-a","sessionId":"s1","deviceId":"demo-1","source":"button",
                 "startMs":1000,"endMs":5000,
                 "events":[{"stage":"utterance_start","tsMs":1000,"level":"info","payload":{"source":"button"}},
                           {"stage":"local_asr","tsMs":1500,"level":"info","payload":{"text":"打开空调"}},
                           {"stage":"device_arbiter","tsMs":3000,"level":"info","payload":{"route":"cloud","reason":"cloud_won"}},
                           {"stage":"execute","tsMs":4000,"level":"info","payload":{"intent":"climate/set_temperature","result":"applied"}},
                           {"stage":"tts_request","tsMs":4500,"level":"info","payload":{"text":"空调调到二十四度"}},
                           {"stage":"tts_play","tsMs":4900,"level":"info","payload":{"source":"network","result":"ok"}}]}
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForObject("/api/telemetry/round", new HttpEntity<>(deviceRound, headers), String.class);

        // 查询汇合：GET /api/telemetry/rounds/utt-e2e-a（RoundDetail：events 是顶层字段）
        @SuppressWarnings("unchecked")
        Map<String, Object> round = rest.getForObject("/api/telemetry/rounds/utt-e2e-a", Map.class);
        assertNotNull(round, "round 应存在");
        List<?> events = (List<?>) round.get("events");
        assertNotNull(events, "events 应为顶层数组（{summary:{...}, events:[...]}）");
        Set<String> stages = events.stream()
                .map(e -> String.valueOf(((Map<?, ?>) e).get("stage")))
                .collect(Collectors.toSet());
        assertTrue(stages.containsAll(Set.of("utterance_start", "local_asr", "device_arbiter",
                "execute", "tts_request", "tts_play")),
                "端侧事件应汇合, 实际 stages: " + stages);
        // 服务端插桩在采纳的 utteranceId 下实际记录的 stage：cloud_asr + B3 拆分后的
        // cloud_arbiter_received(llm) + cloud_arbiter_won(llm, priority, llm_reply)
        // （ASR 文本非命令词 → 离线不命中；llm 为 @MockBean 自身不产生事件）。
        assertTrue(stages.containsAll(Set.of("cloud_asr", "cloud_arbiter_received", "cloud_arbiter_won")),
                "服务端插桩事件应汇合, 实际 stages: " + stages);
        // 汇合总数精确断言（T9 硬化）：6 端侧事件 + cloud_asr + received(llm) + won(llm) = 9
        assertEquals(9, events.size(), "汇合事件数应为 9, 实际 events: " + events);
    }

    /** 一条设备连接的封装：hello → ready → speak（audio_start/PCM/audio_end）→ awaitReply。 */
    private static final class DeviceSession {

        private final LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private WebSocket ws;
        private final String sessionId;
        private final String segmentId;
        private final String utteranceId;

        static DeviceSession open(int port, String clientSessionId, String segmentId, String utteranceId)
                throws Exception {
            OkHttpClient http = new OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build();
            CountDownLatch opened = new CountDownLatch(1);
            DeviceSession s = new DeviceSession(clientSessionId, segmentId, utteranceId, http, opened);
            s.ws = http.newWebSocket(new Request.Builder()
                    .url("ws://localhost:" + port + "/ws").build(), s.listener());
            assertTrue(opened.await(10, TimeUnit.SECONDS), "ws 连接应建立: " + clientSessionId);
            s.send("hello", Map.of("client", "autovoice-android",
                    "protocolVersion", "1.1", "sessionId", clientSessionId));
            Map<String, Object> ready = s.await("ready");
            assertNotNull(payload(ready).get("sessionId"), "ready 应带 sessionId: " + clientSessionId);
            return s;
        }

        private DeviceSession(String sessionId, String segmentId, String utteranceId,
                              OkHttpClient http, CountDownLatch opened) {
            this.sessionId = sessionId;
            this.segmentId = segmentId;
            this.utteranceId = utteranceId;
            this.http = http;
            this.opened = opened;
        }

        private final OkHttpClient http;
        private final CountDownLatch opened;

        private WebSocketListener listener() {
            return new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    opened.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    inbox.add(text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    inbox.add("__transport_failure__: " + t);
                }
            };
        }

        void speak() {
            // audio_start 携带端侧 utteranceId：服务端插桩事件（Task 2 采纳逻辑）落到该 ID 下
            send("audio_start", Map.of("sessionId", sessionId, "sampleRate", 16000,
                    "channels", 1, "encoding", "pcm_s16le", "segmentId", segmentId,
                    "utteranceId", utteranceId));
            assertTrue(ws.send(ByteString.of(PCM_16K)), "二进制 PCM 帧应发送成功: " + sessionId);
            send("audio_end", Map.of("sessionId", sessionId, "durationMs", 1000));
        }

        Map<String, Object> awaitReply() throws InterruptedException {
            long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                String raw = inbox.poll(500, TimeUnit.MILLISECONDS);
                if (raw == null) {
                    continue;
                }
                if (raw.startsWith("__transport_failure__")) {
                    fail(raw);
                }
                Map<String, Object> msg = GatewayCodec.decode(raw);
                if ("error".equals(msg.get("type"))) {
                    fail("收到 error: " + raw);
                }
                if ("reply".equals(msg.get("type"))) {
                    return msg;
                }
            }
            throw new AssertionError("应在 " + RECEIVE_TIMEOUT_MS + "ms 内收到 reply（sessionId=" + sessionId + "）");
        }

        private Map<String, Object> await(String type) throws InterruptedException {
            long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                String raw = inbox.poll(500, TimeUnit.MILLISECONDS);
                if (raw == null) {
                    continue;
                }
                Map<String, Object> msg = GatewayCodec.decode(raw);
                if (type.equals(msg.get("type"))) {
                    return msg;
                }
            }
            throw new AssertionError("应在 " + RECEIVE_TIMEOUT_MS + "ms 内收到 '" + type + "'（sessionId=" + sessionId + "）");
        }

        private void send(String type, Map<String, Object> payload) {
            assertTrue(ws.send(GatewayCodec.encode(type, payload)), "消息应发送成功: " + type);
        }

        void close() {
            ws.close(1000, "test done");
            http.dispatcher().executorService().shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> msg) {
        return (Map<String, Object>) msg.get("payload");
    }
}
