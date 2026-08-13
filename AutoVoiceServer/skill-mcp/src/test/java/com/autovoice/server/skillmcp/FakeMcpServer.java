package com.autovoice.server.skillmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最小 MCP streamable HTTP 假服务器：处理 initialize / notifications/initialized /
 * tools/list / tools/call 四个 JSON-RPC 方法；回显请求的 MCP-Session-Id 与 JSON-RPC id
 * （SDK 按请求 id 关联响应）。GET（SSE 探测）返回 405，DELETE 返回 200。
 */
final class FakeMcpServer implements AutoCloseable {

    static final ObjectMapper MAPPER = new ObjectMapper();
    final MockWebServer server = new MockWebServer();
    final AtomicInteger callCount = new AtomicInteger();

    FakeMcpServer() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("GET".equals(request.getMethod())) {
                    // SDK 探测 SSE 通道：本假服务器不支持 SSE，405 让其回退为纯 POST 请求-响应模式
                    return new MockResponse().setResponseCode(405).setBody("");
                }
                if ("DELETE".equals(request.getMethod())) {
                    // 会话关闭确认
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
                        result.putObject("capabilities").putObject("tools");
                        ObjectNode info = result.putObject("serverInfo");
                        info.put("name", "fake-mcp");
                        info.put("version", "1.0");
                        resp = resp.setHeader("MCP-Session-Id", "sess-1");
                        return resp.setBody(rpc(id, result).toString());
                    }
                    if ("notifications/initialized".equals(method)) {
                        return new MockResponse().setResponseCode(202).setBody("");
                    }
                    if ("tools/list".equals(method)) {
                        ArrayNode tools = result.putArray("tools");
                        tools.add(tool("poi_search", "搜索兴趣点", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"));
                        tools.add(tool("route_plan", "规划驾车路线", "{\"type\":\"object\"}"));
                        return resp.setBody(rpc(id, result).toString());
                    }
                    if ("tools/call".equals(method)) {
                        callCount.incrementAndGet();
                        ArrayNode content = result.putArray("content");
                        content.addObject().put("type", "text").put("text", "找到 1 个结果：西湖");
                        result.put("isError", false);
                        return resp.setBody(rpc(id, result).toString());
                    }
                    result.put("error", "unknown method: " + method);
                    return resp.setBody(rpc(id, result).toString());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private static ObjectNode tool(String name, String desc, String schema) throws IOException {
        ObjectNode t = MAPPER.createObjectNode();
        t.put("name", name);
        t.put("description", desc);
        t.set("inputSchema", MAPPER.readTree(schema));
        return t;
    }

    private static ObjectNode rpc(JsonNode id, ObjectNode result) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id);
        out.set("result", result);
        return out;
    }

    String url() {
        return server.url("/mcp").toString();
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }
}
