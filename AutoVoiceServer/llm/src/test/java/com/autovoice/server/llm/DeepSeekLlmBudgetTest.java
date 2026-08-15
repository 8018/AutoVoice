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

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DeepSeekLlmBudgetTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void zeroBudgetForcesDirectAnswer() throws Exception {
        // budget=0：第 1 次调用即不带 tools；模型直答
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                    boolean hasTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                    if (hasTools) {
                        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"不应出现\"}}]}");
                    }
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"直答文本\"}}]}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();
        try {
            ToolProvider tools = () -> List.of(new FunctionTool("t", "d", "{}"));
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE, tools, 0, (n, a) -> "x", null);
            Reply r = provider.chat("hi", new SessionContext("s", "zh", Map.of())).get(5, TimeUnit.SECONDS);
            assertEquals("直答文本", r.text());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void roundLimitEndsWithForcedFinalCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger noToolCalls = new AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                try {
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                    boolean hasTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                    if (!hasTools) {
                        noToolCalls.incrementAndGet();
                        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"最终答复\"}}]}");
                    }
                    // 每次都调工具（模型不收敛）
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                            + "\"tool_calls\":[{\"id\":\"call-x\",\"type\":\"function\","
                            + "\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]}}]}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();
        try {
            ToolProvider tools = () -> List.of(new FunctionTool("t", "d", "{}"));
            ToolExecutor exec = (n, a) -> "ok";
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE, tools, 60_000, exec, null);
            Reply r = provider.chat("hi", new SessionContext("s", "zh", Map.of())).get(5, TimeUnit.SECONDS);
            assertEquals("最终答复", r.text());
            assertEquals(5, calls.get());       // 4 轮带工具 + 1 轮强制直答
            assertEquals(1, noToolCalls.get());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void toolCallWithoutToolsThrows() throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // 无论是否带 tools 都返回工具调用（模型不配合）
                return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"call-x\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]}}]}");
            }
        });
        server.start();
        try {
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE, () -> List.of(), 0, (n, a) -> "x", null);
            // join()：future 异常完成 → CompletionException（RuntimeException 子类，与模块既有异常用例一致）
            assertThrows(RuntimeException.class, () -> provider.chat("hi",
                    new SessionContext("s", "zh", Map.of())).join());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void toolFailureReturnsFallbackToLlm() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                try {
                    if (n == 1) {
                        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]}}]}");
                    }
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                    String content = body.path("messages").toString().contains("服务不可用")
                            ? "抱歉，服务暂时不可用" : "错误答复";
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();
        try {
            ToolExecutor exec = (n, a) -> { throw new RuntimeException("高德服务不可用"); };
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE,
                    () -> List.of(new FunctionTool("t", "d", "{}")), 5_000, exec, null);
            Reply r = provider.chat("hi", new SessionContext("s", "zh", Map.of())).get(5, TimeUnit.SECONDS);
            assertEquals("抱歉，服务暂时不可用", r.text()); // 错误文本回 LLM → 兜底回复
        } finally {
            server.shutdown();
        }
    }
}
