package com.autovoice.server.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.ToolExecutor;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DeepSeekLlmLoopTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void navigationCannotBypassResolutionAndUsesDeviceCoordinates() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String name : List.of("navigate", "resolve_navigation")) {
                var body = MAPPER.createObjectNode();
                var fn = body.putArray("choices").addObject().putObject("message")
                        .putArray("tool_calls").addObject().put("id", name)
                        .put("type", "function").putObject("function");
                fn.put("name", name).put("arguments", name.equals("navigate")
                        ? "{\"poiname\":\"北京站\",\"lat\":39.9,\"lon\":116.4}"
                        : "{\"destinations\":[\"火车站\"],\"location\":\"116.4,39.9\"}");
                server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(body.toString()));
            }
            List<String> executed = new ArrayList<>();
            ToolProvider tools = () -> List.of(new FunctionTool("resolve_navigation", "导航", "{\"type\":\"object\"}",
                        com.autovoice.server.contracts.ToolExecutionTraits.INDEPENDENT_QUERY),
                        new com.autovoice.server.contracts.FunctionTool("navigate", "导航动作", "{\"type\":\"object\"}",
                                com.autovoice.server.contracts.ToolExecutionTraits.APPROVED_COMMIT));
            try (var provider = new DeepSeekLlmProvider(new OkHttpClient(), "test-key",
                    server.url("/chat").toString(), NoopTelemetryRecorder.INSTANCE,
                    tools, 5_000, (name, args) -> {
                        executed.add(name);
                        assertTrue(args.contains("104.06,30.65"));
                        assertFalse(args.contains("116.4,39.9"));
                        return "{\"destinations\":[{\"query\":\"火车站\",\"candidates\":[]}]}";
                    }, null)) {
                Reply result = provider.chat("导航到火车站", new SessionContext("s", "zh",
                        java.util.Map.of("latitude", 30.65, "longitude", 104.06))).get(10, TimeUnit.SECONDS);
                assertEquals(List.of("resolve_navigation"), executed);
                assertEquals("text", result.kind());
                assertNull(result.intent());
                assertEquals(2, server.getRequestCount(), "empty candidates must terminate the model loop");
            }
        }
    }

    /** 模拟 LLM：第 1 次请求 → 调 poi_search 工具；第 2 次 → 最终文本。记录收到的请求数与 body。 */
    private static MockWebServer twoRoundLlm(AtomicInteger calls, List<String> bodies) throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                try {
                    String bodyText = request.getBody().readUtf8();
                    bodies.add(bodyText);
                    JsonNode body = MAPPER.readTree(bodyText);
                    boolean hasTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                    String content;
                    if (n == 1 && hasTools) {
                        content = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"poi_search\",\"arguments\":\"{\\\"query\\\":\\\"西湖\\\"}\"}}]}}]}";
                    } else {
                        content = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"已为您查询到西湖附近的景点。\"}}]}";
                    }
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(content);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();
        return server;
    }

    @Test
    void twoRoundToolLoop() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger execs = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        try (MockWebServer llm = twoRoundLlm(calls, bodies)) {
            ToolProvider tools = () -> List.of(new FunctionTool("poi_search", "搜索兴趣点",
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}",
                    com.autovoice.server.contracts.ToolExecutionTraits.INDEPENDENT_QUERY));
            ToolExecutor exec = (name, args) -> {
                execs.incrementAndGet();
                assertEquals("{\"query\":\"西湖\"}", args);
                return "西湖，国家 5A 级景区";
            };
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "test-key",
                    llm.url("/chat/completions").toString(), NoopTelemetryRecorder.INSTANCE,
                    tools, 5_000, exec, null);
            Reply r = provider.chat("导航去西湖", new SessionContext("s1", "zh", java.util.Map.of()))
                    .get(10, TimeUnit.SECONDS);
            assertEquals("text", r.kind());
            assertEquals("已为您查询到西湖附近的景点。", r.text());
            assertEquals(2, calls.get());
            assertEquals(1, execs.get());
            // 第 2 轮请求必须回传工具结果消息（role=tool + tool_call_id），否则模型无法续轮
            assertEquals(2, bodies.size());
            boolean hasToolMsg = false;
            for (JsonNode m : MAPPER.readTree(bodies.get(1)).path("messages")) {
                if ("tool".equals(m.path("role").asText(""))) {
                    hasToolMsg = true;
                    assertEquals("call-1", m.path("tool_call_id").asText(""));
                    assertEquals("西湖，国家 5A 级景区", m.path("content").asText(""));
                }
            }
            assertTrue(hasToolMsg, "round 2 请求应含 role=tool 消息: " + bodies.get(1));
        }
    }

    @Test
    void carControlIsTerminalNoLoop() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    // 第 1 次请求返回 car_control 工具调用（默认工具，4 参构造器）
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                            + "\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                            + "\"function\":{\"name\":\"car_control\",\"arguments\":"
                            + "\"{\\\"domain\\\":\\\"climate\\\",\\\"action\\\":\\\"power_on\\\"}\"}}]}}]}");
                }
                // 若 provider 续轮，第 2 次请求应出现 —— 断言其不会发生
                fail("car_control 必须终局，不应有第 2 次 LLM 调用");
                return new MockResponse();
            }
        });
        server.start();
        try {
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "test-key",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE);
            Reply r = provider.chat("打开空调", new SessionContext("s1", "zh", java.util.Map.of()))
                    .get(10, TimeUnit.SECONDS);
            assertEquals("action", r.kind());
            assertEquals("climate", r.intent().domain());
            assertEquals("power_on", r.intent().intent());
            assertEquals(1, calls.get()); // 单轮终局，不续轮
        } finally {
            server.shutdown();
        }
    }
}
