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
        assertEquals(DeepSeekLlmProvider.DEFAULT_SYSTEM_PROMPT,
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
    void chatParsesNavigateToolCallIntoActionReply() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture("deepseek-llm-navigate-call.json")));

        Reply reply = provider.chat("导航去杭州东站", ctx("s1")).get(5, TimeUnit.SECONDS);

        // tool_calls[0].function.arguments → navigate action 回复（speakText 模板生成）
        assertEquals("action", reply.kind());
        assertNotNull(reply.intent());
        assertEquals("navigation", reply.intent().domain());
        assertEquals("navigate", reply.intent().intent());
        assertEquals("杭州东站", reply.intent().slots().get("poiname").value());
        assertEquals(30.2896, ((Number) reply.intent().slots().get("lat").value()).doubleValue(), 0.0001);
        assertEquals(120.2108, ((Number) reply.intent().slots().get("lon").value()).doubleValue(), 0.0001);
        assertEquals("llm.navigate", reply.intent().source());
        assertEquals("好的，已为您规划去杭州东站的导航", reply.speakText());

        // 请求体必须携带两个终局工具（car_control + navigate）
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        JsonNode tools = body.path("tools");
        assertTrue(tools.isArray());
        assertEquals(2, tools.size());
        assertEquals("car_control", tools.get(0).path("function").path("name").asText());
        assertEquals("navigate", tools.get(1).path("function").path("name").asText());
    }

    @Test
    void chatParsesNavigateWaypointsIntoActionReply() throws Exception {
        // 多目的地（先去A再去B）：navigate 工具调用带 waypoints 数组
        String arguments = "{\"poiname\":\"大旗杆\",\"lat\":38.8731,\"lon\":115.4737,"
                + "\"waypoints\":[{\"poiname\":\"爱情广场\",\"lat\":38.8654,\"lon\":115.4696}]}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"call-2\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"navigate\",\"arguments\":"
                        + mapper.writeValueAsString(arguments) + "}}]}}]}"));

        Reply reply = provider.chat("导航去爱情广场再去大旗杆", ctx("s1")).get(5, TimeUnit.SECONDS);

        // 终点槽照常 + waypoints → string 槽（JSON 文本：SlotValue 无数组类型，
        // 端侧 parseSlots 对数组 value 直接丢弃，string 槽全链路无损）
        assertEquals("action", reply.kind());
        assertEquals("navigation", reply.intent().domain());
        assertEquals("navigate", reply.intent().intent());
        assertEquals("大旗杆", reply.intent().slots().get("poiname").value());
        JsonNode waypoints = mapper.readTree((String) reply.intent().slots().get("waypoints").value());
        assertTrue(waypoints.isArray());
        assertEquals(1, waypoints.size());
        assertEquals("爱情广场", waypoints.get(0).path("poiname").asText());
        assertEquals(38.8654, waypoints.get(0).path("lat").asDouble(), 0.0001);
        assertEquals(115.4696, waypoints.get(0).path("lon").asDouble(), 0.0001);
        // 话术：多途经点 → "先去A再去B的导航"
        assertEquals("好的，已为您规划先去爱情广场再去大旗杆的导航", reply.speakText());
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

    /** helper：最近一次请求的 system 消息 content（照抄现有 :85 的请求体解析写法）。 */
    private String capturedSystemContent() throws Exception {
        okhttp3.mockwebserver.RecordedRequest req = server.takeRequest();
        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        return body.path("messages").get(0).path("content").asText();
    }

    @Test
    void customSystemPromptUsed() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture("deepseek-llm-reply.json")));
        String custom = "你是高冷助手。";
        DeepSeekLlmProvider p = new DeepSeekLlmProvider(new OkHttpClient(), API_KEY,
                server.url("/chat/completions").toString(), (utt, e) -> {}, null, 0, null, () -> custom);
        p.chat(USER_TEXT, ctx("s1")).get(5, TimeUnit.SECONDS);
        assertEquals(custom, capturedSystemContent());
    }

    @Test
    void blankSupplierFallsBackToDefault() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture("deepseek-llm-reply.json")));
        DeepSeekLlmProvider p = new DeepSeekLlmProvider(new OkHttpClient(), API_KEY,
                server.url("/chat/completions").toString(), (utt, e) -> {}, null, 0, null, () -> "  ");
        p.chat(USER_TEXT, ctx("s1")).get(5, TimeUnit.SECONDS);
        assertEquals(DeepSeekLlmProvider.DEFAULT_SYSTEM_PROMPT, capturedSystemContent());
    }

    @Test
    void nullSupplierFallsBackToDefault() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture("deepseek-llm-reply.json")));
        DeepSeekLlmProvider p = new DeepSeekLlmProvider(new OkHttpClient(), API_KEY,
                server.url("/chat/completions").toString(), (utt, e) -> {}, null, 0, null, null);
        p.chat(USER_TEXT, ctx("s1")).get(5, TimeUnit.SECONDS);
        assertEquals(DeepSeekLlmProvider.DEFAULT_SYSTEM_PROMPT, capturedSystemContent());
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
