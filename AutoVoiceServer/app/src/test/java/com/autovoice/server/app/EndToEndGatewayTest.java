package com.autovoice.server.app;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.Intent;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 端到端测试：启动完整 Spring 上下文（RANDOM_PORT 避开 8080 被占），真实
 * VoiceGatewayHandler / SegmentPipeline / RaceArbiter 走通 {@code /ws} 全链路，
 * 三个 provider 用 @MockBean 替换为固定返回值（不真调云端 API）。
 *
 * <p>协议断言（shared/protocol.md §5）：hello → ready；audio_start → 二进制 PCM → audio_end →
 * 先收 decision（仲裁日志事件）再收 reply；reply 为 kind=action（intent + speakText + asrText，
 * 无音频——TTS 解耦）—— LLM mock 模拟 function calling 产出 action 回复
 * （语义已由模型工具调用承担，原 NLU 链路退役）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndGatewayTest {

    /** 任意 16k 字节 PCM 即可：mock asr 不真识别，仅验证二进制帧通路。 */
    private static final byte[] PCM_16K = new byte[16_000];

    private static final String CLIENT_SESSION_ID = "e2e-demo-1";
    private static final long RECEIVE_TIMEOUT_MS = 15_000;

    @LocalServerPort
    private int port;

    @MockBean
    private AsrProvider asr;
    @MockBean
    private LlmProvider llm;
    @MockBean
    private TtsProvider tts;

    @BeforeEach
    void stubProviders() {
        when(asr.transcribe(any(), any())).thenReturn("空调调到二十四度");
        when(llm.chat(any(), any())).thenReturn(CompletableFuture.completedFuture(Reply.ofAction(
                Intent.of("1.0", "climate", "set_temperature", Map.of(), 0.95, "llm.car_control", null),
                "好的，空调温度已调到24度")));
        when(tts.synthesize(any(), any())).thenReturn(Reply.ofAudio("audio/wav", new byte[]{1, 2, 3}));
    }

    @Test
    void helloAudioEndToReplyFullFlow() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build();
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        CountDownLatch opened = new CountDownLatch(1);
        WebSocket ws = client.newWebSocket(new Request.Builder().url(wsUrl()).build(), new WebSocketListener() {
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
        });
        try {
            assertTrue(opened.await(10, TimeUnit.SECONDS), "ws 连接应建立");
            send(ws, "hello", Map.of("client", "autovoice-android",
                    "protocolVersion", "1.0", "sessionId", CLIENT_SESSION_ID));

            // hello → ready（sessionId 以服务端采纳为准）
            Map<String, Object> ready = await(inbox, "ready");
            Map<String, Object> readyPayload = payload(ready);
            String sessionId = (String) readyPayload.get("sessionId");
            assertNotNull(sessionId, "ready 应带 sessionId");
            assertEquals("zh-CN", readyPayload.get("language"));
            assertEquals("1.1", readyPayload.get("protocolVersion"));

            // audio_start → 二进制 PCM → audio_end（携带客户端生成的 segmentId，reply 应回显）
            send(ws, "audio_start", Map.of("sessionId", sessionId, "sampleRate", 16000,
                    "channels", 1, "encoding", "pcm_s16le", "segmentId", "seg-e2e-1"));
            assertTrue(ws.send(ByteString.of(PCM_16K)), "二进制 PCM 帧应发送成功");
            send(ws, "audio_end", Map.of("sessionId", sessionId, "durationMs", 1000));

            // 收 decision + reply（协议 §5 时序：decision 先于 reply）
            List<Map<String, Object>> untilReply = new ArrayList<>();
            Map<String, Object> reply = null;
            long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && reply == null) {
                String raw = inbox.poll(500, TimeUnit.MILLISECONDS);
                if (raw == null) {
                    continue;
                }
                if (raw.startsWith("__transport_failure__")) {
                    fail(raw);
                }
                Map<String, Object> msg = GatewayCodec.decode(raw);
                untilReply.add(msg);
                if ("reply".equals(msg.get("type"))) {
                    reply = msg;
                }
            }
            assertNotNull(reply, "应在 " + RECEIVE_TIMEOUT_MS + "ms 内收到 reply；实际收到: " + untilReply);
            assertTrue(untilReply.stream().noneMatch(m -> "error".equals(m.get("type"))),
                    "全流程不应收到 error: " + untilReply);

            // decision：至少一条，且先于 reply
            List<Map<String, Object>> decisions = untilReply.stream()
                    .filter(m -> "decision".equals(m.get("type")))
                    .toList();
            assertFalse(decisions.isEmpty(), "应收到至少一条 decision 事件");
            assertTrue(untilReply.indexOf(decisions.get(0)) < untilReply.indexOf(reply),
                    "decision 应先于 reply 到达");
            Map<String, Object> d = payload(decisions.get(0));
            assertEquals("cloud", d.get("arbiter"));
            assertEquals("llm", d.get("route"));
            assertEquals("llm_reply", d.get("reason"));
            assertNotNull(d.get("utteranceId"));
            assertInstanceOf(Number.class, d.get("timestampMs"));

            // reply：kind=action（intent + speakText + asrText，无音频——TTS 解耦）
            Map<String, Object> p = payload(reply);
            assertEquals("action", p.get("kind"));
            assertFalse(p.containsKey("mime"), "TTS 解耦后 reply 不得携带音频");
            assertFalse(p.containsKey("dataBase64"), "TTS 解耦后 reply 不得携带音频");
            String speakText = (String) p.get("speakText");
            assertNotNull(speakText);
            assertFalse(speakText.isBlank());
            Map<?, ?> intent = (Map<?, ?>) p.get("intent");
            assertNotNull(intent, "kind=action 下行应携带 intent");
            assertEquals("climate", intent.get("domain"));
            assertEquals("set_temperature", intent.get("intent"));
            // asrText：LLM 路径 = ASR 识别文本（Task 61 语义，端侧云端胜出写识别区）
            assertEquals("空调调到二十四度", p.get("asrText"));
            // segmentId 端到端回显：客户端可据此将 reply 对账到具体话语
            assertEquals("seg-e2e-1", p.get("segmentId"), "reply 应回显 audio_start 的 segmentId");
        } finally {
            ws.close(1000, "test done");
            client.dispatcher().executorService().shutdown();
        }
    }

    @Test
    void ttsRequestRoundTripReturnsAudioResponse() throws Exception {
        // TTS 解耦（协议 v1.1 §4.5）：tts_request → tts_response，独立于音频话语链路
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build();
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        CountDownLatch opened = new CountDownLatch(1);
        WebSocket ws = client.newWebSocket(new Request.Builder().url(wsUrl()).build(), new WebSocketListener() {
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
        });
        try {
            assertTrue(opened.await(10, TimeUnit.SECONDS), "ws 连接应建立");
            send(ws, "hello", Map.of("client", "autovoice-android",
                    "protocolVersion", "1.1", "sessionId", CLIENT_SESSION_ID));
            await(inbox, "ready");

            send(ws, "tts_request", Map.of("text", "好的，空调已打开", "segmentId", "tts-e2e-1"));
            Map<String, Object> res = await(inbox, "tts_response");
            Map<String, Object> p = payload(res);
            assertEquals("audio/wav", p.get("mime"));
            String dataBase64 = (String) p.get("dataBase64");
            assertNotNull(dataBase64);
            assertFalse(dataBase64.isEmpty());
            assertEquals("好的，空调已打开", p.get("text"), "tts_response 应回显请求文本");
            assertEquals("tts-e2e-1", p.get("segmentId"), "tts_response 应回显请求 segmentId");
        } finally {
            ws.close(1000, "test done");
            client.dispatcher().executorService().shutdown();
        }
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    private static void send(WebSocket ws, String type, Map<String, Object> payload) {
        assertTrue(ws.send(GatewayCodec.encode(type, payload)), "消息应发送成功: " + type);
    }

    /** 轮询收一条指定 type 的消息（忽略其它类型的消息）。 */
    private static Map<String, Object> await(LinkedBlockingQueue<String> inbox, String type)
            throws InterruptedException {
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
        throw new AssertionError("应在 " + RECEIVE_TIMEOUT_MS + "ms 内收到 '" + type + "' 消息");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> msg) {
        return (Map<String, Object>) msg.get("payload");
    }
}
