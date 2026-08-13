package com.autovoice.server.llm;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekLlmProviderTest {

    static final String API_KEY = "test-deepseek-apikey";
    static final String USER_TEXT = "明天上海天气怎么样";

    final ObjectMapper mapper = new ObjectMapper();

    MockWebServer server;
    DeepSeekLlmProvider provider;
    final List<TelemetryEvent> events = new CopyOnWriteArrayList<>();
    final List<String> recordKeys = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        TelemetryRecorder recorder = (utt, e) -> {
            recordKeys.add(utt);
            events.add(e);
        };
        provider = new DeepSeekLlmProvider(new OkHttpClient(), API_KEY,
                server.url("/chat/completions").toString(), recorder);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void chatSendsOpenAiCompatibleBodyAndParsesContent() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture("deepseek-llm-reply.json")));

        Reply reply = provider.chat(USER_TEXT, ctx("s1")).get(5, TimeUnit.SECONDS);

        // 返回值：choices[0].message.content → Reply.ofText
        assertEquals("text", reply.kind());
        assertEquals("上海明天多云，25到31度。", reply.text());

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("Bearer " + API_KEY, req.getHeader(DeepSeekLlmProvider.HEADER_AUTHORIZATION));
        assertNotNull(req.getHeader("Content-Type"));
        assertTrue(req.getHeader("Content-Type").startsWith("application/json"));

        // 请求体：model=deepseek-chat + system+user 两条消息（system prompt 逐字用 brief 原文）
        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        assertEquals("deepseek-chat", body.path("model").asText());
        assertTrue(body.path("messages").isArray());
        assertEquals(2, body.path("messages").size());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals(DeepSeekLlmProvider.SYSTEM_PROMPT,
                body.path("messages").get(0).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals(USER_TEXT, body.path("messages").get(1).path("content").asText());
        assertTrue(body.path("stream").isBoolean()); // stream=false
    }

    @Test
    void chatParsesToolCallIntoActionReply() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture("deepseek-llm-tool-call.json")));

        Reply reply = provider.chat("把空调调到二十四度", ctx("s1")).get(5, TimeUnit.SECONDS);

        // tool_calls[0].function.arguments → car_control action 回复（speakText 模板生成）
        assertEquals("action", reply.kind());
        assertNotNull(reply.intent());
        assertEquals("climate", reply.intent().domain());
        assertEquals("set_temperature", reply.intent().intent());
        assertEquals(24.0, (double) reply.intent().slots().get("temperature").value(), 0.001);
        assertEquals("llm.car_control", reply.intent().source());
        assertEquals("好的，空调温度已调到24度", reply.speakText());

        // 请求体必须携带 tools（car_control skill 定义）
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        JsonNode tools = body.path("tools");
        assertTrue(tools.isArray());
        assertEquals("car_control", tools.get(0).path("function").path("name").asText());
    }

    @Test
    void chatRecordsLlmTelemetryEvent() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture("deepseek-llm-reply.json")));

        Reply reply = provider.chat(USER_TEXT, ctx("s1"), "utt-llm-1").get(5, TimeUnit.SECONDS);

        // llm 事件：3 参入口按 utteranceId 关联（时间线"大模型"阶段贯通）+ text/reply/durationMs
        TelemetryEvent e = events.stream()
                .filter(x -> TelemetryStages.LLM.equals(x.stage()))
                .findFirst().orElseThrow();
        assertEquals("info", e.level());
        assertEquals("utt-llm-1", recordKeys.get(0), "llm 事件应按 utteranceId 汇入");
        assertEquals(USER_TEXT, e.payload().get("text"));
        assertEquals("text:" + reply.text(), e.payload().get("reply"));
        assertTrue(e.payload().containsKey("durationMs"));
    }

    @Test
    void non2xxThrowsLlmException() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"rate limit\"}}"));

        CompletionException ex = assertThrows(CompletionException.class,
                () -> provider.chat(USER_TEXT, ctx("s2")).join());
        assertTrue(ex.getCause() instanceof LlmException);
        assertTrue(ex.getCause().getMessage().contains("429"));
    }

    @Test
    void emptyChoicesThrowsLlmException() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[]}"));

        CompletionException ex = assertThrows(CompletionException.class,
                () -> provider.chat(USER_TEXT, ctx("s3")).join());
        assertTrue(ex.getCause() instanceof LlmException);
    }

    @Test
    void networkFailureThrowsRuntimeException() throws Exception {
        server.shutdown(); // 连接被拒绝 → IOException → RuntimeException

        CompletionException ex = assertThrows(CompletionException.class,
                () -> provider.chat(USER_TEXT, ctx("s4")).join());
        assertTrue(ex.getCause() instanceof RuntimeException);
    }

    private static SessionContext ctx(String sessionId) {
        return new SessionContext(sessionId, "zh", Map.of());
    }

    private static String fixture(String name) throws Exception {
        return new String(Objects.requireNonNull(
                        DeepSeekLlmProviderTest.class.getClassLoader().getResourceAsStream(name))
                .readAllBytes(), StandardCharsets.UTF_8);
    }
}
