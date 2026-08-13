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

    /** 模拟 LLM：第 1 次请求 → 调 poi_search 工具；第 2 次 → 最终文本。记录收到的请求数。 */
    private static MockWebServer twoRoundLlm(AtomicInteger calls) throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                try {
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
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
        try (MockWebServer llm = twoRoundLlm(calls)) {
            ToolProvider tools = () -> List.of(new FunctionTool("poi_search", "搜索兴趣点",
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"));
            ToolExecutor exec = (name, args) -> {
                execs.incrementAndGet();
                assertEquals("{\"query\":\"西湖\"}", args);
                return "西湖，国家 5A 级景区";
            };
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "test-key",
                    llm.url("/chat/completions").toString(), NoopTelemetryRecorder.INSTANCE,
                    tools, 5_000, exec);
            Reply r = provider.chat("导航去西湖", new SessionContext("s1", "zh", java.util.Map.of()))
                    .get(10, TimeUnit.SECONDS);
            assertEquals("text", r.kind());
            assertEquals("已为您查询到西湖附近的景点。", r.text());
            assertEquals(2, calls.get());
            assertEquals(1, execs.get());
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
