package com.autovoice.server.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.autovoice.server.gateway.GatewayCodec;
import com.autovoice.server.llm.DeepSeekLlmProvider;
import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.McpToolExecutor;
import com.autovoice.server.skillmcp.SkillConfig;
import com.autovoice.server.skillmcp.SkillPlatformClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端到端：WS 说话 → ASR(假) → 真 DeepSeekLlmProvider(MockWebServer) 多轮循环
 * → 调 MCP 工具(MockWebServer 假 MCP) → 最终文本回复。验证 registry→注入→循环→执行全链。
 *
 * <p>真 provider 装配：@MockBean LlmProvider 的 stub 用 thenAnswer 委托容器外手工构造的
 * DeepSeekLlmProvider（MockWebServer 端点 + 容器内真实 McpSkillRegistry 的工具列表/执行路由）；
 * 真 registry 经 setUp 里同步 refresh() 填充会话快照（start() 的首拉发生在 mock 打桩前，
 * 会 NPE 一次属预期噪音，不影响本用例）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "autovoice.skill-manager.url=unused",          // 平台客户端被 @MockBean 替换
        "autovoice.skill-manager.service-token=t",
        "autovoice.skill-manager.poll-ms=600000",
        "autovoice.telemetry.db-path=${java.io.tmpdir}/mcp-e2e-${random.uuid}.db"})
class McpEndToEndGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 任意 16k 字节 PCM 即可：mock asr 不真识别，仅验证二进制帧通路。 */
    private static final byte[] PCM_16K = new byte[16_000];

    private static final String CLIENT_SESSION_ID = "mcp-e2e-demo-1";
    private static final long RECEIVE_TIMEOUT_MS = 15_000;

    @LocalServerPort int port;
    @Autowired OkHttpClient client;

    @MockBean AsrProvider asr;
    @MockBean TtsProvider tts;
    @MockBean LlmProvider llm;
    @MockBean SkillPlatformClient platform;

    /** 容器内真实 McpSkillRegistry（bean）：refresh 后持有到假 MCP 的会话快照。 */
    @Autowired McpSkillRegistry registry;

    MockWebServer llmServer;
    MockWebServer mcpServer;
    /** 假 MCP 的 tools/call 计数（断言 MCP 工具真被执行）。 */
    AtomicInteger toolCallCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        when(asr.transcribe(any(), any())).thenReturn("导航去西湖");
        when(tts.synthesize(any(), any(), any())).thenReturn(Reply.ofAudio("audio/wav", new byte[]{1, 2, 3}));

        // 假 MCP server（initialize / notifications/initialized / tools/list / tools/call；
        // GET=SSE 探测 405 回退纯 POST、DELETE=会话关闭确认——与 skill-mcp FakeMcpServer 同形）
        mcpServer = new MockWebServer();
        mcpServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("GET".equals(request.getMethod())) {
                    return new MockResponse().setResponseCode(405).setBody("");
                }
                if ("DELETE".equals(request.getMethod())) {
                    return new MockResponse().setResponseCode(200).setBody("");
                }
                try {
                    JsonNode req = MAPPER.readTree(request.getBody().readUtf8());
                    String method = req.path("method").asText("");
                    JsonNode id = req.path("id");
                    ObjectNode result = MAPPER.createObjectNode();
                    MockResponse resp = new MockResponse().setHeader("Content-Type", "application/json");
                    if (request.getHeader("MCP-Session-Id") != null) {
                        resp = resp.setHeader("MCP-Session-Id", request.getHeader("MCP-Session-Id"));
                    }
                    if ("initialize".equals(method)) {
                        result.put("protocolVersion", request.getHeader("MCP-Protocol-Version") == null
                                ? "2025-11-25" : request.getHeader("MCP-Protocol-Version"));
                        // SDK 要求服务端声明 tools 能力，否则 listTools 直接抛
                        // "Server does not provide tools capability"
                        result.putObject("capabilities").putObject("tools");
                        ObjectNode info = result.putObject("serverInfo");
                        info.put("name", "fake");
                        info.put("version", "1.0");
                        return resp.setHeader("MCP-Session-Id", "sess-1")
                                .setBody(rpc(id, result).toString());
                    }
                    if ("notifications/initialized".equals(method)) {
                        return new MockResponse().setResponseCode(202);
                    }
                    if ("tools/list".equals(method)) {
                        ArrayNode tools = result.putArray("tools");
                        tools.addObject().put("name", "poi_search").put("description", "搜索兴趣点")
                                .set("inputSchema", MAPPER.readTree(
                                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}"));
                        return resp.setBody(rpc(id, result).toString());
                    }
                    if ("tools/call".equals(method)) {
                        toolCallCount.incrementAndGet();
                        ArrayNode content = result.putArray("content");
                        content.addObject().put("type", "text").put("text", "找到 1 个结果：西湖");
                        result.put("isError", false);
                        return resp.setBody(rpc(id, result).toString());
                    }
                    result.put("error", "unknown method: " + method);
                    return resp.setBody(rpc(id, result).toString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        mcpServer.start();

        // 假 LLM：第 1 次（带 tools）调 poi_search，第 2 次最终文本
        llmServer = new MockWebServer();
        AtomicInteger calls = new AtomicInteger();
        llmServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                try {
                    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                    boolean hasTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                    if (n == 1 && hasTools) {
                        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                                + "\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"poi_search\",\"arguments\":\"{\\\"query\\\":\\\"西湖\\\"}\"}}]}}]}");
                    }
                    return new MockResponse().setBody("{\"choices\":[{\"message\":{"
                            + "\"content\":\"已为您找到西湖附近的景点。\"}}]}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        llmServer.start();

        when(platform.fetchEnabled()).thenReturn(List.of(new SkillConfig(
                "amap-maps", "高德地图", "导航", mcpServer.url("/mcp").toString(),
                "", "", "", true, 1L)));

        // 同步 refresh 填充真 registry 会话快照：没有这一步 callTool("poi_search") 抛
        // "no skill owns tool"，LLM 退回直答、tools/call 计数断言必挂（start() 的首拉
        // 发生在 mock 打桩前会 NPE 一次，属预期日志噪音，不影响这里）
        registry.refresh();

        // 真 provider：容器外手工构造（真多轮工具循环 + 真工具路由），@MockBean llm 委托给它
        DeepSeekLlmProvider realProvider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                llmServer.url("/").toString(), NoopTelemetryRecorder.INSTANCE,
                () -> registry.enabledToolSpecs(), 5_000,
                new McpToolExecutor(registry::callTool));
        when(llm.chat(any(), any(), any())).thenAnswer(inv -> realProvider.chat(
                (String) inv.getArgument(0), (SessionContext) inv.getArgument(1), (String) inv.getArgument(2)));
    }

    @AfterEach
    void tearDown() throws Exception {
        llmServer.shutdown();
        mcpServer.shutdown();
    }

    @Test
    void wsSpeakDrivesRealLlmToolLoopIntoMcpTool() throws Exception {
        // WS 探针（同 EndToEndGatewayTest 客户端形态）：hello→ready→audio_start→PCM→audio_end→等 reply
        OkHttpClient wsClient = client.newBuilder().readTimeout(20, TimeUnit.SECONDS).build();
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        CountDownLatch opened = new CountDownLatch(1);
        WebSocket ws = wsClient.newWebSocket(new Request.Builder().url(wsUrl()).build(), new WebSocketListener() {
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
            Map<String, Object> ready = await(inbox, "ready");
            String sessionId = (String) payload(ready).get("sessionId");
            assertNotNull(sessionId, "ready 应带 sessionId");

            send(ws, "audio_start", Map.of("sessionId", sessionId, "sampleRate", 16000,
                    "channels", 1, "encoding", "pcm_s16le", "segmentId", "seg-mcp-1"));
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

            // decision：LLM 胜出（reason=llm_reply，先于 reply）
            List<Map<String, Object>> decisions = untilReply.stream()
                    .filter(m -> "decision".equals(m.get("type")))
                    .toList();
            assertFalse(decisions.isEmpty(), "应收到至少一条 decision 事件");
            assertTrue(untilReply.indexOf(decisions.get(0)) < untilReply.indexOf(reply),
                    "decision 应先于 reply 到达");
            assertEquals("llm_reply", payload(decisions.get(0)).get("reason"));

            // reply：kind=text，文本来自真 LLM 第 2 轮（工具结果续轮后的直答）
            Map<String, Object> p = payload(reply);
            assertEquals("text", p.get("kind"));
            assertEquals("已为您找到西湖附近的景点。", p.get("text"));
            assertEquals("已为您找到西湖附近的景点。", p.get("speakText"));
            assertEquals("导航去西湖", p.get("asrText"), "asrText 应为 ASR 识别文本");

            // 真 MCP 工具执行：tools/call 被调过恰好 1 次（poi_search）
            assertEquals(1, toolCallCount.get(), "MCP tools/call 应被调过 1 次");
        } finally {
            ws.close(1000, "test done");
            wsClient.dispatcher().executorService().shutdown();
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

    /** JSON-RPC 成功响应（回显请求 id：SDK 按 id 关联响应）。 */
    private static ObjectNode rpc(JsonNode id, ObjectNode result) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id);
        out.set("result", result);
        return out;
    }
}
