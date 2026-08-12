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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiDeviceGatewayTest {

    /** 任意 16k 字节 PCM 即可：mock asr 不真识别，仅验证二进制帧通路。 */
    private static final byte[] PCM_16K = new byte[16_000];
    private static final long RECEIVE_TIMEOUT_MS = 15_000;

    @LocalServerPort
    private int port;

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
        when(llm.chat(any(), any())).thenAnswer(inv -> {
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
        when(tts.synthesize(any(), any())).thenReturn(Reply.ofAudio("audio/wav", new byte[]{1, 2, 3}));

        DeviceSession a = DeviceSession.open(port, "multi-device-a", "seg-A-1");
        DeviceSession b = DeviceSession.open(port, "multi-device-b", "seg-B-1");
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

    /** 一条设备连接的封装：hello → ready → speak（audio_start/PCM/audio_end）→ awaitReply。 */
    private static final class DeviceSession {

        private final LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private WebSocket ws;
        private final String sessionId;
        private final String segmentId;

        static DeviceSession open(int port, String clientSessionId, String segmentId) throws Exception {
            OkHttpClient http = new OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build();
            CountDownLatch opened = new CountDownLatch(1);
            DeviceSession s = new DeviceSession(clientSessionId, segmentId, http, opened);
            s.ws = http.newWebSocket(new Request.Builder()
                    .url("ws://localhost:" + port + "/ws").build(), s.listener());
            assertTrue(opened.await(10, TimeUnit.SECONDS), "ws 连接应建立: " + clientSessionId);
            s.send("hello", Map.of("client", "autovoice-android",
                    "protocolVersion", "1.1", "sessionId", clientSessionId));
            Map<String, Object> ready = s.await("ready");
            assertNotNull(payload(ready).get("sessionId"), "ready 应带 sessionId: " + clientSessionId);
            return s;
        }

        private DeviceSession(String sessionId, String segmentId, OkHttpClient http, CountDownLatch opened) {
            this.sessionId = sessionId;
            this.segmentId = segmentId;
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
            send("audio_start", Map.of("sessionId", sessionId, "sampleRate", 16000,
                    "channels", 1, "encoding", "pcm_s16le", "segmentId", segmentId));
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
